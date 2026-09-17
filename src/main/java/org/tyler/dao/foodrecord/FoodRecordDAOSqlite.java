package org.tyler.dao.foodrecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.sqlite.SQLiteDataSource;
import org.tyler.exceptionHandler.exception.SQLDataValidationException;
import org.tyler.filesandbox.IFileSandboxPath;
import org.tyler.model.food.Food;
import org.tyler.model.food.FoodRecord;
import org.tyler.model.food.GenericInfo;
import org.tyler.model.food.MacroNutrients;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 食物记录基于 SQLite 的 DAO 实现，是当前唯一的 {@code @Primary} 主实现。
 *
 * <p>表结构严格对齐 {@code food_record} DDL：数值列以 {@link BigDecimal} 的
 * {@code toPlainString()} 写入、{@code getString} 读回，避免浮点精度丢失；
 * 全列 NOT NULL，因此写入前做严格校验，缺失字段抛 {@link SQLDataValidationException}
 * 而非静默转 0 / 空串。
 *
 * <p>数据库文件路径由配置 {@code food.database-path} 经 {@link IFileSandboxPath#resolve}
 * 解析（与 read/write 同源，落在沙箱根目录）；全部 SQL / DDL 从 classpath 下的
 * {@code db/food_record/*.sql} 加载，不在 Java 代码里内嵌 SQL。
 *
 * <p>{@code save} 是纯 INSERT（不做覆盖写）；删除语义由 {@link #deleteByDate} /
 * {@link #deleteById} 单独承担，读取带主键用 {@link #loadRecordsByDate}。
 */
@Primary
@Repository
public class FoodRecordDAOSqlite implements IFoodRecordDAO {

    private static final Logger log = LoggerFactory.getLogger(FoodRecordDAOSqlite.class);

    private static final String SQL_DIR = "db/food_record/";

    private final JdbcTemplate jdbcTemplate;
    private final String ddlCreateTable;
    private final String ddlCreateIndex;
    private final String sqlSelectAll;
    private final String sqlSelectByDate;
    private final String sqlSelectRecordsByDate;
    private final String sqlInsert;
    private final String sqlDeleteByDate;
    private final String sqlDeleteById;

    public FoodRecordDAOSqlite(
            IFileSandboxPath sandbox,
            @Value("${food.database-path:food-record.sqlite}") String dbFile) {
        Path dbPath = sandbox.resolve(dbFile);
        this.jdbcTemplate = new JdbcTemplate(dataSource(dbPath));
        this.ddlCreateTable = loadSql("create_table.sql");
        this.ddlCreateIndex = loadSql("create_index.sql");
        this.sqlSelectAll = loadSql("select_all.sql");
        this.sqlSelectByDate = loadSql("select_by_date.sql");
        this.sqlSelectRecordsByDate = loadSql("select_records_by_date.sql");
        this.sqlInsert = loadSql("insert.sql");
        this.sqlDeleteByDate = loadSql("delete_by_date.sql");
        this.sqlDeleteById = loadSql("delete_by_id.sql");
        initSchema();
    }

    private static SQLiteDataSource dataSource(Path dbPath) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbPath);
        return dataSource;
    }

    private static String loadSql(String fileName) {
        String classpath = SQL_DIR + fileName;
        try (InputStream in = FoodRecordDAOSqlite.class.getClassLoader().getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IllegalStateException("SQL 资源缺失：" + classpath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 SQL 资源失败：" + classpath, e);
        }
    }

    private void initSchema() {
        jdbcTemplate.execute(ddlCreateTable);
        jdbcTemplate.execute(ddlCreateIndex);
        log.debug("SQLite food_record 表已就绪");
    }

    @Override
    public List<Food> load() {
        return jdbcTemplate.query(sqlSelectAll, FoodRecordDAOSqlite::mapRow);
    }

    @Override
    public void save(List<Food> foods) {
        // 纯 INSERT：只追加、不清空，不做覆盖写。
        if (foods == null) {
            return;
        }
        for (Food food : foods) {
            if (food != null) {
                insert(dateOf(food), food);
            }
        }
    }

    @Override
    public List<Food> loadByDate(String date) {
        requireDate(date);
        return jdbcTemplate.query(sqlSelectByDate, FoodRecordDAOSqlite::mapRow, date);
    }

    @Override
    public void saveByDate(String date, Food food) {
        requireDate(date);
        if (food == null) {
            throw new IllegalArgumentException("Food 不能为空");
        }
        insert(date, food);
    }

    @Override
    public List<FoodRecord> loadRecordsByDate(String date) {
        requireDate(date);
        return jdbcTemplate.query(sqlSelectRecordsByDate, FoodRecordDAOSqlite::mapRecordRow, date);
    }

    @Override
    public boolean deleteByDate(String date) {
        requireDate(date);
        return jdbcTemplate.update(sqlDeleteByDate, date) > 0;
    }

    @Override
    public boolean deleteById(long id) {
        return jdbcTemplate.update(sqlDeleteById, id) > 0;
    }

    private void insert(String eatenDate, Food food) {
        validateForInsert(eatenDate, food);
        GenericInfo info = food.genericInfo();
        MacroNutrients macros = food.macroNutrients();
        jdbcTemplate.update(sqlInsert,
                info.foodName(),
                info.amount().toPlainString(),
                info.unit(),
                info.calories().toPlainString(),
                macros.protein().toPlainString(),
                macros.carbs().toPlainString(),
                macros.fat().toPlainString(),
                macros.fiber().toPlainString(),
                eatenDate,
                LocalDateTime.now().toString());
    }

    private static void validateForInsert(String eatenDate, Food food) {
        GenericInfo info = food.genericInfo();
        MacroNutrients macros = food.macroNutrients();
        if (info == null) {
            throw new SQLDataValidationException("食物记录缺少 genericInfo");
        }
        if (macros == null) {
            throw new SQLDataValidationException("食物记录缺少 macroNutrients");
        }
        requireText(info.foodName(), "foodName");
        requireDecimal(info.amount(), "amount");
        requireText(info.unit(), "unit");
        requireDecimal(info.calories(), "calories");
        requireDecimal(macros.protein(), "protein");
        requireDecimal(macros.carbs(), "carbs");
        requireDecimal(macros.fat(), "fat");
        requireDecimal(macros.fiber(), "fiber");
        requireText(eatenDate, "date");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new SQLDataValidationException("食物记录字段 " + field + " 不能为空");
        }
    }

    private static void requireDecimal(BigDecimal value, String field) {
        if (value == null) {
            throw new SQLDataValidationException("食物记录字段 " + field + " 不能为空");
        }
    }

    private static Food mapRow(ResultSet rs, int rowNum) throws SQLException {
        GenericInfo info = new GenericInfo(
                rs.getString("food_name"),
                decimal(rs, "amount"),
                rs.getString("unit"),
                decimal(rs, "calories"),
                rs.getString("eaten_date"));
        MacroNutrients macros = new MacroNutrients(
                decimal(rs, "protein"),
                decimal(rs, "carbs"),
                decimal(rs, "fat"),
                decimal(rs, "fiber"));
        return new Food(info, macros);
    }

    private static FoodRecord mapRecordRow(ResultSet rs, int rowNum) throws SQLException {
        long id = rs.getLong("id");
        Food food = mapRow(rs, rowNum);
        String createdAt = rs.getString("created_at");
        LocalDateTime parsedCreatedAt = createdAt == null ? null : LocalDateTime.parse(createdAt);
        return new FoodRecord(id, food, parsedCreatedAt);
    }

    private static BigDecimal decimal(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static String dateOf(Food food) {
        return food.genericInfo() == null ? null : food.genericInfo().date();
    }

    private static void requireDate(String date) {
        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException("日期不能为空");
        }
    }
}
