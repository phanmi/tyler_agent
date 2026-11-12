"""A stand-in for the Java bridge, so a chat can run without the application."""

from collections.abc import Callable
from copy import deepcopy

from tyler_agent.protocol.protocol import Message

# The saved profile getUserInfo returns. It says nothing about food, so it cannot steer a case.
PROFILE: Message = {
    "User": {"name": "Sam", "gender": "not given", "age": 30, "jobType": "office work"},
    "Other": {"info": "", "expectationFromLLM": "Keep my food diary accurate."},
}


class UnexpectedOperation(Exception):
    """A tool requested a Java operation this stand-in has no answer for."""

    def __init__(self, name: str) -> None:
        self.name = name
        super().__init__(name)


class FakeJava:
    """Answers one chat's Java operations and keeps the food records it saved.

    `saved` starts empty and grows by one record for each food.insert; nothing removes a
    record. A new chat gets a new instance. The stand-in applies none of Java's domain rules,
    so a record Java would reject is kept here and scored as a wrong save.
    """

    def __init__(self, now: str) -> None:
        self._now = now
        self.saved: list[Message] = []

    async def operation(self, name: str, value: Message) -> Message:
        answer = OPERATIONS.get(name)
        if answer is None:
            raise UnexpectedOperation(name)
        return answer(self, value)

    def current_time(self, value: Message) -> Message:
        return {"text": self._now}

    def insert_food(self, value: Message) -> Message:
        self.saved.append(deepcopy(value["food"]))
        return {"food": deepcopy(value["food"]), "id": str(len(self.saved))}

    def read_profile(self, value: Message) -> Message:
        return {"profile": deepcopy(PROFILE)}


# A food chat needs only these. Any other operation, such as a file write, ends the chat as an
# error that names it; add an entry here only when a case needs that operation answered.
OPERATIONS: dict[str, Callable[[FakeJava, Message], Message]] = {
    "time.current": FakeJava.current_time,
    "food.insert": FakeJava.insert_food,
    "user_info.read": FakeJava.read_profile,
}
