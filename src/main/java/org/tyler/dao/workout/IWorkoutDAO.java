package org.tyler.dao.workout;

import org.tyler.model.workout.Workout;

import java.util.Map;
import java.util.Optional;

/** Workout persistence contract; IDs identify records for updates and deletion. */
public interface IWorkoutDAO {

    long insert(Workout workout);

    Optional<Workout> selectById(long id);

    Map<Long, Workout> selectAll();

    boolean update(long id, Workout workout);

    boolean delete(long id);
}
