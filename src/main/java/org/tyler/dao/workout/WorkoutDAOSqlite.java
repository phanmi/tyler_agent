package org.tyler.dao.workout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.sqlite.SQLiteDataSource;
import org.tyler.exceptionHandler.exception.SQLDataValidationException;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.exceptionHandler.exception.SQLReadException;
import org.tyler.filesandbox.IFileSandboxPath;
import org.tyler.model.workout.Workout;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** SQLite 实现：重量以十进制字符串保存，避免浮点精度丢失。 */
@Repository
public class WorkoutDAOSqlite implements IWorkoutDAO {

    private static final String SQL_DIR = "db/workout_record/";

    private final JdbcTemplate jdbcTemplate;
    private final String sqlInsert;
    private final String sqlSelectById;
    private final String sqlSelectAll;
    private final String sqlUpdate;
    private final String sqlDelete;

    public WorkoutDAOSqlite(
            IFileSandboxPath sandbox,
            @Value("${workout.database-path:workout-record.sqlite}") String dbFile) {
        Path dbPath = sandbox.resolve(dbFile);
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbPath);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.sqlInsert = loadSql("insert.sql");
        this.sqlSelectById = loadSql("select_by_id.sql");
        this.sqlSelectAll = loadSql("select_all.sql");
        this.sqlUpdate = loadSql("update.sql");
        this.sqlDelete = loadSql("delete.sql");
        try {
            jdbcTemplate.execute(loadSql("create_table.sql"));
            jdbcTemplate.execute(loadSql("create_index.sql"));
        } catch (DataAccessException e) {
            throw new SQLPersistentException("初始化训练记录表失败", e);
        }
    }

    @Override
    public long insert(Workout workout) {
        validate(workout);
        Long id;
        try {
            // INSERT ... RETURNING id 仍是写入操作，失败按持久化异常处理。
            id = jdbcTemplate.queryForObject(sqlInsert, Long.class, workout.workoutName(), workout.rep(),
                    workout.weight().toPlainString(), workout.workoutDate());
        } catch (DataAccessException e) {
            throw new SQLPersistentException("保存训练记录失败", e);
        }
        if (id == null) {
            throw new SQLPersistentException("训练记录插入后未返回主键");
        }
        return id;
    }

    @Override
    public Optional<Workout> selectById(long id) {
        requireId(id);
        try {
            return jdbcTemplate.query(sqlSelectById, WorkoutDAOSqlite::mapRow, id).stream().findFirst();
        } catch (DataAccessException e) {
            throw new SQLReadException("按主键读取训练记录失败", e);
        }
    }

    @Override
    public Map<Long, Workout> selectAll() {
        Map<Long, Workout> records = new LinkedHashMap<>();
        try {
            jdbcTemplate.query(sqlSelectAll,
                    (RowCallbackHandler) rs -> records.put(rs.getLong("id"), mapRow(rs, 0)));
        } catch (DataAccessException e) {
            throw new SQLReadException("读取全部训练记录失败", e);
        }
        return records;
    }

    @Override
    public boolean update(long id, Workout workout) {
        requireId(id);
        validate(workout);
        try {
            return jdbcTemplate.update(sqlUpdate, workout.workoutName(), workout.rep(), workout.weight().toPlainString(),
                    workout.workoutDate(), id) > 0;
        } catch (DataAccessException e) {
            throw new SQLPersistentException("更新训练记录失败", e);
        }
    }

    @Override
    public boolean delete(long id) {
        requireId(id);
        try {
            return jdbcTemplate.update(sqlDelete, id) > 0;
        } catch (DataAccessException e) {
            throw new SQLPersistentException("删除训练记录失败", e);
        }
    }

    private static String loadSql(String fileName) {
        String classpath = SQL_DIR + fileName;
        try (InputStream in = WorkoutDAOSqlite.class.getClassLoader().getResourceAsStream(classpath)) {
            if (in == null) {
                throw new SQLReadException("SQL 资源缺失：" + classpath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SQLReadException("读取 SQL 资源失败：" + classpath, e);
        }
    }

    private static Workout mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Workout(rs.getString("workout_name"), rs.getString("rep"),
                new BigDecimal(rs.getString("weight")), rs.getString("workout_date"));
    }

    private static void requireId(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("id 必须大于 0");
        }
    }

    private static void validate(Workout workout) {
        if (workout == null) {
            throw new SQLDataValidationException("Workout 不能为空");
        }
        if (workout.workoutName() == null || workout.workoutName().isBlank()) {
            throw new SQLDataValidationException("训练名称不能为空");
        }
        if (workout.weight() == null || workout.weight().signum() < 0) {
            throw new SQLDataValidationException("训练重量必须为非负数");
        }
        try {
            if (workout.workoutDate() == null) {
                throw new DateTimeParseException("missing date", "", 0);
            }
            LocalDate.parse(workout.workoutDate());
        } catch (DateTimeParseException e) {
            throw new SQLDataValidationException("训练日期必须为 YYYY-MM-DD 格式");
        }
    }
}
