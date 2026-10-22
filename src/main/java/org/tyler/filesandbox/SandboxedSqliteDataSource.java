package org.tyler.filesandbox;

import org.springframework.jdbc.datasource.AbstractDataSource;
import org.sqlite.SQLiteDataSource;
import org.tyler.exceptionHandler.exception.FileReadException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Rechecks the database and SQLite sidecar paths before each connection.
 * SQLite still opens native paths: these checks are not a race-free file handle
 * handoff, and cannot protect a connection from subsequent filesystem changes.
 */
public final class SandboxedSqliteDataSource extends AbstractDataSource {
    private static final List<String> SIDECARS = List.of("-journal", "-wal", "-shm");

    private final IFileSandboxPath sandbox;
    private final String databaseFile;
    private final Path databasePath;
    private final SQLiteDataSource delegate;

    public SandboxedSqliteDataSource(IFileSandboxPath sandbox, String databaseFile) {
        this.sandbox = sandbox;
        this.databaseFile = databaseFile;
        databasePath = sandbox.resolve(databaseFile);
        validatePaths();
        delegate = new SQLiteDataSource();
        delegate.setUrl("jdbc:sqlite:" + databasePath);
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
        if (!databasePath.equals(sandbox.resolve(databaseFile))) {
            throw new IllegalArgumentException("Database workspace path has changed");
        }
        for (String suffix : SIDECARS) {
            sandbox.resolve(databasePath + suffix);
        }
    }
}
