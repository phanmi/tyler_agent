package org.tyler.service.workout;

import org.tyler.model.workout.Workout;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/** Contract for creating, reading, updating, and deleting workouts. */
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
