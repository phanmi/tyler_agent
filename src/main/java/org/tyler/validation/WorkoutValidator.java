package org.tyler.validation;

import org.tyler.model.workout.Workout;

/** Shared workout rules; the model retains its intrinsic sets/repetitions invariant. */
public final class WorkoutValidator {
    private WorkoutValidator() {
    }

    public static void validate(Workout workout) {
        if (workout == null) {
            throw new IllegalArgumentException("Workout must not be null");
        }
        if (workout.workoutName() == null || workout.workoutName().isBlank()) {
            throw new IllegalArgumentException("Workout name must not be blank");
        }
        if (workout.weight() == null || workout.weight().signum() < 0) {
            throw new IllegalArgumentException("Workout weight must be nonnegative");
        }
        DomainValidation.parseDate(workout.workoutDate(), "workoutDate");
    }
}
