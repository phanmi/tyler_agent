# Workspace file sandbox

[Developer guide](DEVELOPMENT.md) · [Backend standards](BACKEND_STANDARDS.md)

## Root and link policy

`FileSandbox` creates the configured workspace if necessary and anchors it to
`toRealPath()` at construction. The default remains
`{user.home}/AppData/Local/tyler_agent`; `agent.workspace-dir` / `AGENT_WORKSPACE_DIR`
still override it. Relative configuration is resolved against the backend launch
directory. A configured root alias (symlink or Windows junction) is accepted and
resolved once. Retargeting that alias does not redirect an existing sandbox.

Below the real root, **symbolic links, junctions and other special/reparse entries
are rejected**, including links pointing inside the workspace and dangling links.
Every existing component is inspected without following links; real-path comparison
also rejects redirection. Canonical root ancestors are checked on each operation.
A changed root identity is rejected where the provider supplies a file key.
Windows NIO reports non-symlink reparse points as `isOther()`; these are rejected
conservatively, so cloud placeholders and other special files are not supported
as workspace entries. This policy does not detect hard-link aliases or establish
a capability boundary around private files already inside the root.

## Operation contracts

| Operation | Contract |
| --- | --- |
| `resolve` | Returns an absolute path under the real root. Validates existing ancestors even for missing targets. Does not create files or directories. |
| `exists` | Returns false for an absent target or ancestor. Rejects linked/special components; inspection failures are file-read failures rather than apparent absence. |
| `read` | Reads UTF-8 text; missing files raise `FileReadException`. |
| `write` | Replaces UTF-8 text and creates missing parents one component at a time after checks. Validates before opening and again before truncation. |

Paths are normalized before use: `notes/../file.txt` means `file.txt` and does not
traverse `notes`. Absolute paths inside the configured or canonical root remain
accepted. Blank, malformed, out-of-bounds and linked paths raise
`IllegalArgumentException`. I/O failures retain their causes in `FileReadException`
or `FileWriteException` with public messages containing no supplied or absolute
path. Success logs report operation and character count without paths or contents.

## SQLite consumers

Food and workout persistence use `SandboxedSqliteDataSource`. It validates the
database and its `-journal`, `-wal` and `-shm` paths at construction and before
each connection, including connections requested with credentials. Connection-time
rejection becomes `SQLException` with a safe message and retained cause, preserving
existing DAO read/write exception translation. Workout JDBC and transactions still
share the same data source. No schema or data migration is involved. Linked
database paths are intentionally no longer accepted.

Database settings accept plain filesystem paths only. Before sandbox resolution,
the data source rejects `?` query syntax (including recognized pragmas such as
`busy_timeout`), JDBC/file/resource URLs, memory selectors, and trailing ASCII
whitespace/control characters that the JDBC URL parser trims. It also checks the
resolved absolute path, because a configured root can contain `?` on POSIX.
This prevents SQLite JDBC from opening a different filename from the one validated,
including different journal/WAL/shared-memory sidecars. Do not put connection
options in `food.database-path` or `workout.database-path`. Ordinary relative and
absolute paths, spaces within filenames, and literal `#`/`%` remain supported.
Invalid settings raise a path-free `IllegalArgumentException` before opening.

## Concurrent filesystem changes and platform limits

File channels request `NOFOLLOW_LINKS`. Where supported, `SecureDirectoryStream`
opens existing parents relative to directory handles with no-follow traversal and
checks the opened root identity. Substituting an ancestor symlink after path
validation cannot redirect that channel open. This does not prevent renaming an
already opened directory.

Windows NIO has no secure directory streams. The fallback checks absolute paths
and opens the leaf with no-follow. Ancestors are checked before opening and again
before reading/truncating, but **checks and opens are not atomic**. Directory
creation uses path-based NIO on all platforms. A hostile writer can still race
ancestor replacement, including creating an external file before the post-open
check rejects it.

`resolve` returns a snapshot, not an open handle. SQLite opens native paths and
sidecars independently; pre-connection checks cannot prevent races or link changes
while a connection is open. This sandbox rejects pre-existing links but is **not
isolation from hostile concurrent filesystem writers**. Workspace and ancestor
permissions must exclude untrusted local writers. Closing that gap requires a
native handle-based access strategy (including SQLite VFS support) or OS process
isolation, not another lexical or real-path check.

References: [Java secure directory streams](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/SecureDirectoryStream.html),
[OpenJDK Windows reparse-point classification](https://bugs.java.com/bugdatabase/view_bug?bug_id=JDK-8383867),
[Microsoft reparse points](https://learn.microsoft.com/en-us/windows/win32/fileio/reparse-points).

## Verification

Temporary-directory tests cover normalized paths, nested creation, missing files,
traversal, UTF-8 replacement, safe errors and retained causes, external/internal/
dangling links, root aliases/replacement, SQLite consumers and sidecars, and link
substitution after validation. Symlink tests skip only for unsupported links or
missing privileges; other failures fail the suite. Junction tests must pass on
Windows. Secure-directory tests explicitly skip on unsupported providers.

```shell
mvn -Dtest=FileSandboxTest,FileSandboxLinkTest,SandboxFileChannelsTest,SandboxedSqliteDataSourceTest,FoodRecordDAOSqliteTest,WorkoutDAOSqliteTest test
mvn test
```

### Local verification — 2026-10-22

- Windows, OpenJDK 26.0.2.1: focused command above **passed**, 45 tests,
  zero failures/errors, two expected secure-directory skips.
- Full `mvn test`: **passed**, 678 tests, zero failures/errors, the same two skips.
- Real Windows file/directory symlinks, junction rejection, a configured junction
  root, and no-follow leaf substitution tests all executed successfully.
- Windows provides no `SecureDirectoryStream`, so its ancestor-substitution and
  root-handle tests were skipped explicitly. The Linux-capable branch remains
  covered by those tests for the existing Ubuntu CI job; it was not run locally.
- The first restricted execution could not create links. Verification was rerun
  outside that execution sandbox; no permission failure was treated as a pass.
- Local Maven used the cached dependency repository, forked JDK 26 compilation,
  and the project's Mockito agent. Output is retained in the ignored
  `agent-python/sandbox-focused.log` and `agent-python/sandbox-full.log` files.

### SQLite filename parsing regression — 2026-10-22

- Added rejection before resolution for query pragmas/URL syntax and JDBC-trimmed
  trailing whitespace, plus validation of the resolved absolute filename.
- Regression tests use `data.sqlite?busy_timeout=5000` with real external links
  at `data.sqlite`, `data.sqlite-journal`, `data.sqlite-wal`, and `data.sqlite-shm`.
  Both database consumers reject the setting without modifying external targets
  or creating additional workspace files.
- Focused `mvn -Dtest=SandboxedSqliteDataSourceTest,FoodRecordDAOSqliteTest,WorkoutDAOSqliteTest test`:
  **passed**, 48 tests, zero failures/errors, one POSIX-only skip on Windows.
- Full `mvn test`: **passed**, 701 tests, zero failures/errors, three expected skips
  (the POSIX-only root-name case and two secure-directory tests).
- Windows symlink regressions executed without skips. The POSIX root-name test
  remains for Ubuntu CI; it was not run locally. Logs:
  `agent-python/sqlite-path-focused.log` and `agent-python/sqlite-path-full.log`.
