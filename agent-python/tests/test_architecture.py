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
