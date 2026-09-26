package org.tyler.dao.foodrecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.sqlite.SQLiteDataSource;
import org.tyler.exceptionHandler.exception.SQLDataValidationException;
import org.tyler.exceptionHandler.exception.SQLReadException;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
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
 * SQLite food-record DAO and the {@code @Primary} implementation.
 *
 * <p>Matches the {@code food_record} schema. Numeric values use {@link BigDecimal}
 * with {@code toPlainString()} on write and {@code getString} on read.
 * Required columns are validated before writing; missing values raise
 * {@link SQLDataValidationException} instead of being replaced with zero or empty strings.
 *
 * <p>The {@code food.database-path} setting is resolved by {@link IFileSandboxPath#resolve}
 * inside the sandbox. SQL and schema definitions are loaded from classpath resources
 * under {@code db/food_record/*.sql}.
 *
 * <p>{@code save} appends records. Use {@link #deleteByDate} or {@link #deleteById}
 * for deletion and {@link #loadRecordsByDate} to read records with IDs.
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
                throw new SQLReadException("Missing SQL resource: " + classpath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SQLReadException("Failed to read SQL resource: " + classpath, e);
        }
    }

    private void initSchema() {
        try {
            // Creating tables and indexes is a persistence operation.
            jdbcTemplate.execute(ddlCreateTable);
            jdbcTemplate.execute(ddlCreateIndex);
        } catch (DataAccessException e) {
            throw new SQLPersistentException("Failed to initialize the food record schema", e);
        }
        log.debug("SQLite food_record schema is ready");
    }

    @Override
    public List<Food> load() {
        try {
            return jdbcTemplate.query(sqlSelectAll, FoodRecordDAOSqlite::mapRow);
        } catch (DataAccessException e) {
            throw new SQLReadException("Failed to read food records", e);
        }
    }

    @Override
    public void save(List<Food> foods) {
        // Append records without clearing existing data.
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
        try {
            return jdbcTemplate.query(sqlSelectByDate, FoodRecordDAOSqlite::mapRow, date);
        } catch (DataAccessException e) {
            throw new SQLReadException("Failed to read food records by date", e);
        }
    }

    @Override
    public void saveByDate(String date, Food food) {
        requireDate(date);
        if (food == null) {
            throw new IllegalArgumentException("Food must not be null");
        }
        insert(date, food);
    }

    @Override
    public List<FoodRecord> loadRecordsByDate(String date) {
        requireDate(date);
        try {
            return jdbcTemplate.query(sqlSelectRecordsByDate, FoodRecordDAOSqlite::mapRecordRow, date);
        } catch (DataAccessException e) {
            throw new SQLReadException("Failed to read food records with IDs by date", e);
        }
    }

    @Override
    public boolean deleteByDate(String date) {
        requireDate(date);
        try {
            return jdbcTemplate.update(sqlDeleteByDate, date) > 0;
        } catch (DataAccessException e) {
            throw new SQLPersistentException("Failed to delete food records by date", e);
        }
    }

    @Override
    public boolean deleteById(long id) {
        try {
            return jdbcTemplate.update(sqlDeleteById, id) > 0;
        } catch (DataAccessException e) {
            throw new SQLPersistentException("Failed to delete a food record by ID", e);
        }
    }

    private void insert(String eatenDate, Food food) {
        validateForInsert(eatenDate, food);
        GenericInfo info = food.genericInfo();
        MacroNutrients macros = food.macroNutrients();
        try {
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
        } catch (DataAccessException e) {
            throw new SQLPersistentException("Failed to save food records", e);
        }
    }

    private static void validateForInsert(String eatenDate, Food food) {
        GenericInfo info = food.genericInfo();
        MacroNutrients macros = food.macroNutrients();
        if (info == null) {
            throw new SQLDataValidationException("Food record is missing genericInfo");
        }
        if (macros == null) {
            throw new SQLDataValidationException("Food record is missing macroNutrients");
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
            throw new SQLDataValidationException("Food record field " + field + " is required");
        }
    }

    private static void requireDecimal(BigDecimal value, String field) {
        if (value == null) {
            throw new SQLDataValidationException("Food record field " + field + " is required");
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
            throw new IllegalArgumentException("Date must not be blank");
        }
    }
}
