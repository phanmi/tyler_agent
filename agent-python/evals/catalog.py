"""The models Settings offers, read from the backend's ``llm-models.properties`` (#103).

The backend loads each provider's default, suggested and low-cost models from that file, and
the harness reads the same file, so the models it runs are always the ones Settings offers.
The backend checks every entry when it starts. The harness reads only the entries it uses, and
repeats one backend rule: a cheap model must be one of its provider's suggestions.
"""

from pathlib import Path

from evals.files import InvalidDataFile
from evals.keys import KEY_VARIABLES
from evals.runner import Target

# The backend's model file. The harness runs only from a checkout, so it reads it in place.
MODELS_PATH = Path(__file__).parents[2] / "src" / "main" / "resources" / "llm-models.properties"
PROPERTY_PREFIX = "llm.models."


def read_properties(path: Path) -> dict[str, str]:
    """The file's ``key=value`` lines. Blank lines and ``#`` comments are skipped."""
    try:
        text = path.read_text("utf-8")
    except OSError as error:
        raise InvalidDataFile(f"Cannot open {path.name}: {error.strerror}") from error
    entries: dict[str, str] = {}
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            key, _, value = line.partition("=")
            entries[key.strip()] = value.strip()
    return entries


def _property(provider: str, field: str) -> str:
    return f"{PROPERTY_PREFIX}{provider}.{field}"


def _entry(entries: dict[str, str], provider: str, field: str) -> str:
    key = _property(provider, field)
    if not entries.get(key):
        raise InvalidDataFile(f"{MODELS_PATH.name}: {key} is missing")
    return entries[key]


def _suggested(entries: dict[str, str], provider: str) -> list[str]:
    return [model.strip() for model in _entry(entries, provider, "suggested").split(",")]


def suggested_targets() -> list[Target]:
    """Every model Settings suggests, provider by provider, in the order Settings lists them.
    A provider the harness has no key variable for is left out."""
    entries = read_properties(MODELS_PATH)
    return [
        Target(provider, model)
        for provider in KEY_VARIABLES
        for model in _suggested(entries, provider)
    ]


def cheap_targets() -> list[Target]:
    """Each provider's low-cost suggestion, which --cheap-models runs.

    The backend refuses a ``cheap`` entry that is not one of the provider's suggestions when it
    starts. A run can start before the backend has, so the same rule is checked here: any other
    entry raises InvalidDataFile instead of running a model that Settings does not offer."""
    entries = read_properties(MODELS_PATH)
    targets: list[Target] = []
    for provider in KEY_VARIABLES:
        cheap = _entry(entries, provider, "cheap")
        if cheap not in _suggested(entries, provider):
            raise InvalidDataFile(
                f"{MODELS_PATH.name}: {_property(provider, 'cheap')} must be one of "
                f"{_property(provider, 'suggested')}"
            )
        targets.append(Target(provider, cheap))
    return targets
