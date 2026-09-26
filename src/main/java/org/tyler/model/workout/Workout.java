package org.tyler.model.workout;

import java.math.BigDecimal;

/**
 * One workout entry: name, sets and repetitions, weight, and date.
 *
 * @param workoutName workout name
 * @param rep         sets and repetitions per set, such as {@code 4X12}
 * @param weight      training weight
 * @param workoutDate workout date in YYYY-MM-DD format
 */
public record Workout(
        String workoutName ,
        String rep,
        BigDecimal weight,
        String workoutDate) {

    public Workout {
        if (rep == null || !rep.matches("[1-9]\\d*X[1-9]\\d*")) {
            throw new IllegalArgumentException("rep must use the format 4X12");
        }
    }
}
