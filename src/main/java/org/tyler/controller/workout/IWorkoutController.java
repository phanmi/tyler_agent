package org.tyler.controller.workout;

import org.springframework.http.ResponseEntity;
import org.tyler.model.workout.Workout;
import org.tyler.tool.workoutPlanTool.GenerateWorkoutPlanTool.WorkoutPlan;

import java.util.List;

/** HTTP operations used by the workout calendar. */
public interface IWorkoutController {

    List<WorkoutController.WorkoutEntry> workoutsByDate(String date);

    WorkoutPlan workoutPlan(String startDate, String goal, String equipment);

    ResponseEntity<WorkoutController.WorkoutEntry> createWorkout(Workout workout);

    ResponseEntity<WorkoutController.WorkoutEntry> updateWorkout(long id, Workout workout);

    ResponseEntity<Void> deleteWorkout(long id);
}
