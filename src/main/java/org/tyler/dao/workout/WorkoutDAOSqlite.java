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

/** SQLite persistence using decimal strings to preserve weight precision. */
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
            throw new SQLPersistentException("Failed to initialize the workout schema", e);
        }
    }

    @Override
    public long insert(Workout workout) {
        validate(workout);
        Long id;
        try {
            // Inserting a record and returning its ID is a persistence operation.
            id = jdbcTemplate.queryForObject(sqlInsert, Long.class, workout.workoutName(), workout.rep(),
                    workout.weight().toPlainString(), workout.workoutDate());
        } catch (DataAccessException e) {
            throw new SQLPersistentException("Failed to save the workout", e);
        }
        if (id == null) {
            throw new SQLPersistentException("Workout insertion did not return an ID");
        }
        return id;
    }

    @Override
    public Optional<Workout> selectById(long id) {
        requireId(id);
        try {
            return jdbcTemplate.query(sqlSelectById, WorkoutDAOSqlite::mapRow, id).stream().findFirst();
        } catch (DataAccessException e) {
            throw new SQLReadException("Failed to read the workout by ID", e);
        }
    }

    @Override
    public Map<Long, Workout> selectAll() {
        Map<Long, Workout> records = new LinkedHashMap<>();
        try {
            jdbcTemplate.query(sqlSelectAll,
                    (RowCallbackHandler) rs -> records.put(rs.getLong("id"), mapRow(rs, 0)));
        } catch (DataAccessException e) {
            throw new SQLReadException("Failed to read workouts", e);
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
            throw new SQLPersistentException("Failed to update the workout", e);
        }
    }

    @Override
    public boolean delete(long id) {
        requireId(id);
        try {
            return jdbcTemplate.update(sqlDelete, id) > 0;
        } catch (DataAccessException e) {
            throw new SQLPersistentException("Failed to delete the workout", e);
        }
    }

    private static String loadSql(String fileName) {
        String classpath = SQL_DIR + fileName;
        try (InputStream in = WorkoutDAOSqlite.class.getClassLoader().getResourceAsStream(classpath)) {
            if (in == null) {
                throw new SQLReadException("Missing SQL resource: " + classpath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SQLReadException("Failed to read SQL resource: " + classpath, e);
        }
    }

    private static Workout mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Workout(rs.getString("workout_name"), rs.getString("rep"),
                new BigDecimal(rs.getString("weight")), rs.getString("workout_date"));
    }

    private static void requireId(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be greater than 0");
        }
    }

    private static void validate(Workout workout) {
        if (workout == null) {
            throw new SQLDataValidationException("Workout must not be null");
        }
        if (workout.workoutName() == null || workout.workoutName().isBlank()) {
            throw new SQLDataValidationException("Workout name must not be blank");
        }
        if (workout.weight() == null || workout.weight().signum() < 0) {
            throw new SQLDataValidationException("Workout weight must be nonnegative");
        }
        try {
            if (workout.workoutDate() == null) {
                throw new DateTimeParseException("missing date", "", 0);
            }
            LocalDate.parse(workout.workoutDate());
        } catch (DateTimeParseException e) {
            throw new SQLDataValidationException("Workout date must use the YYYY-MM-DD format");
        }
    }
}
