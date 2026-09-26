package org.tyler.dal.workout;

import org.springframework.stereotype.Component;
import org.tyler.dao.workout.IWorkoutDAO;
import org.tyler.model.workout.Workout;

import java.util.Map;
import java.util.Optional;

/** 将训练记录操作委托给持久化接口，不依赖具体数据库实现。 */
@Component
public class WorkoutDAL implements IWorkoutDAL {

    private final IWorkoutDAO dao;

    public WorkoutDAL(IWorkoutDAO dao) {
        this.dao = dao;
    }

    @Override
    public long saveWorkout(Workout workout) {
        return dao.insert(workout);
    }

    @Override
    public Optional<Workout> getWorkoutById(long id) {
        return dao.selectById(id);
    }

    @Override
    public Map<Long, Workout> getAllWorkouts() {
        return dao.selectAll();
    }

    @Override
    public boolean updateWorkout(long id, Workout workout) {
        return dao.update(id, workout);
    }

    @Override
    public boolean deleteWorkout(long id) {
        return dao.delete(id);
    }
}
