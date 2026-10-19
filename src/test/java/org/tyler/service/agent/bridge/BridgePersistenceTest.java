package org.tyler.service.agent.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.tyler.config.WorkoutDatabaseConfig;
import org.tyler.dao.foodrecord.FoodRecordDAOSqlite;
import org.tyler.dao.workout.WorkoutDAOSqlite;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.model.workout.Workout;
import org.tyler.service.food.FoodService;
import org.tyler.service.food.IFoodService;
import org.tyler.service.userInfo.UserInfoService;
import org.tyler.service.workout.IWorkoutPlanService;
import org.tyler.service.workout.IWorkoutService;
import org.tyler.service.workout.WorkoutService;
import org.tyler.service.workout.WorkoutPlanService;
import org.tyler.validation.FoodValidator;
import org.tyler.validation.WorkoutValidator;
import org.tyler.service.agent.python.PythonWorker;
import org.tyler.service.agent.python.WorkerTestSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;
import static org.tyler.service.agent.bridge.ServiceOperationDispatcherTest.input;

/** Real service wiring and temporary databases, with no provider and no application data. */
class BridgePersistenceTest {
    @TempDir Path workspace;

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(FileSandbox.class, WorkoutDatabaseConfig.class,
                FoodRecordDAOSqlite.class, FoodValidator.class, FoodService.class, UserInfoService.class,
                WorkoutDAOSqlite.class, WorkoutValidator.class, WorkoutService.class, WorkoutPlanService.class)
                .withPropertyValues("agent.workspace-dir=" + workspace);
    }

    private ServiceOperationDispatcher bridge(org.springframework.context.ApplicationContext context) {
        var sandbox = context.getBean(FileSandbox.class);
        return new ServiceOperationDispatcher(context.getBean(IFoodService.class), context.getBean(IWorkoutPlanService.class),
                context.getBean(UserInfoService.class), sandbox, sandbox, Clock.systemDefaultZone());
    }

    @Test
    void realWorkerCallbackAppendsFoodWithIndependentStableIds() {
        context().run(ctx -> {
            var dispatcher = bridge(ctx);
            var food = ctx.getBean(IFoodService.class);
            var first = dispatcher.dispatch("food.insert", input("food"));
            try (var worker = new PythonWorker(WorkerTestSupport.settings("write"), dispatcher)) {
                assertNotNull(worker.execute(WorkerTestSupport.payload()));
                worker.historySettled(true);
            }
            var records = food.getFoodRecordsByDate("2026-10-16");
            assertEquals(2, records.size());
            assertEquals(Long.parseLong(first.path("id").textValue()), records.getFirst().id());
            assertNotEquals(records.getFirst().id(), records.getLast().id());
            assertEquals(records.getFirst().food(), records.getLast().food());
            // Existing SQLite NUMERIC affinity may normalize scale; the transport preserves it.
            assertEquals(0, new BigDecimal("100.00").compareTo(records.getLast().food().genericInfo().amount()));
            assertTrue(food.deleteFoodById(records.getFirst().id()));
            assertEquals(records.getLast(), food.getFoodRecordsByDate("2026-10-16").getFirst());
        });
    }

    @Test
    void repeatedPlansPreserveEditedRecordsAndRollbackActualInsertFailure() {
        context().run(ctx -> {
            var dispatcher = bridge(ctx);
            var storage = ctx.getBean(IWorkoutService.class);
            var request = input("plan").put("startDate", "2026-09-28").put("goal", "general_fitness")
                    .put("equipment", "bodyweight");
            var edited = new Workout("Push-up", "4X8", new BigDecimal("15.500"), "2026-09-28");
            long id = storage.saveWorkout(edited);
            var before = storage.getAllWorkouts();
            var jdbc = ctx.getBean("workoutJdbcTemplate", JdbcTemplate.class);
            jdbc.execute("""
                    CREATE TRIGGER fail_bridge_plan BEFORE INSERT ON workout_record
                    WHEN (SELECT COUNT(*) FROM workout_record) >= 3
                    BEGIN SELECT RAISE(ABORT, 'forced bridge insert failure'); END
                    """);
            assertNotNull(assertThrowsExactly(SQLPersistentException.class,
                    () -> dispatcher.dispatch("workout_plan.generate_save", request)).getCause());
            assertEquals(before, storage.getAllWorkouts());
            jdbc.execute("DROP TRIGGER fail_bridge_plan");
            var saved = dispatcher.dispatch("workout_plan.generate_save", request);
            var snapshot = storage.getAllWorkouts();
            assertEquals(12, snapshot.size());
            assertEquals(saved, dispatcher.dispatch("workout_plan.generate_save", request));
            assertEquals(snapshot, storage.getAllWorkouts());
            assertEquals(edited, storage.getWorkoutById(id).orElseThrow());
            assertEquals("15.500", saved.path("plan").path("days").get(0).path("workouts").get(1).path("weight").textValue());
        });
    }

    @Test
    void sandboxFilesKeepUnicodeAndRejectEscapingPaths() {
        context().run(ctx -> {
            var dispatcher = bridge(ctx);
            var write = input("file_write");
            assertEquals("notes/session.txt", dispatcher.dispatch("file.write", write).path("path").textValue());
            assertEquals(write.path("content"), dispatcher.dispatch("file.read", input("file_read")).path("content"));
            String outside = workspace.getParent().resolve("outside-bridge.txt").toString();
            for (String path : new String[]{"../outside-bridge.txt", outside, " "}) {
                assertThrowsExactly(IllegalArgumentException.class,
                        () -> dispatcher.dispatch("file.write", input("file_write").put("path", path)));
                assertThrowsExactly(IllegalArgumentException.class,
                        () -> dispatcher.dispatch("file.read", input("file_read").put("path", path)));
            }
            assertFalse(Files.exists(Path.of(outside)));
            assertThrowsExactly(FileReadException.class,
                    () -> dispatcher.dispatch("file.read", input("file_read").put("path", "missing.txt")));
            assertTrue(dispatcher.dispatch("user_info.read", input("profile")).path("profile").isObject());
        });
    }
}
