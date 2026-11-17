"""The content check: each food's nutrition values are plausible for that food (#113)."""

import asyncio
import json
import logging
from collections.abc import Sequence

import pytest
from support.agent_fakes import fake_context
from support.food_fakes import (
    CONVERSATION,
    SOURCE_PHRASES,
    TODAY,
    Java,
    Model,
    answer,
    food_raw,
    run_check,
)
from support.food_fakes import food_list as foods

from tyler_agent.tools.builtin.food_record.asking import OPTIONAL_ATTEMPTS, evidence
from tyler_agent.tools.builtin.food_record.content_check import (
    VERIFY_FOOD_NUTRITION,
    check_content,
)
from tyler_agent.tools.builtin.food_record.correction import CORRECT_FOOD
from tyler_agent.tools.builtin.food_record.foods import FoodList, nutrition_text, record_text
from tyler_agent.tools.contracts import CompletedCall, RunSettings
from tyler_agent.tools.parsing import food_arguments
from tyler_agent.transcript import (
    AssistantTurn,
    ToolDefinition,
    TranscriptEntry,
    UserTurn,
)

CHICKEN = food_raw()
RICE = food_raw(foodName="Cooked rice", amount=1, unit="cup", calories=205)
OATS = food_raw(foodName="Oats", amount=50, calories=190)
# Ten times the calories of 200 g of chicken breast.
TOO_HIGH = food_raw(calories=3300)
LEAN = dict(protein=60, carbs=0, fat=6, fiber=0)
VERIFY, CORRECT = [VERIFY_FOOD_NUTRITION.name], [CORRECT_FOOD]
UNUSABLE_TEXT = AssistantTurn((), "The values look fine.")
REJECTED = "3300 kcal is ten times too high."
# The nutrition check is on unless the user switches it off.
SWITCH_ON = RunSettings()


def judged(valid: bool, reason: str = "Plausible.") -> AssistantTurn:
    """A verifyFoodNutrition answer."""
    return verdict_raw(json.dumps({"valid": valid, "reason": reason}))


def verdict_raw(raw: str) -> AssistantTurn:
    return answer(VERIFY_FOOD_NUTRITION.name, raw)


def written(raw: str) -> AssistantTurn:
    """A correctFood answer: one whole food record."""
    return answer(CORRECT_FOOD, raw)


def check(
    replies: Sequence[AssistantTurn | Exception],
    *raws: str,
    earlier: Sequence[CompletedCall] = (),
    conversation: tuple[UserTurn | AssistantTurn, ...] = CONVERSATION,
    settings: RunSettings = SWITCH_ON,
) -> tuple[FoodList, Model]:
    """Run the content check on these foods with a scripted model."""
    return run_check(
        check_content, replies, *raws, earlier=earlier, conversation=conversation, settings=settings
    )


def test_plausible_values_are_kept_after_one_request_for_each_food() -> None:
    result, model = check([judged(True), judged(True)], CHICKEN, RICE)
    assert result == foods(CHICKEN, RICE)
    assert model.tools == [VERIFY, VERIFY]


def test_the_check_is_shown_one_food_and_nothing_of_the_chat() -> None:
    """It judges the values against what is known about the food, so it is shown no
    conversation, tool result or date, and not the other foods."""
    earlier = [CompletedCall("readFile", '{"path": "plan.md"}', "FILE-TEXT")]
    _, model = check([judged(True), judged(True)], CHICKEN, RICE, earlier=earlier)
    first, second = model.prompts
    assert nutrition_text(food_arguments(CHICKEN)) in first
    for shown in ('"foodName" : "Chicken breast"', '"amount" : 200', '"calories" : 330'):
        assert shown in first, shown
    assert '"protein" : 62' in first and "Cooked rice" not in first
    assert "Cooked rice" in second and "Chicken breast" not in second
    for hidden in (
        "FILE-TEXT",
        TODAY,
        "2026-11-10",
        '"date"',
        *(turn.text for turn in CONVERSATION),
    ):
        assert hidden not in first, hidden


def test_an_implausible_estimate_is_corrected_and_only_its_nutrition_changes() -> None:
    """The correction writes a whole record, and only its nutrition values are kept."""
    rewritten = food_raw(
        macros=LEAN, foodName="Other", amount=1, unit="kg", calories=330, date="2026-01-01"
    )
    result, model = check([judged(False, REJECTED), written(rewritten)], TOO_HIGH)
    assert result == foods(food_raw(macros=LEAN, calories=330))
    assert model.tools == [VERIFY, CORRECT]
    correction = model.prompts[1]
    assert json.dumps(REJECTED) in correction
    assert record_text(food_arguments(TOO_HIGH)) in correction
    assert "Keep its name, amount, unit and date." in correction


def test_a_value_the_user_stated_survives_a_rejection() -> None:
    """The check cannot see that the user stated a value, so it may reject one. The
    correction is shown the conversation and told to keep stated values, and what it writes
    is saved without a second check, so the check cannot overrule the user."""
    said = "I ate a banana. Record it as 1900 kcal, that is what my tracker says."
    carbs = dict(protein=1.3, carbs=27, fat=0.4, fiber=3.1)
    banana = food_raw(foodName="Banana", amount=1, unit="medium", calories=1900)
    kept = food_raw(macros=carbs, foodName="Banana", amount=1, unit="medium", calories=1900)
    replies = [judged(False, "1900 kcal is far too high for a banana."), written(kept)]
    result, model = check(replies, banana, conversation=(UserTurn(said),))
    assert result == foods(kept)
    assert model.tools == [VERIFY, CORRECT]
    assert said not in model.prompts[0]
    correction = model.prompts[1]
    assert json.dumps(said) in correction
    assert "A value the user stated is right however unusual it looks" in correction
    assert "Keep every value the user stated, including nutrition values" in correction


