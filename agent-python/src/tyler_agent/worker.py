"""Private pipe entry point with credential-free readiness and cancellable agent execution."""

import asyncio
import logging
import os
import sys
from io import BufferedReader
from threading import Thread
from typing import BinaryIO, cast

from tyler_agent.lifecycle import ParentMonitor
from tyler_agent.protocol.protocol import FrameReader, Message, MessageCodec, ProtocolError
from tyler_agent.protocol.validation import PROTOCOL_VERSION, validate_session_id
from tyler_agent.provider import open_provider
from tyler_agent.resources import release_version
from tyler_agent.runtime import ProviderFactory, WorkerRuntime

log = logging.getLogger(__name__)


def serve(
    source: BufferedReader,
    sink: BinaryIO,
    session_id: str,
    provider_factory: ProviderFactory = open_provider,
) -> int:
    """Read pipes on the main thread; one owned asyncio thread handles cancellable runs."""
    validate_session_id(session_id)
    codec = MessageCodec()
    reader = FrameReader(codec)

    def send(kind: str, request_id: str | None, payload: Message) -> None:
        sink.write(
            codec.encode(
                {
                    "protocolVersion": PROTOCOL_VERSION,
                    "type": kind,
                    "sessionId": session_id,
                    "requestId": request_id,
                    "payload": payload,
                }
            )
        )
        sink.flush()

    loop = asyncio.new_event_loop()
    runtime = WorkerRuntime(send, provider_factory)
    thread = Thread(target=loop.run_forever, name="agent-run-loop")
    thread.start()
    try:
        send("ready", None, {"workerVersion": release_version()})
        log.info("Worker ready: protocolVersion=%d", PROTOCOL_VERSION)
        while chunk := source.read1(65536):
            for message in reader.feed(chunk):
                codec.validator.validate_received(message, session_id, "java")
                if not asyncio.run_coroutine_threadsafe(runtime.accept(message), loop).result():
                    log.info("Worker shutdown accepted")
                    return 0
        reader.finish()
        log.info("Worker input closed")
        return 0
    finally:
        # Provider I/O is asynchronous and cancellation closes the request-owned client.
        # The Java supervisor remains the hard termination boundary if a library hangs.
        try:
            asyncio.run_coroutine_threadsafe(runtime.close(), loop).result()
        finally:
            loop.call_soon_threadsafe(loop.stop)
            thread.join()
            loop.close()


def main(provider_factory: ProviderFactory = open_provider) -> int:
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
            provider_factory,
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
