package org.tyler.service.workout;

import org.tyler.model.workout.WorkoutPlan;

/** Shared plan use cases; invalid dates, goals, or equipment cause IllegalArgumentException. */
public interface IWorkoutPlanService {
    /** Builds a seven-day preview without accessing storage. */
    WorkoutPlan previewPlan(String startDate, String goal, String equipment);

    /** Saves a whole plan atomically, reusing existing date/name matches without overwriting them.
     * Sequential repeats preserve manual edits; concurrent deduplication is not guaranteed.
     */
    WorkoutPlan generateAndSave(String startDate, String goal, String equipment);
}
