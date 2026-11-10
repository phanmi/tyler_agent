"""In-memory HTTP server for provider SDK tests; it never opens a network connection."""

import json
from typing import Any

import httpx2


class Server:
    """Record request bodies and answer from a script, one response per request."""

    def __init__(self, responses: list[httpx2.Response]) -> None:
        self.responses = responses
        self.requests: list[httpx2.Request] = []

    def __call__(self, request: httpx2.Request) -> httpx2.Response:
        self.requests.append(request)
        return self.responses.pop(0)

    def body(self, index: int) -> Any:
        return json.loads(self.requests[index].content)
