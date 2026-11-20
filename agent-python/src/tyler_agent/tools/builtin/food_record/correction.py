"""The correct food tool: one request that writes one food record.

A check that finds a missing food, a wrong amount or implausible nutrition values asks for
the food's record to be written again. The model always writes a whole record; the check
that asked keeps only the fields its correction may change. An answer that leaves a field
out, or gives a negative number, is not used.
"""

from decimal import Decimal
from importlib.resources import files

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import Message
from tyler_agent.tools.builtin.food_record.asking import Evidence, Question
from tyler_agent.tools.discovery import read_definition
from tyler_agent.tools.parsing import ArgumentsRejected, food_arguments
from tyler_agent.transcript import ToolDefinition

CORRECT_FOOD = "correctFood"

CORRECT_FOOD_DESCRIPTION = (
    "Write one food diary record. It follows the rules recordFood gives for a food's amount, "
    "unit and nutrition values, which are: {rules}"
)

# Where recordFood's description turns from which foods go in its list to how one food is
# written. The correct food tool is given the description from here on. It writes one food
# and has no `foods` list, so the rules before this point would contradict its schema.
ONE_FOOD_RULES_START = "When the user gives an amount"

CORRECT_PROMPT = """\
You write one food diary record before it is saved. {task}

Today's date and time: {today}

{evidence}
{record}
Call {tool} exactly once with the record for this one food. Keep every value the user stated, \
including nutrition values, exactly as stated. Keep the amount the user gave in the user's own \
word for its unit, without converting it or adding a conversion to the unit. Estimate nutrition \
values from the food and amount only when the user did not give them."""

# Added when the correction rewrites a record that is already in the list.
RECORD_NOTE = """
The record to write again:
{record}
"""


def one_food_rules(description: str) -> str:
    """The part of recordFood's description that says how one food is written: its amount,
    unit and nutrition values. A description without ONE_FOOD_RULES_START is a mistake in
    the definition, so it raises ValueError."""
    return description[description.index(ONE_FOOD_RULES_START) :]


def correct_food_tool() -> ToolDefinition:
    """The correct food tool: the schema of one entry of recordFood's list, with recordFood's
    own rules for one food as its rules, so a corrected record has the shape and follows the
    rules of an entry the chat model writes."""
    definition = read_definition(files(__name__.rpartition(".")[0]))
    one_food: Message = definition["parameters"]["properties"]["foods"]["items"]
    rules = one_food_rules(definition["description"])
    return ToolDefinition(CORRECT_FOOD, CORRECT_FOOD_DESCRIPTION.format(rules=rules), one_food)


def food_correction(evidence: Evidence, task: str, record: str | None = None) -> Question[Message]:
    """The question that writes one food record. `task` says what to write and why; `record`
    is the text of the record being written again, or None for a food that has none yet.

    The caller builds `task`, and puts any text that came from the model or the user into it
    as a JSON string.
    """
    tool = correct_food_tool()
    prompt = CORRECT_PROMPT.format(
        task=task,
        today=evidence.today,
        evidence=evidence.text,
        record="" if record is None else RECORD_NOTE.format(record=record),
        tool=tool.name,
    )
    return Question(prompt, tool, corrected_record)


def corrected_record(raw: str) -> Message:
    """Read a correction's arguments as one whole food record, as food.insert input.

    The tool's schema requires every field. OpenAI and Claude enforce that and DeepSeek does
    not, so a field that is missing or null is rejected here: a check would copy the gap over
    a value that could have been saved. A negative number is rejected too. Java has that rule
    as well; it is repeated here because the record is the model's answer to this tool, and
    an unusable answer is asked for again, where Java's rejection would fail the chat. A
    number beyond the protocol bound is one the user never supplied, so it is unusable too.
    """
    try:
        food = food_arguments(raw)
    except AgentFailure as error:
        raise ArgumentsRejected from error
    info, macros = food["food"]["genericInfo"], food["food"]["macroNutrients"]
    if info is None or macros is None or None in info.values() or None in macros.values():
        raise ArgumentsRejected
    numbers = (info["amount"], info["calories"], *macros.values())
    if any(Decimal(number) < 0 for number in numbers):
        raise ArgumentsRejected
    return food
