package org.tyler.dao.foodrecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.sqlite.SQLiteDataSource;
import org.tyler.filesandbox.IFileSandboxPath;
import org.tyler.model.food.Food;
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
 * 食物记录基于 SQLite 的 DAO 实现，与 {@link FoodRecordDAO}（JSON）并列存在，
 * 共同实现 {@link IFoodRecordDAO} 契约。
 *
 * <p>本类是「改造成 SQLite」的新增主实现（{@code @Primary}），表结构严格对齐
 * {@code food_record} DDL：数值列以 {@link BigDecimal} 的 {@code toPlainString()}
 * 写入、{@code getString} 读回，避免浮点精度丢失。
 *
 * <p>数据库文件路径由 {@link IFileSandboxPath#resolve} 解析（与 read/write 同源，
 * 落在沙箱根目录下的 {@code food-record.sqlite}）；全部 SQL / DDL 从 classpath 下的
 * {@code db/food_record/*.sql} 加载，不在 Java 代码里内嵌 SQL。
 *
 * <p>DDL 全列 NOT NULL，写入时对 null 兜底（null 数值存 0、null 文本存空串），
 * 避免 LLM 解析不全导致插入失败。
 */
@Primary
@Repository
public class FoodRecordDAOSqlite implements IFoodRecordDAO {

    private static final Logger log = LoggerFactory.getLogger(FoodRecordDAOSqlite.class);

    private static final String DB_FILE = "food-record.sqlite";
    private static final String SQL_DIR = "db/food_record/";

    private final JdbcTemplate jdbcTemplate;
    private final String ddlCreateTable;
    private final String ddlCreateIndex;
    private final String sqlSelectAll;
    private final String sqlSelectByDate;
    private final String sqlInsert;
    private final String sqlDeleteAll;

    public FoodRecordDAOSqlite(IFileSandboxPath sandbox) {
        Path dbPath = sandbox.resolve(DB_FILE);
        this.jdbcTemplate = new JdbcTemplate(dataSource(dbPath));
        this.ddlCreateTable = loadSql("create_table.sql");
        this.ddlCreateIndex = loadSql("create_index.sql");
        this.sqlSelectAll = loadSql("select_all.sql");
        this.sqlSelectByDate = loadSql("select_by_date.sql");
        this.sqlInsert = loadSql("insert.sql");
        this.sqlDeleteAll = loadSql("delete_all.sql");
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
        // 覆盖写：先清空再逐条插入，与 JSON DAO 的整体覆盖语义对齐。
        jdbcTemplate.update(sqlDeleteAll);
        if (foods != null) {
            for (Food food : foods) {
                if (food != null) {
                    insert(dateOf(food), food);
                }
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

    private void insert(String eatenDate, Food food) {
        GenericInfo info = food.genericInfo();
        MacroNutrients macros = food.macroNutrients();
        jdbcTemplate.update(sqlInsert,
                nvlText(info == null ? null : info.foodName()),
                nvlDecimal(info == null ? null : info.amount()),
                nvlText(info == null ? null : info.unit()),
                nvlDecimal(info == null ? null : info.calories()),
                nvlDecimal(macros == null ? null : macros.protein()),
                nvlDecimal(macros == null ? null : macros.carbs()),
                nvlDecimal(macros == null ? null : macros.fat()),
                nvlDecimal(macros == null ? null : macros.fiber()),
                nvlText(eatenDate),
                LocalDateTime.now().toString());
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

    private static BigDecimal decimal(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static String nvlText(String value) {
        return value == null ? "" : value;
    }

    private static String nvlDecimal(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
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
