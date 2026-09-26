package org.tyler.dal.workout;

import org.tyler.model.workout.Workout;

import java.util.Map;
import java.util.Optional;

/** 训练记录的业务数据访问契约。 */
public interface IWorkoutDAL {

    long saveWorkout(Workout workout);

    Optional<Workout> getWorkoutById(long id);

    Map<Long, Workout> getAllWorkouts();

    boolean updateWorkout(long id, Workout workout);

    boolean deleteWorkout(long id);
}
