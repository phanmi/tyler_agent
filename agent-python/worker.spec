# PyInstaller bundles the runtime and shared resources; it does not bundle credentials.
from pathlib import Path

from PyInstaller.utils.hooks import collect_data_files, collect_submodules

root = Path(SPECPATH).parent
analysis = Analysis(
    [str(root / "agent-python/src/tyler_agent/__main__.py")],
    pathex=[str(root / "agent-python/src")],
    datas=[
        (str(root / "contracts/agent/v1/message.schema.json"), "contracts/agent/v1"),
        (str(root / ".mvn/maven.config"), ".mvn"),
    ] + collect_data_files("jsonschema_specifications") + collect_data_files("tyler_agent"),
    hiddenimports=collect_submodules("tyler_agent.tools.builtin"),
)
archive = PYZ(analysis.pure)
executable = EXE(archive, analysis.scripts, [], exclude_binaries=True,
                 name="tyler-agent-worker", console=True)
bundle = COLLECT(executable, analysis.binaries, analysis.datas, name="tyler-agent-worker")
