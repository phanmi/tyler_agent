"""Credential-free protocol prototype; the model loop is deliberately deferred to Step 7."""

import logging
import os
import re
import sys
from io import BufferedReader
from typing import BinaryIO, cast

from tyler_agent.protocol import FrameReader, Message, MessageCodec, ProtocolError
from tyler_agent.resources import release_version

log = logging.getLogger(__name__)


def serve(source: BufferedReader, sink: BinaryIO, session_id: str) -> int:
    """Announce readiness, reject unavailable runs explicitly, and exit on shutdown/EOF."""
    if not re.fullmatch("[a-f0-9]{32}", session_id):
        raise ProtocolError("SESSION_MISMATCH")
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
            if message["sessionId"] != session_id:
                raise ProtocolError("SESSION_MISMATCH")
            kind = message["type"]
            if kind not in {"run", "cancel", "operation_result", "shutdown"}:
                raise ProtocolError("WRONG_DIRECTION")
            # Peer identity and direction take precedence over payload values on the private wire.
            codec.validate_semantics(message)
            if kind == "shutdown":
                log.info("Worker shutdown accepted")
                return 0
            if kind == "run":
                request_id = message["requestId"]
                if request_id in requests:
                    raise ProtocolError("DUPLICATE_MESSAGE")
                if len(requests) >= 10000:
                    raise ProtocolError("INVALID_SEQUENCE")
                requests.add(request_id)
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
            elif kind == "cancel" and message["requestId"] in requests:
                continue  # A cancel may cross the already-sent failure.
            else:
                raise ProtocolError("REQUEST_NOT_ACTIVE")
    reader.finish()
    log.info("Worker input closed")
    return 0


def main() -> int:
    logging.basicConfig(level=logging.INFO, stream=sys.stderr, format="%(levelname)s %(message)s")
    if os.name == "nt":
        import msvcrt

        msvcrt.setmode(sys.stdin.fileno(), os.O_BINARY)
        msvcrt.setmode(sys.stdout.fileno(), os.O_BINARY)
    try:
        return serve(
            cast(BufferedReader, sys.stdin.buffer),
            sys.stdout.buffer,
            os.environ.get("TYLER_AGENT_SESSION_ID", ""),
        )
    except ProtocolError as error:
        log.error("Worker protocol failure: code=%s", error.code)
        return 2
    except (OSError, ValueError):
        log.error("Worker resource or pipe failure")
        return 2
