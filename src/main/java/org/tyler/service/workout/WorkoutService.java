package org.tyler.service.workout;

import org.springframework.stereotype.Service;
import org.tyler.dao.workout.IWorkoutDAO;
import org.tyler.model.workout.Workout;

import java.util.Map;
import java.util.Optional;

/** Service entry point for workout operations through the DAO interface. */
@Service
public class WorkoutService implements IWorkoutService {

    private final IWorkoutDAO dao;

    public WorkoutService(IWorkoutDAO dao) {
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
