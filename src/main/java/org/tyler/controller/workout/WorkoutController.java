package org.tyler.controller.workout;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tyler.model.workout.Workout;
import org.tyler.service.workout.IWorkoutService;
import org.tyler.tool.workoutPlanTool.GenerateWorkoutPlanTool;
import org.tyler.tool.workoutPlanTool.GenerateWorkoutPlanTool.WorkoutPlan;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Exposes saved workouts to the calendar and delegates writes to the workout service. */
@RestController
@RequestMapping("/api/workouts")
public class WorkoutController implements IWorkoutController {

    private final IWorkoutService workoutService;
    private final GenerateWorkoutPlanTool planTool;

    public WorkoutController(IWorkoutService workoutService, GenerateWorkoutPlanTool planTool) {
        this.workoutService = workoutService;
        this.planTool = planTool;
    }

    @Override
    @GetMapping("/plan")
    public WorkoutPlan workoutPlan(@RequestParam String startDate, @RequestParam String goal,
                                   @RequestParam String equipment) {
        return planTool.previewPlan(startDate, goal, equipment);
    }

    @Override
    @GetMapping
    public List<WorkoutEntry> workoutsByDate(@RequestParam("date") String date) {
        LocalDate parsedDate;
        try {
            parsedDate = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date must use the format YYYY-MM-DD", e);
        }
        return workoutService.getAllWorkouts().entrySet().stream()
                .filter(entry -> entry.getValue().workoutDate().equals(parsedDate.toString()))
                .map(entry -> new WorkoutEntry(entry.getKey(), entry.getValue()))
                .toList();
    }

    @Override
    @PostMapping
    public ResponseEntity<WorkoutEntry> createWorkout(@RequestBody Workout workout) {
        long id = workoutService.saveWorkout(workout);
        return ResponseEntity.status(HttpStatus.CREATED).body(new WorkoutEntry(id, workout));
    }

    @Override
    @PutMapping("/{id}")
    public ResponseEntity<WorkoutEntry> updateWorkout(@PathVariable long id, @RequestBody Workout workout) {
        if (!workoutService.updateWorkout(id, workout)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new WorkoutEntry(id, workout));
    }

    @Override
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorkout(@PathVariable long id) {
        return workoutService.deleteWorkout(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    public record WorkoutEntry(long id, Workout workout) {
    }
}
