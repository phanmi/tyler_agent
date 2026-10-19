"""Credential-free protocol prototype; the model loop is deliberately deferred to Step 7."""

import logging
import os
import sys
from collections.abc import Callable
from io import BufferedReader
from typing import BinaryIO, cast

from tyler_agent.lifecycle import ParentMonitor
from tyler_agent.protocol.protocol import FrameReader, Message, MessageCodec, ProtocolError
from tyler_agent.protocol.validation import validate_session_id
from tyler_agent.resources import release_version

log = logging.getLogger(__name__)

type MessageSender = Callable[[str, str | None, Message], None]


def serve(source: BufferedReader, sink: BinaryIO, session_id: str) -> int:
    """Announce readiness, reject unavailable runs explicitly, and exit on shutdown/EOF."""
    validate_session_id(session_id)
    codec = MessageCodec()
    reader = FrameReader(codec)
    requests: set[str] = set()

    def send(kind: str, request_id: str | None, payload: Message) -> None:
        sink.write(
            codec.encode(
                {
                    "protocolVersion": 1,
                    "type": kind,
                    "sessionId": session_id,
                    "requestId": request_id,
                    "payload": payload,
                }
            )
        )
        sink.flush()

    send("ready", None, {"workerVersion": release_version()})
    log.info("Worker ready: protocolVersion=1")
    while chunk := source.read1(65536):
        for message in reader.feed(chunk):
            codec.validator.validate_received(message, session_id, "java")
            if not _dispatch_message(message, requests, send):
                return 0
    reader.finish()
    log.info("Worker input closed")
    return 0


def _dispatch_message(message: Message, requests: set[str], send: MessageSender) -> bool:
    """Return whether to keep reading; the prototype never executes application operations."""
    kind = message["type"]
    if kind == "shutdown":
        log.info("Worker shutdown accepted")
        return False
    if kind == "run":
        _reject_unavailable_run(message["requestId"], requests, send)
        return True
    if kind == "cancel" and message["requestId"] in requests:
        return True  # A cancel may cross the already-sent failure.
    raise ProtocolError("REQUEST_NOT_ACTIVE", "Message does not belong to an active request")


def _register_request(request_id: str, requests: set[str]) -> None:
    if request_id in requests:
        raise ProtocolError("DUPLICATE_MESSAGE", "Request identifier has already been used")
    if len(requests) >= 10000:
        raise ProtocolError("INVALID_SEQUENCE", "Worker request history limit reached")
    requests.add(request_id)


def _reject_unavailable_run(request_id: str, requests: set[str], send: MessageSender) -> None:
    _register_request(request_id, requests)
    send(
        "failed",
        request_id,
        {
            "error": {
                "category": "worker",
                "code": "WORKER_UNAVAILABLE",
                "message": "Agent execution is not implemented in this prototype",
                "writeOutcome": "none",
            }
        },
    )


def main() -> int:
    logging.basicConfig(level=logging.INFO, stream=sys.stderr, format="%(levelname)s %(message)s")
    # sys.platform also lets type checkers exclude Windows-only APIs on other platforms.
    if sys.platform == "win32":
        import msvcrt

        msvcrt.setmode(sys.stdin.fileno(), os.O_BINARY)
        msvcrt.setmode(sys.stdout.fileno(), os.O_BINARY)
    monitor: ParentMonitor | None = None
    try:
        monitor = ParentMonitor.from_environment()
        return serve(
            cast(BufferedReader, sys.stdin.buffer),
            sys.stdout.buffer,
            os.environ.get("TYLER_AGENT_SESSION_ID", ""),
        )
    except ProtocolError as error:
        log.error("Worker protocol failure: code=%s detail=%s", error.code, error.detail)
        return 2
    except (OSError, ValueError):
        log.error("Worker resource or pipe failure")
        return 2
    finally:
        if monitor is not None:
            monitor.close()
