package org.tyler.service.workout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;
import org.tyler.config.WorkoutDatabaseConfig;
import org.tyler.dao.workout.WorkoutDAOSqlite;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.model.workout.Workout;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses production Spring wiring and independent SQLite reads to verify commits and rollback. */
class WorkoutPlanServiceTest {
    @TempDir Path tempDir;

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(FileSandbox.class,
                WorkoutDatabaseConfig.class, WorkoutDAOSqlite.class, WorkoutService.class, WorkoutPlanService.class)
                .withPropertyValues("agent.workspace-dir=" + tempDir, "workout.database-path=plans.sqlite");
    }

    @Test
    void previewPreservesTemplatesAndNeverAccessesStorage() {
        IWorkoutService storage = mock(IWorkoutService.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        WorkoutPlanService service = new WorkoutPlanService(storage, transactions);
        for (String equipment : List.of("bodyweight", "gym")) {
            for (String goal : List.of("general_fitness", "strength", "muscle_gain")) {
                var plan = service.previewPlan("2026-09-28", goal, equipment);
                assertEquals(7, plan.days().size());
                assertEquals(12, plan.days().stream().mapToInt(day -> day.workouts().size()).sum());
                assertFalse(plan.weightGuidance().isBlank());
                String rep = goal.equals("strength") ? "4X6" : goal.equals("muscle_gain") ? "3X10" : "3X12";
                assertEquals(rep, plan.days().getFirst().workouts().getFirst().rep());
                assertEquals(equipment.equals("gym") ? "Goblet squat" : "Bodyweight squat",
                        plan.days().getFirst().workouts().getFirst().workoutName());
                for (int i = 0; i < 7; i++) {
                    var day = plan.days().get(i);
                    assertEquals(LocalDate.of(2026, 9, 28).plusDays(i).toString(), day.date());
                    for (Workout workout : day.workouts()) {
                        assertEquals(day.date(), workout.workoutDate());
                        assertEquals(BigDecimal.ZERO, workout.weight());
                    }
                }
                assertEquals("1X1", plan.days().get(1).workouts().getFirst().rep());
                assertEquals(25, plan.days().get(1).durationMinutes());
                assertEquals(20, plan.days().get(3).durationMinutes());
                assertEquals(30, plan.days().get(5).durationMinutes());
                assertEquals("Rest", plan.days().get(6).focus());
                assertTrue(plan.days().get(6).workouts().isEmpty());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> service.generateAndSave("2026-02-30", "strength", "gym"));
        assertThrows(IllegalArgumentException.class, () -> service.previewPlan("2026-09-28", "unknown", "gym"));
        assertThrows(IllegalArgumentException.class, () -> service.previewPlan("2026-09-28", "strength", "unknown"));
        verifyNoInteractions(storage, transactions);
    }

    @Test
    void savesAndSequentiallyReusesExistingRecordsWithoutOverwriting() {
        context().run(ctx -> {
            IWorkoutService storage = ctx.getBean(IWorkoutService.class);
            IWorkoutPlanService plans = ctx.getBean(IWorkoutPlanService.class);
            Workout edited = new Workout("Push-up", "4X8", new BigDecimal("15"), "2026-09-28");
            long id = storage.saveWorkout(edited);
            var first = plans.generateAndSave("2026-09-28", "general_fitness", "bodyweight");
            var snapshot = storage.getAllWorkouts();
            assertEquals(12, snapshot.size());
            assertEquals(edited, first.days().getFirst().workouts().get(1));
            assertEquals(first, plans.generateAndSave("2026-09-28", "general_fitness", "bodyweight"));
            assertEquals(snapshot, storage.getAllWorkouts());
            assertEquals(edited, storage.getWorkoutById(id).orElseThrow());
            JdbcTemplate independent = new JdbcTemplate(ctx.getBean("workoutDataSource", DataSource.class));
            assertEquals(12, independent.queryForObject("SELECT COUNT(*) FROM workout_record", Integer.class));
        });
    }

    @Test
    void actualDatabaseFailureAfterInsertsRollsBackAndPreservesExistingRecords() {
        context().run(ctx -> {
            DataSource source = ctx.getBean("workoutDataSource", DataSource.class);
            JdbcTemplate jdbc = ctx.getBean("workoutJdbcTemplate", JdbcTemplate.class);
            assertSame(source, jdbc.getDataSource());
            assertSame(source, ctx.getBean("workoutTransactionManager", DataSourceTransactionManager.class).getDataSource());
            IWorkoutService storage = ctx.getBean(IWorkoutService.class);
            IWorkoutPlanService plans = ctx.getBean(IWorkoutPlanService.class);
            storage.saveWorkout(new Workout("Push-up", "4X8", new BigDecimal("15"), "2026-09-28"));
            storage.saveWorkout(new Workout("Manual exercise", "2X5", BigDecimal.ONE, "2026-10-10"));
            var before = storage.getAllWorkouts();
            // Fails only after at least two new rows are visible in the transaction.
            jdbc.execute("""
                    CREATE TRIGGER fail_partial_plan BEFORE INSERT ON workout_record
                    WHEN (SELECT COUNT(*) FROM workout_record) >= 4
                    BEGIN SELECT RAISE(ABORT, 'forced partial plan failure'); END
                    """);
            // Prevent delete-based compensation from satisfying this rollback test.
            jdbc.execute("""
                    CREATE TRIGGER forbid_cleanup BEFORE DELETE ON workout_record
                    BEGIN SELECT RAISE(ABORT, 'manual cleanup forbidden'); END
                    """);
            SQLPersistentException failure = assertThrows(SQLPersistentException.class,
                    () -> plans.generateAndSave("2026-09-28", "general_fitness", "bodyweight"));
            assertInstanceOf(DataAccessException.class, failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
            assertTrue(failure.getCause().getMessage().contains("forced partial plan failure"));
            assertEquals(before, storage.getAllWorkouts());
            JdbcTemplate independent = new JdbcTemplate(source);
            assertEquals(before.size(), independent.queryForObject("SELECT COUNT(*) FROM workout_record", Integer.class));
            jdbc.execute("DROP TRIGGER fail_partial_plan");
            plans.generateAndSave("2026-09-28", "general_fitness", "bodyweight");
            assertEquals(13, storage.getAllWorkouts().size());
        });
    }
    @Test
    void existingDuplicateNamesKeepBothRowsAndReuseTheLastRecord() {
        context().run(ctx -> {
            IWorkoutService storage = ctx.getBean(IWorkoutService.class);
            IWorkoutPlanService plans = ctx.getBean(IWorkoutPlanService.class);
            Workout first = new Workout("Push-up", "2X5", BigDecimal.ONE, "2026-09-28");
            Workout last = new Workout("Push-up", "4X8", BigDecimal.TEN, "2026-09-28");
            long firstId = storage.saveWorkout(first);
            long lastId = storage.saveWorkout(last);
            var result = plans.generateAndSave("2026-09-28", "general_fitness", "bodyweight");
            assertEquals(last, result.days().getFirst().workouts().get(1));
            assertEquals(13, storage.getAllWorkouts().size());
            assertEquals(first, storage.getWorkoutById(firstId).orElseThrow());
            assertEquals(last, storage.getWorkoutById(lastId).orElseThrow());
        });
    }
}
