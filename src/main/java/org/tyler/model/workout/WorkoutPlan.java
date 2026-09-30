package org.tyler.model.workout;

import java.util.List;

/** Shared calendar and tool response; field names preserve the public JSON contract. */
public record WorkoutPlan(String startDate, String goal, String equipment,
                          String weightGuidance, List<WorkoutPlanDay> days) {
}
