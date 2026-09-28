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
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies date-scoped reads and exception conversion against temporary SQLite storage. */
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
        SQLReadException failure = assertThrows(SQLReadException.class,
                () -> dao.selectByDate(LocalDate.of(2026, 9, 26)));
        assertInstanceOf(DataAccessException.class, failure.getCause());
        assertTrue(failure.getMessage().contains("2026-09-26"));
    }

    @Test
    void selectByDatePreservesMatchingRecordsIdsAndOrder() throws IOException {
        WorkoutDAOSqlite dao = newDao();
        LocalDate date = LocalDate.of(2026, 9, 26);
        Workout first = new Workout("Squat", "4X12", new BigDecimal("20.50"), date.toString());
        Workout other = new Workout("Run", "1X1", BigDecimal.ZERO, "2026-09-27");
        Workout last = new Workout("Press", "3X8", new BigDecimal("12.25"), date.toString());
        long firstId = dao.insert(first);
        long otherId = dao.insert(other);
        long lastId = dao.insert(last);

        var records = dao.selectByDate(date);
        assertEquals(List.of(firstId, lastId), List.copyOf(records.keySet()));
        assertEquals(List.of(first, last), List.copyOf(records.values()));
        assertEquals(List.of(otherId), List.copyOf(dao.selectByDate(date.plusDays(1)).keySet()));
        assertTrue(dao.selectByDate(date.minusDays(1)).isEmpty());
    }

    @Test
    void selectByDateRejectsNullBeforeDatabaseAccess() throws IOException {
        WorkoutDAOSqlite dao = newDao();
        makeDatabaseUnavailable();
        assertEquals("date must not be null",
                assertThrows(IllegalArgumentException.class, () -> dao.selectByDate(null)).getMessage());
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
