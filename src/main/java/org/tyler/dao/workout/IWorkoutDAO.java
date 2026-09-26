package org.tyler.dao.workout;

import org.tyler.model.workout.Workout;

import java.util.Map;
import java.util.Optional;

/** 训练记录的持久化契约；主键用于精确更新和删除。 */
public interface IWorkoutDAO {

    long insert(Workout workout);

    Optional<Workout> selectById(long id);

    Map<Long, Workout> selectAll();

    boolean update(long id, Workout workout);

    boolean delete(long id);
}