def test_with_the_switch_off_no_request_is_made() -> None:
    result, model = check([], TOO_HIGH, settings=RunSettings(check_nutrition=False))
    assert result == foods(TOO_HIGH) and model.prompts == []


def test_a_list_with_no_foods_is_not_checked() -> None:
    result, model = check([])
    assert result == foods() and model.prompts == []


@pytest.mark.parametrize(
    "unusable",
    [
        UNUSABLE_TEXT,
        written(CHICKEN),
        AssistantTurn((judged(True).calls[0],) * 2, ""),
        verdict_raw("not json"),
        verdict_raw('{"valid": "true", "reason": "ok"}'),
        verdict_raw('{"valid": 1, "reason": "ok"}'),
        verdict_raw('{"valid": true}'),
        verdict_raw('{"valid": true, "reason": null}'),
        verdict_raw('{"valid": true, "reason": "ok", "extra": 1}'),
        verdict_raw('{"valid": false, "reason": " "}'),
    ],
)
def test_an_unusable_answer_is_asked_once_more_then_the_food_goes_on_unchanged(
    unusable: AssistantTurn,
) -> None:
    """The foods can be saved without this check, so it never fails the run, and an unusable
    answer is read neither as a pass nor as a rejection."""
    result, model = check([unusable, unusable], TOO_HIGH)
    assert result == foods(TOO_HIGH)
    assert model.tools == [VERIFY] * OPTIONAL_ATTEMPTS == [VERIFY, VERIFY]


def test_a_usable_answer_after_an_unusable_one_is_acted_on() -> None:
    replies = [UNUSABLE_TEXT, judged(False, REJECTED), written(CHICKEN)]
    result, model = check(replies, TOO_HIGH)
    assert result == foods(CHICKEN)
    assert model.tools == [VERIFY, VERIFY, CORRECT]


@pytest.mark.parametrize(
    "unusable",
    [
        UNUSABLE_TEXT,
        judged(True),
        # A whole record is needed: one without its nutrition values corrects nothing.
        written('{"genericInfo": {"foodName": "Chicken breast", "amount": 200, "unit": "g"}}'),
        # Matches the schema but exceeds the protocol's two-billion bound.
        written(food_raw(calories=3000000000)),
    ],
)
def test_an_unusable_correction_is_asked_once_more_then_the_values_stay(
    unusable: AssistantTurn,
) -> None:
    result, model = check([judged(False, REJECTED), unusable, written(CHICKEN)], TOO_HIGH)
    assert result == foods(CHICKEN)
    assert model.tools == [VERIFY, CORRECT, CORRECT]

    result, model = check([judged(False, REJECTED), unusable, unusable], TOO_HIGH)
    assert result == foods(TOO_HIGH)
    assert model.tools == [VERIFY, CORRECT, CORRECT]


class HeldChecks:
    """Hold every food's check until `expected` of them are waiting, so a check that asked
    about one food after another would never finish. Then accept every food except the one
    named `rejected`, and answer its correction with `corrected`."""

    def __init__(self, expected: int, rejected: str, corrected: str) -> None:
        self.java = Java()
        self.expected = expected
        self.rejected = rejected
        self.corrected = corrected
        self.waiting = 0
        self.release = asyncio.Event()

    async def __call__(
        self, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        (turn,) = transcript
        assert isinstance(turn, UserTurn)
        if [tool.name for tool in tools] == CORRECT:
            return written(self.corrected)
        self.waiting += 1
        if self.waiting == self.expected:
            self.release.set()
        await self.release.wait()
        return judged(self.rejected not in turn.text, REJECTED)


def test_every_food_is_checked_at_the_same_time_and_the_order_is_kept() -> None:
    fixed = food_raw(foodName="Cooked rice", amount=1, unit="cup", calories=205, macros=LEAN)
    model = HeldChecks(3, "Cooked rice", fixed)
    context = fake_context(model.java, model, CONVERSATION, SWITCH_ON)

    async def scenario() -> FoodList:
        check = check_content(foods(CHICKEN, RICE, OATS), await evidence(context), context)
        return await asyncio.wait_for(check, 3)

    assert asyncio.run(scenario()) == foods(CHICKEN, fixed, OATS)
    assert model.waiting == 3


def test_logs_record_counts_but_no_food_or_reason(caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.INFO, logger="tyler_agent")
    private = food_raw(foodName="PRIVATE-FOOD", calories=3300)
    check(
        [judged(False, "PRIVATE-REASON"), written(CHICKEN), UNUSABLE_TEXT, judged(True)],
        private,
        RICE,
    )
    messages = [entry.getMessage() for entry in caplog.records]
    assert "Food check answer unusable: tool=verifyFoodNutrition attempt=1" in messages
    assert "Food nutrition checked: foods=2 corrected=1" in messages
    text = "\n".join(messages)
    for hidden in ("PRIVATE-FOOD", "PRIVATE-REASON", "rice", "3300"):
        assert hidden not in text


def test_estimates_are_judged_against_the_sources_recordfood_names() -> None:
    """The check judges values against the sources recordFood's description gives the chat
    model, and the bar stays plausibility, not an exact match (#105)."""
    _, model = check([judged(True)], CHICKEN)
    (prompt,) = model.prompts
    for phrase in SOURCE_PHRASES:
        assert phrase in prompt, phrase
    assert "Do not reject a value for differing slightly." in prompt
