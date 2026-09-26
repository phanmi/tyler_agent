package org.tyler.dao.workout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.exceptionHandler.exception.SQLReadException;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.model.workout.Workout;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Uses an unavailable temporary SQLite path to verify DAO exception conversion. */
class WorkoutDAOSqliteTest {

    private static final String DB_FILE = "workout-record.sqlite";

    @TempDir
    Path tempDir;

    private WorkoutDAOSqlite newDao() throws IOException {
        return new WorkoutDAOSqlite(new FileSandbox(tempDir.toString()), DB_FILE);
    }

    private void makeDatabaseUnavailable() throws IOException {
        Path dbPath = tempDir.resolve(DB_FILE);
        Files.delete(dbPath);
        Files.createDirectory(dbPath);
    }

    @Test
    void readFailuresThrowSqlReadException() throws IOException {
        WorkoutDAOSqlite dao = newDao();
        makeDatabaseUnavailable();

        assertInstanceOf(DataAccessException.class,
                assertThrows(SQLReadException.class, () -> dao.selectById(1)).getCause());
        assertInstanceOf(DataAccessException.class,
                assertThrows(SQLReadException.class, dao::selectAll).getCause());
    }

    @Test
    void writeFailuresThrowSqlPersistentException() throws IOException {
        WorkoutDAOSqlite dao = newDao();
        makeDatabaseUnavailable();
        Workout sample = new Workout("Squat", "4X12", new BigDecimal("100"), "2026-09-26");

        assertInstanceOf(DataAccessException.class,
                assertThrows(SQLPersistentException.class, () -> dao.insert(sample)).getCause());
        assertInstanceOf(DataAccessException.class,
                assertThrows(SQLPersistentException.class, () -> dao.update(1, sample)).getCause());
        assertInstanceOf(DataAccessException.class,
                assertThrows(SQLPersistentException.class, () -> dao.delete(1)).getCause());
    }

    @Test
    void schemaFailureThrowsSqlPersistentException() throws IOException {
        Files.createDirectory(tempDir.resolve(DB_FILE));
        FileSandbox sandbox = new FileSandbox(tempDir.toString());

        assertInstanceOf(DataAccessException.class,
                assertThrows(SQLPersistentException.class,
                        () -> new WorkoutDAOSqlite(sandbox, DB_FILE)).getCause());
    }
}
