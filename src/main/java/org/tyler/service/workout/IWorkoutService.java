package org.tyler.service.workout;

import org.tyler.model.workout.Workout;

import java.util.Map;
import java.util.Optional;

/** Contract for creating, reading, updating, and deleting workouts. */
public interface IWorkoutService {

    long saveWorkout(Workout workout);

    Optional<Workout> getWorkoutById(long id);

    Map<Long, Workout> getAllWorkouts();

    boolean updateWorkout(long id, Workout workout);

    boolean deleteWorkout(long id);
}
