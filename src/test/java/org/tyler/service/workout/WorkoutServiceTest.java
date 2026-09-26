package org.tyler.service.workout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.dal.workout.WorkoutDAL;
import org.tyler.dao.workout.WorkoutDAOSqlite;
import org.tyler.exceptionHandler.exception.SQLDataValidationException;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.model.workout.Workout;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 SQLite 覆盖 service → DAL → DAO 的增删改查。 */
class WorkoutServiceTest {

    @TempDir
    Path tempDir;

    private WorkoutService newService() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        return new WorkoutService(new WorkoutDAL(new WorkoutDAOSqlite(sandbox, "workout-record.sqlite")));
    }

    private static Workout workout(String name, String rep, String weight, String date) {
        return new Workout(name, rep, new BigDecimal(weight), date);
    }

    @Test
    void insertSelectAndRestartPreserveAllFieldsAndIds() throws IOException {
        WorkoutService service = newService();
        Workout first = workout("Bench press", "4X12", "82.50", "2026-09-26");
        Workout second = workout("Squat", "3X8", "120", "2026-09-27");

        long firstId = service.saveWorkout(first);
        long secondId = service.saveWorkout(second);

        assertTrue(firstId > 0);
        assertTrue(secondId > firstId);
        assertEquals(first, service.getWorkoutById(firstId).orElseThrow());
        assertEquals(Map.of(firstId, first, secondId, second), service.getAllWorkouts());
        assertEquals(first, newService().getWorkoutById(firstId).orElseThrow());
    }

    @Test
    void updateChangesOnlySelectedRecordAndReportsMissingId() throws IOException {
        WorkoutService service = newService();
        Workout original = workout("Bench press", "4X12", "80", "2026-09-26");
        long firstId = service.saveWorkout(original);
        long secondId = service.saveWorkout(original);
        Workout changed = workout("Incline press", "5X5", "90.25", "2026-09-28");

        assertTrue(service.updateWorkout(firstId, changed));
        assertEquals(changed, service.getWorkoutById(firstId).orElseThrow());
        assertEquals(original, service.getWorkoutById(secondId).orElseThrow());
        assertFalse(service.updateWorkout(9999, changed));
        assertEquals(2, service.getAllWorkouts().size());
    }

    @Test
    void deleteRemovesOnlySelectedRecordAndReportsMissingId() throws IOException {
        WorkoutService service = newService();
        Workout duplicate = workout("Deadlift", "4X6", "100", "2026-09-26");
        long firstId = service.saveWorkout(duplicate);
        long secondId = service.saveWorkout(duplicate);

        assertTrue(service.deleteWorkout(firstId));
        assertTrue(service.getWorkoutById(firstId).isEmpty());
        assertEquals(duplicate, service.getWorkoutById(secondId).orElseThrow());
        assertFalse(service.deleteWorkout(firstId));
        assertFalse(service.deleteWorkout(9999));
    }

    @Test
    void emptySelectAndInvalidInputs() throws IOException {
        WorkoutService service = newService();
        assertTrue(service.getAllWorkouts().isEmpty());
        assertTrue(service.getWorkoutById(9999).isEmpty());

        assertThrows(SQLDataValidationException.class, () -> service.saveWorkout(null));
        assertThrows(SQLDataValidationException.class,
                () -> service.saveWorkout(workout(" ", "4X12", "20", "2026-09-26")));
        assertThrows(SQLDataValidationException.class,
                () -> service.saveWorkout(new Workout("Squat", "4X12", null, "2026-09-26")));
        assertThrows(SQLDataValidationException.class,
                () -> service.saveWorkout(workout("Squat", "4X12", "-1", "2026-09-26")));
        assertThrows(SQLDataValidationException.class,
                () -> service.saveWorkout(workout("Squat", "4X12", "20", "2026-02-30")));
        assertThrows(IllegalArgumentException.class,
                () -> workout("Squat", "4x12", "20", "2026-09-26"));
        assertThrows(IllegalArgumentException.class, () -> service.getWorkoutById(0));
        assertThrows(IllegalArgumentException.class, () -> service.updateWorkout(-1,
                workout("Squat", "4X12", "20", "2026-09-26")));
        assertThrows(IllegalArgumentException.class, () -> service.deleteWorkout(0));
    }
}
