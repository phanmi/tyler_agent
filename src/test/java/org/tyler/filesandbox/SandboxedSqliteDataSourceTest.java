package org.tyler.filesandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tyler.config.WorkoutDatabaseConfig;
import org.tyler.dao.foodrecord.FoodRecordDAOSqlite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class SandboxedSqliteDataSourceTest {
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
        assertThrowsExactly(IllegalArgumentException.class,
                () -> new FoodRecordDAOSqlite(sandbox, "data.sqlite"));
        assertThrowsExactly(IllegalArgumentException.class,
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
            assertThrowsExactly(IllegalArgumentException.class,
                    () -> new FoodRecordDAOSqlite(sandbox, "linked/data.sqlite"));
            assertThrowsExactly(IllegalArgumentException.class,
                    () -> new WorkoutDatabaseConfig().workoutDataSource(sandbox, "linked/data.sqlite"));
            assertFalse(Files.exists(external.resolve("data.sqlite")));
        } finally {
            Files.delete(junction);
        }
    }
}
