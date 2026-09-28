package org.tyler.dao.workout;

import org.tyler.model.workout.Workout;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/** Workout persistence contract; IDs identify records for updates and deletion. */
public interface IWorkoutDAO {

    long insert(Workout workout);

    Optional<Workout> selectById(long id);

    Map<Long, Workout> selectAll();

    /**
     * Returns only the supplied date's records in ascending ID order, or an empty map.
     *
     * @throws IllegalArgumentException if date is null
     * @throws org.tyler.exceptionHandler.exception.SQLReadException if storage cannot be read
     */
    Map<Long, Workout> selectByDate(LocalDate date);

    boolean update(long id, Workout workout);

    boolean delete(long id);
}
