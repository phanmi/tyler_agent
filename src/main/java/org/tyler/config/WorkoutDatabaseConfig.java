package org.tyler.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.tyler.filesandbox.IFileSandboxPath;
import org.tyler.filesandbox.SandboxedSqliteDataSource;

import javax.sql.DataSource;

/** Gives workout JDBC operations and transactions the same connection-bound data source. */
@Configuration
public class WorkoutDatabaseConfig {
    @Bean
    public DataSource workoutDataSource(IFileSandboxPath sandbox,
            @Value("${workout.database-path:workout-record.sqlite}") String dbFile) {
        return new SandboxedSqliteDataSource(sandbox, dbFile);
    }

    @Bean
    public JdbcTemplate workoutJdbcTemplate(@Qualifier("workoutDataSource") DataSource source) {
        return new JdbcTemplate(source);
    }

    @Bean
    public DataSourceTransactionManager workoutTransactionManager(
            @Qualifier("workoutDataSource") DataSource source) {
        return new DataSourceTransactionManager(source);
    }

    @Bean
    public TransactionTemplate workoutTransactions(
            @Qualifier("workoutTransactionManager") DataSourceTransactionManager manager) {
        return new TransactionTemplate(manager);
    }
}
