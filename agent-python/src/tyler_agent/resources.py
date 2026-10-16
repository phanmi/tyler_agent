"""Locate shared contracts and the single release-version source in development or a bundle."""

import re
import sys
from pathlib import Path


def resource_root() -> Path:
    """PyInstaller carries the same repository resources beside its bundled runtime."""
    bundled = getattr(sys, "_MEIPASS", None)
    return Path(bundled) if bundled else Path(__file__).resolve().parents[3]


def release_version() -> str:
    content = (resource_root() / ".mvn/maven.config").read_text(encoding="utf-8")
    match = re.search(r"(?m)^-Drevision=([^\s]+)$", content)
    if not match:
        raise ValueError("Missing shared release version")
    return match[1]
