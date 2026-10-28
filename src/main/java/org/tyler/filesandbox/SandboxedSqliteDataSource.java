package org.tyler.filesandbox;

import org.springframework.jdbc.datasource.AbstractDataSource;
import org.sqlite.SQLiteDataSource;
import org.tyler.exceptionHandler.exception.FileReadException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * Rechecks the database and SQLite sidecar paths before each connection.
 * Only plain filesystem paths are accepted: driver URL parsing must not change
 * the filename validated by the sandbox.
 * SQLite still opens native paths: these checks are not a race-free file handle
 * handoff, and cannot protect a connection from subsequent filesystem changes.
 */
public final class SandboxedSqliteDataSource extends AbstractDataSource {
    private static final List<String> SIDECARS = List.of("-journal", "-wal", "-shm");
    private static final String INVALID_PATH =
            "Database path must be a plain filesystem path without SQLite URL syntax or trailing whitespace";

    private final IFileSandboxPath sandbox;
    private final Path databasePath;
    private final SQLiteDataSource delegate;

    public SandboxedSqliteDataSource(IFileSandboxPath sandbox, String databaseFile) {
        requirePlainPath(databaseFile);
        this.sandbox = sandbox;
        databasePath = sandbox.resolve(databaseFile);
        // The configured root can itself contain '?' on some filesystems.
        requirePlainPath(databasePath.toString());
        validatePaths();
        delegate = new SQLiteDataSource();
        delegate.setUrl("jdbc:sqlite:" + databasePath);
    }

    private static void requirePlainPath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(INVALID_PATH);
        }
        String lower = value.toLowerCase(Locale.ROOT);
        // SQLite JDBC extracts query pragmas and trims the completed URL. Reject
        // these inputs rather than checking one filename and opening another.
        if (value.indexOf('?') >= 0 || value.charAt(value.length() - 1) <= ' '
                || lower.startsWith("jdbc:") || lower.startsWith("file:")
                || lower.startsWith(":resource:") || lower.equals(":memory:")) {
            throw new IllegalArgumentException(INVALID_PATH);
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        checkConnectionPaths();
        return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        checkConnectionPaths();
        return delegate.getConnection(username, password);
    }

    private void checkConnectionPaths() throws SQLException {
        try {
            validatePaths();
        } catch (IllegalArgumentException | FileReadException e) {
            throw new SQLException("Database workspace path is unavailable or unsafe", e);
        }
    }

    private void validatePaths() {
        // The sandbox root is fixed, so the resolved path cannot change; only the entries
        // on it can, and those are what this revalidates.
        sandbox.resolveWithSiblings(databasePath.toString(), SIDECARS);
    }
}
