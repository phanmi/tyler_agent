package org.tyler.model.workout;

import java.math.BigDecimal;

/**
 * 一次训练记录：训练项目、名称、组数与次数、重量、训练日期。
 *
 * @param workoutName        训练名称
 * @param rep         组数与每组次数，格式如 {@code 4X12}
 * @param weight      训练重量
 * @param workoutDate 训练日期，YYYY-MM-DD
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
