"""Lifecycle fixture: main thread blocks like a synchronous provider while the real monitor runs."""

import os
import sys
from pathlib import Path
from threading import Event

from tyler_agent.lifecycle import ParentMonitor
from tyler_agent.protocol.protocol import MessageCodec
from tyler_agent.protocol.validation import PROTOCOL_VERSION


def main() -> None:
    monitor = ParentMonitor.from_environment()
    try:
        codec = MessageCodec()
        sys.stdout.buffer.write(
            codec.encode(
                {
                    "protocolVersion": PROTOCOL_VERSION,
                    "sessionId": os.environ["TYLER_AGENT_SESSION_ID"],
                    "requestId": None,
                    "type": "ready",
                    "payload": {"workerVersion": "test"},
                }
            )
        )
        sys.stdout.buffer.flush()
        codec.decode(sys.stdin.buffer.readline().removesuffix(b"\n"))
        Path(sys.argv[1]).write_text("pending", encoding="utf-8")
        Event().wait()  # Simulated blocked provider, no credentials or network.
    finally:
        monitor.close()


if __name__ == "__main__":
    main()
