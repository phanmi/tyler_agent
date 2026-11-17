"""The correct food tool: one request that writes one food record.

A check that finds a missing food, a wrong amount or implausible nutrition values asks for
the food's record to be written again. The model always writes a whole record; the check
that asked keeps only the fields its correction may change.
"""

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

CORRECT_PROMPT = """\
You write one food diary record before it is saved. {task}

Today's date and time: {today}

The conversation, oldest message first:
{conversation}

The tool calls already made in this chat, oldest first, with their results:
{earlier_calls}
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


def correct_food_tool() -> ToolDefinition:
    """The correct food tool: the schema of one entry of recordFood's list, with recordFood's
    own description as its rules, so a corrected record has the shape and follows the rules
    of an entry the chat model writes."""
    definition = read_definition(files(__name__.rpartition(".")[0]))
    one_food: Message = definition["parameters"]["properties"]["foods"]["items"]
    description = CORRECT_FOOD_DESCRIPTION.format(rules=definition["description"])
    return ToolDefinition(CORRECT_FOOD, description, one_food)


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
        conversation=evidence.conversation,
        earlier_calls=evidence.earlier_calls,
        record="" if record is None else RECORD_NOTE.format(record=record),
        tool=tool.name,
    )
    return Question(prompt, tool, corrected_record)


def corrected_record(raw: str) -> Message:
    """Read a correction's arguments as one whole food record, as food.insert input.

    A record without its general info or its nutrition values cannot correct anything, and a
    number beyond the protocol bound is one the user never supplied, so both are rejected as
    unusable answers, not failed runs.
    """
    try:
        food = food_arguments(raw)
    except AgentFailure as error:
        raise ArgumentsRejected from error
    if food["food"]["genericInfo"] is None or food["food"]["macroNutrients"] is None:
        raise ArgumentsRejected
    return food
