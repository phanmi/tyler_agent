package org.tyler.service.workout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.tyler.model.workout.Workout;
import org.tyler.model.workout.WorkoutPlan;
import org.tyler.model.workout.WorkoutPlanDay;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fixed-template planning with atomic persistence and sequential date/name reuse. */
@Service
public class WorkoutPlanService implements IWorkoutPlanService {
    private static final Logger log = LoggerFactory.getLogger(WorkoutPlanService.class);
    private final IWorkoutService workoutService;
    private final TransactionTemplate transactions;

    public WorkoutPlanService(IWorkoutService workoutService,
            @Qualifier("workoutTransactions") TransactionTemplate transactions) {
        this.workoutService = workoutService;
        this.transactions = transactions;
    }

    @Override
    public WorkoutPlan generateAndSave(String startDate, String goal, String equipment) {
        WorkoutPlan preview = previewPlan(startDate, goal, equipment);
        WorkoutPlan saved = transactions.execute(status -> savePlan(preview));
        log.info("Completed workout plan persistence starting {} with {} days", startDate, saved.days().size());
        return saved;
    }

    /** Builds a preview without database access; both adapters share these generation rules. */
    @Override
    public WorkoutPlan previewPlan(String startDate, String goal, String equipment) {
        return generate(parseStartDate(startDate), parseGoal(goal), parseEquipment(equipment));
    }

    private WorkoutPlan savePlan(WorkoutPlan plan) {
        Map<WorkoutKey, Workout> saved = new HashMap<>();
        for (Workout workout : workoutService.getAllWorkouts().values()) {
            saved.put(new WorkoutKey(workout.workoutDate(), workout.workoutName()), workout);
        }

        List<WorkoutPlanDay> days = new ArrayList<>(plan.days().size());
        // All inserts participate in the caller's transaction; failures need no compensating deletes.
        for (WorkoutPlanDay day : plan.days()) {
            List<Workout> workouts = new ArrayList<>(day.workouts().size());
            for (Workout workout : day.workouts()) {
                WorkoutKey key = new WorkoutKey(workout.workoutDate(), workout.workoutName());
                Workout existing = saved.get(key);
                if (existing == null) {
                    workoutService.saveWorkout(workout);
                    saved.put(key, workout);
                    workouts.add(workout);
                } else {
                    workouts.add(existing);
                }
            }
            days.add(new WorkoutPlanDay(day.date(), day.focus(), List.copyOf(workouts),
                    day.activity(), day.durationMinutes()));
        }
        return new WorkoutPlan(plan.startDate(), plan.goal(), plan.equipment(),
                plan.weightGuidance(), List.copyOf(days));
    }

    private record WorkoutKey(String date, String name) {
    }

    private static LocalDate parseStartDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("startDate must use the format YYYY-MM-DD", e);
        }
    }

    private static Goal parseGoal(String value) {
        return switch (value) {
            case "strength" -> Goal.STRENGTH;
            case "muscle_gain" -> Goal.MUSCLE_GAIN;
            case "general_fitness" -> Goal.GENERAL_FITNESS;
            default -> throw new IllegalArgumentException("Unsupported goal: " + value);
        };
    }

    private static Equipment parseEquipment(String value) {
        return switch (value) {
            case "bodyweight" -> Equipment.BODYWEIGHT;
            case "gym" -> Equipment.GYM;
            default -> throw new IllegalArgumentException("Unsupported equipment: " + value);
        };
    }

    private static WorkoutPlan generate(LocalDate startDate, Goal goal, Equipment equipment) {
        List<WorkoutPlanDay> days = new ArrayList<>(7);
        days.add(strengthDay(startDate, 0, "Full body A", goal, equipment));
        days.add(activityDay(startDate, 1, "Cardio", "Brisk walk or cycle", 25));
        days.add(strengthDay(startDate, 2, "Full body B", goal, equipment));
        days.add(activityDay(startDate, 3, "Recovery", "Gentle mobility or an easy walk", 20));
        days.add(strengthDay(startDate, 4, "Full body C", goal, equipment));
        days.add(activityDay(startDate, 5, "Cardio", "Brisk walk or cycle", 30));
        days.add(activityDay(startDate, 6, "Rest", "Rest", 0));
        return new WorkoutPlan(startDate.toString(), goal.value, equipment.value,
                "Weight 0 is a placeholder; choose a suitable load for each exercise.", List.copyOf(days));
    }

    private static WorkoutPlanDay strengthDay(LocalDate startDate, int offset, String focus,
                                              Goal goal, Equipment equipment) {
        String date = startDate.plusDays(offset).toString();
        String[] names = exerciseNames(offset, equipment);
        List<Workout> workouts = new ArrayList<>(names.length);
        for (String exerciseName : names) {
            workouts.add(new Workout(exerciseName, goal.rep, BigDecimal.ZERO, date));
        }
        return new WorkoutPlanDay(date, focus, List.copyOf(workouts), null, 0);
    }

    private static WorkoutPlanDay activityDay(LocalDate startDate, int offset, String focus,
                                              String activity, int durationMinutes) {
        String date = startDate.plusDays(offset).toString();
        List<Workout> workouts = durationMinutes == 0 ? List.of()
                : List.of(new Workout(activity + " (" + durationMinutes + " minutes)",
                        "1X1", BigDecimal.ZERO, date));
        return new WorkoutPlanDay(date, focus, workouts, activity, durationMinutes);
    }

    private static String[] exerciseNames(int offset, Equipment equipment) {
        if (equipment == Equipment.BODYWEIGHT) {
            return switch (offset) {
                case 0 -> new String[]{"Bodyweight squat", "Push-up", "Glute bridge"};
                case 2 -> new String[]{"Reverse lunge", "Incline push-up", "Superman"};
                case 4 -> new String[]{"Step-up", "Pike push-up", "Bird dog"};
                default -> throw new IllegalArgumentException("Unsupported strength day: " + offset);
            };
        }
        return switch (offset) {
            case 0 -> new String[]{"Goblet squat", "Bench press", "Seated cable row"};
            case 2 -> new String[]{"Romanian deadlift", "Overhead press", "Lat pulldown"};
            case 4 -> new String[]{"Walking lunge", "Incline dumbbell press", "Seated cable row"};
            default -> throw new IllegalArgumentException("Unsupported strength day: " + offset);
        };
    }

    private enum Goal {
        GENERAL_FITNESS("general_fitness", "3X12"),
        STRENGTH("strength", "4X6"),
        MUSCLE_GAIN("muscle_gain", "3X10");

        private final String value;
        private final String rep;

        Goal(String value, String rep) {
            this.value = value;
            this.rep = rep;
        }
    }

    private enum Equipment {
        BODYWEIGHT("bodyweight"), GYM("gym");

        private final String value;

        Equipment(String value) {
            this.value = value;
        }
    }

}
