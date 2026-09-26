package org.tyler.service.workout;

import org.tyler.model.workout.Workout;

import java.util.Map;
import java.util.Optional;

/** 训练记录的增删改查服务契约。 */
public interface IWorkoutService {

    long saveWorkout(Workout workout);

    Optional<Workout> getWorkoutById(long id);

    Map<Long, Workout> getAllWorkouts();

    boolean updateWorkout(long id, Workout workout);

    boolean deleteWorkout(long id);
}
