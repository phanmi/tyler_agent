"""Run every food check on the foods of one recordFood call, before any of them is saved.

The order matters. The name and count check settles which foods are in the list, so a food
it adds has its date checked next. The content check comes last, on the amounts the name and
count check left.

Adding a check takes a module with its tool and prompt, and one entry in CHECKS.
"""

from collections.abc import Awaitable, Callable, Sequence

from tyler_agent.protocol.protocol import Message
from tyler_agent.tools.builtin.food_record.asking import Evidence, evidence
from tyler_agent.tools.builtin.food_record.content_check import check_content
from tyler_agent.tools.builtin.food_record.date_check import check_dates
from tyler_agent.tools.builtin.food_record.foods import FoodList
from tyler_agent.tools.builtin.food_record.name_check import check_names
from tyler_agent.tools.contracts import ToolContext

# One check: it is given the list, the evidence and the run's context, and returns the list
# it leaves. A check that is switched off returns the list it was given.
type Check = Callable[[FoodList, Evidence, ToolContext], Awaitable[FoodList]]

CHECKS: tuple[Check, ...] = (check_names, check_dates, check_content)


async def checked_foods(foods: Sequence[Message], context: ToolContext) -> FoodList:
    """Return the foods to save, and the ones the checks removed with the reasons.

    This requests only the read-only time operation and model requests. A provider failure,
    a cancellation, or a required check that could not be answered propagates from the check
    it happened in, so the caller saves nothing.
    """
    shared = await evidence(context)
    checked = FoodList(tuple(foods))
    for check in CHECKS:
        checked = await check(checked, shared, context)
    return checked
