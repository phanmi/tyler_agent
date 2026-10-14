package org.tyler.model.workout;

import java.util.List;

/** One dated strength, activity, or rest day in a plan. */
public record WorkoutPlanDay(String date, String focus, List<Workout> workouts,
                             String activity, int durationMinutes) {
}
