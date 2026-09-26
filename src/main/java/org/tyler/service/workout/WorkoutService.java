package org.tyler.service.workout;

import org.springframework.stereotype.Service;
import org.tyler.dal.workout.IWorkoutDAL;
import org.tyler.model.workout.Workout;

import java.util.Map;
import java.util.Optional;

/** Service entry point for workout operations through the DAL interface. */
@Service
public class WorkoutService implements IWorkoutService {

    private final IWorkoutDAL dal;

    public WorkoutService(IWorkoutDAL dal) {
        this.dal = dal;
    }

    @Override
    public long saveWorkout(Workout workout) {
        return dal.saveWorkout(workout);
    }

    @Override
    public Optional<Workout> getWorkoutById(long id) {
        return dal.getWorkoutById(id);
    }

    @Override
    public Map<Long, Workout> getAllWorkouts() {
        return dal.getAllWorkouts();
    }

    @Override
    public boolean updateWorkout(long id, Workout workout) {
        return dal.updateWorkout(id, workout);
    }

    @Override
    public boolean deleteWorkout(long id) {
        return dal.deleteWorkout(id);
    }
}
