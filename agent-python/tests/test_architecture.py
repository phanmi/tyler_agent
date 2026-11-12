"""Keep application database access behind the Java service bridge."""

import ast
from pathlib import Path

import tyler_agent


def test_agent_does_not_import_database_drivers() -> None:
    root = Path(tyler_agent.__file__).parent
    forbidden = {"sqlite3", "sqlalchemy", "aiosqlite", "psycopg", "pymysql"}
    violations: list[str] = []
    for source in sorted(root.rglob("*.py")):
        for node in ast.walk(ast.parse(source.read_text(encoding="utf-8"))):
            modules: list[str] = []
            if isinstance(node, ast.Import):
                modules = [alias.name for alias in node.names]
                line = node.lineno
            elif isinstance(node, ast.ImportFrom) and node.module:
                modules = [node.module]
                line = node.lineno
            else:
                continue
            for module in modules:
                if module.split(".")[0] in forbidden:
                    violations.append(f"{source.relative_to(root)}:{line}: {module}")
    assert not violations, f"Use Java service operations for persistence: {violations}"


def test_the_worker_does_not_depend_on_the_eval_harness() -> None:
    """The harness makes paid calls and is not in the wheel or the bundle, so no worker module
    may import it, and the bundle must be built from the worker package only."""
    root = Path(tyler_agent.__file__).parent
    importers: list[str] = []
    for source in sorted(root.rglob("*.py")):
        for node in ast.walk(ast.parse(source.read_text(encoding="utf-8"))):
            modules: list[str] = []
            if isinstance(node, ast.Import):
                modules = [alias.name for alias in node.names]
            elif isinstance(node, ast.ImportFrom) and node.module:
                modules = [node.module]
            if any(module.split(".")[0] == "evals" for module in modules):
                importers.append(str(source.relative_to(root)))
    project = root.parents[1]

    assert not importers, f"Worker modules must not import the eval harness: {importers}"
    assert "evals" not in (project / "worker.spec").read_text("utf-8")
    assert 'packages = ["src/tyler_agent"]' in (project / "pyproject.toml").read_text("utf-8")
