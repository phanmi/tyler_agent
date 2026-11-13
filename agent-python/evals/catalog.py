"""The models Settings offers, read from the backend's ``llm-models.properties`` (#103).

The backend loads each provider's default, suggested and low-cost models from that file, and
the harness reads the same file, so the models it runs are always the ones Settings offers.
The backend checks every entry when it starts; the harness reads only the entries it uses.
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


def listed_targets(field: str) -> list[Target]:
    """Each provider's comma-separated ``llm.models.<provider>.<field>`` models, provider by
    provider. A provider the harness has no key variable for is left out."""
    entries = read_properties(MODELS_PATH)
    targets: list[Target] = []
    for provider in KEY_VARIABLES:
        key = f"{PROPERTY_PREFIX}{provider}.{field}"
        if not entries.get(key):
            raise InvalidDataFile(f"{MODELS_PATH.name}: {key} is missing")
        targets += [Target(provider, model.strip()) for model in entries[key].split(",")]
    return targets


def suggested_targets() -> list[Target]:
    """Every model Settings suggests, in the order Settings lists them."""
    return listed_targets("suggested")


def cheap_targets() -> list[Target]:
    """Each provider's low-cost suggestion, which --cheap-models runs."""
    return listed_targets("cheap")
