package org.tyler.service.workout;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.tyler.dao.workout.IWorkoutDAO;
import org.tyler.model.workout.Workout;
import org.tyler.validation.DomainValidation;
import org.tyler.validation.IValidator;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/** Validates workout use cases before persistence, including writes from plan generation. */
@Service
public class WorkoutService implements IWorkoutService {

    private final IWorkoutDAO dao;
    private final IValidator<Workout> validator;

    public WorkoutService(IWorkoutDAO dao, @Qualifier("workoutValidator") IValidator<Workout> validator) {
        this.dao = dao;
        this.validator = validator;
    }

    @Override
    public long saveWorkout(Workout workout) {
        validator.validate(workout);
        return dao.insert(workout);
    }

    @Override
    public Optional<Workout> getWorkoutById(long id) {
        DomainValidation.requireId(id);
        return dao.selectById(id);
    }

    @Override
    public Map<Long, Workout> getAllWorkouts() {
        return dao.selectAll();
    }

    @Override
    public Map<Long, Workout> getWorkoutsByDate(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date must not be null");
        }
        return dao.selectByDate(date);
    }

    @Override
    public boolean updateWorkout(long id, Workout workout) {
        DomainValidation.requireId(id);
        validator.validate(workout);
        return dao.update(id, workout);
    }

    @Override
    public boolean deleteWorkout(long id) {
        DomainValidation.requireId(id);
        return dao.delete(id);
    }
}
