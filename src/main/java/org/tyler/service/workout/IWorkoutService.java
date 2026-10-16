package org.tyler.service.workout;

import org.tyler.model.workout.Workout;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Contract for creating, reading, updating, and deleting workouts.
 * Invalid domain input raises {@link IllegalArgumentException} before storage access.
 * Writes require a nonblank name, nonnegative weight and a real ISO date; the model
 * enforces positive sets/repetitions. IDs must be positive; valid missing IDs retain
 * the absence result. Storage exceptions propagate unchanged.
 */
public interface IWorkoutService {

    long saveWorkout(Workout workout);

    Optional<Workout> getWorkoutById(long id);

    Map<Long, Workout> getAllWorkouts();

    /**
     * Returns records for a parsed date in ascending ID order, or an empty map.
     * Callers parse external date strings before entering this service.
     *
     * @throws IllegalArgumentException if date is null
     * @throws org.tyler.exceptionHandler.exception.SQLReadException if storage cannot be read
     */
    Map<Long, Workout> getWorkoutsByDate(LocalDate date);

    boolean updateWorkout(long id, Workout workout);

    boolean deleteWorkout(long id);
}
