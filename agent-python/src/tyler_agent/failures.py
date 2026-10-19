"""Safe run failures; arbitrary provider and local exception text never crosses the pipe."""

from tyler_agent.protocol.protocol import Message


class AgentFailure(Exception):
    """Carry an established category/code, with local causes retained only in Python."""

    def __init__(self, category: str, code: str) -> None:
        self.category = category
        self.code = code
        super().__init__(code)

    def payload(self, write_outcome: str) -> Message:
        return {
            "category": self.category,
            "code": self.code,
            "message": f"Agent execution failed: {self.code}",
            "writeOutcome": write_outcome,
        }


class OperationFailure(AgentFailure):
    """Preserve Java's failure details with the run outcome; Java retains its local cause."""

    def __init__(self, error: Message) -> None:
        super().__init__(error["category"], error["code"])
        self.error = dict(error)

    def payload(self, write_outcome: str) -> Message:
        return self.error | {"writeOutcome": write_outcome}
