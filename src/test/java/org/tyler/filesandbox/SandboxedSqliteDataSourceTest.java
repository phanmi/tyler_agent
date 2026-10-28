package org.tyler.filesandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.tyler.config.WorkoutDatabaseConfig;
import org.tyler.dao.foodrecord.FoodRecordDAOSqlite;
import org.tyler.exceptionHandler.exception.WorkspacePathException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SandboxedSqliteDataSourceTest {
    private static final String INVALID_PATH =
            "Database path must be a plain filesystem path without SQLite URL syntax or trailing whitespace";

    @TempDir
    Path tempDir;

    @Test
    void opensOrdinaryDatabaseThroughBothConnectionMethods() throws Exception {
        var source = new SandboxedSqliteDataSource(new FileSandbox(tempDir.toString()), "data.sqlite");
        try (var connection = source.getConnection()) {
            assertTrue(connection.isValid(1));
        }
        try (var connection = source.getConnection(null, null)) {
            assertTrue(connection.isValid(1));
        }
        assertTrue(Files.isRegularFile(tempDir.resolve("data.sqlite")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "data.sqlite?busy_timeout=5000", "data.sqlite?journal_mode=WAL",
            "data.sqlite?unknown=value", "data.sqlite?", "file:data.sqlite", "FILE:data.sqlite",
            "jdbc:sqlite:data.sqlite", ":resource:data.sqlite", ":memory:",
            "data.sqlite ", "data.sqlite\t", "data.sqlite\n"})
    void rejectsDriverSyntaxBeforeResolvingAnyPath(String filename) {
        IFileSandboxPath sandbox = mock(IFileSandboxPath.class);
        IllegalArgumentException error = assertThrowsExactly(IllegalArgumentException.class,
                () -> new SandboxedSqliteDataSource(sandbox, filename));
        assertEquals(INVALID_PATH, error.getMessage());
        verifyNoInteractions(sandbox);
    }

    @Test
    void consumersRejectRecognizedPragmaWithoutTouchingLinkedDatabase() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        var sandbox = new FileSandbox(workspace.toString());
        Path external = Files.writeString(tempDir.resolve("external"), "keep");
        FileSandboxLinkTest.symbolicLink(workspace.resolve("data.sqlite"), external);
        String filename = "data.sqlite?busy_timeout=5000";

        assertEquals(INVALID_PATH, assertThrowsExactly(IllegalArgumentException.class,
                () -> new SandboxedSqliteDataSource(sandbox, filename)).getMessage());
        assertEquals(INVALID_PATH, assertThrowsExactly(IllegalArgumentException.class,
                () -> new FoodRecordDAOSqlite(sandbox, filename)).getMessage());
        assertEquals(INVALID_PATH, assertThrowsExactly(IllegalArgumentException.class,
                () -> new WorkoutDatabaseConfig().workoutDataSource(sandbox, filename)).getMessage());
        assertEquals("keep", Files.readString(external));
        try (var entries = Files.list(workspace)) {
            assertEquals(java.util.List.of("data.sqlite"),
                    entries.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void rejectsQuerySyntaxInResolvedWorkspaceRoot() throws Exception {
        var sandbox = new FileSandbox(tempDir.resolve("workspace?busy_timeout=5000").toString());
        assertEquals(INVALID_PATH, assertThrowsExactly(IllegalArgumentException.class,
                () -> new SandboxedSqliteDataSource(sandbox, "data.sqlite")).getMessage());
        try (var entries = Files.list(sandbox.resolve("."))) {
            assertEquals(0, entries.count());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"data.sqlite", "data space.sqlite", "data#%23.sqlite"})
    void ordinaryAbsolutePathsOpenTheExactValidatedFile(String filename) throws Exception {
        var sandbox = new FileSandbox(tempDir.toString());
        Path expected = sandbox.resolve(filename);
        var source = new SandboxedSqliteDataSource(sandbox, expected.toString());
        try (var connection = source.getConnection()) {
            assertTrue(connection.isValid(1));
        }
        try (var entries = Files.list(tempDir)) {
            assertEquals(java.util.List.of(expected), entries.toList());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-journal", "-wal", "-shm"})
    void rejectsLinksAddedAfterDataSourceCreation(String suffix) throws Exception {
        var sandbox = new FileSandbox(tempDir.resolve("workspace").toString());
        var source = new SandboxedSqliteDataSource(sandbox, "data.sqlite");
        Path external = Files.writeString(tempDir.resolve("external"), "keep");
        FileSandboxLinkTest.symbolicLink(tempDir.resolve("workspace/data.sqlite" + suffix), external);
        SQLException error = assertThrowsExactly(SQLException.class, source::getConnection);
        assertEquals("Database workspace path is unavailable or unsafe", error.getMessage());
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
        assertThrowsExactly(SQLException.class, () -> source.getConnection(null, null));
        assertEquals("keep", Files.readString(external));
    }

    @Test
    void bothDatabaseConsumersRejectLinkedDatabaseFiles() throws Exception {
        var sandbox = new FileSandbox(tempDir.resolve("workspace").toString());
        Path external = Files.writeString(tempDir.resolve("external"), "keep");
        FileSandboxLinkTest.symbolicLink(tempDir.resolve("workspace/data.sqlite"), external);
        assertThrowsExactly(WorkspacePathException.class,
                () -> new FoodRecordDAOSqlite(sandbox, "data.sqlite"));
        assertThrowsExactly(WorkspacePathException.class,
                () -> new WorkoutDatabaseConfig().workoutDataSource(sandbox, "data.sqlite"));
        assertEquals("keep", Files.readString(external));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void databaseConsumersRejectJunctionAncestorsBeforeCreatingDatabase() throws Exception {
        var sandbox = new FileSandbox(tempDir.resolve("workspace").toString());
        Path external = Files.createDirectory(tempDir.resolve("external"));
        Path junction = tempDir.resolve("workspace/linked");
        FileSandboxLinkTest.junction(junction, external);
        try {
            assertThrowsExactly(WorkspacePathException.class,
                    () -> new FoodRecordDAOSqlite(sandbox, "linked/data.sqlite"));
            assertThrowsExactly(WorkspacePathException.class,
                    () -> new WorkoutDatabaseConfig().workoutDataSource(sandbox, "linked/data.sqlite"));
            assertFalse(Files.exists(external.resolve("data.sqlite")));
        } finally {
            Files.delete(junction);
        }
    }
}
