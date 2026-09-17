package org.tyler.exceptionHandler.exception;

/**
 * 写入数据库前的数据校验失败异常。
 *
 * <p>{@code food_record} 表全列 NOT NULL，因此 DAO 在 {@code INSERT} 之前必须
 * 校验 {@link org.tyler.model.food.Food} 的必填字段齐全；一旦发现缺失（而非
 * 静默转成 0 / 空串），即抛出本异常，由上层异常处理统一映射为 400。
 *
 * <p>继承 {@link IllegalArgumentException}，复用「非法参数 → 400」的既有语义。
 */
public class SQLDataValidationException extends IllegalArgumentException {

    public SQLDataValidationException(String message) {
        super(message);
    }
}