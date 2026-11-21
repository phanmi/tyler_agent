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

/**
 * Gives food JDBC operations and transactions the same connection-bound data source, as
 * {@link WorkoutDatabaseConfig} does for workouts. A list of foods can then be saved in one
 * transaction.
 */
@Configuration
public class FoodDatabaseConfig {
    @Bean
    public DataSource foodDataSource(IFileSandboxPath sandbox,
            @Value("${food.database-path:food-record.sqlite}") String dbFile) {
        return new SandboxedSqliteDataSource(sandbox, dbFile);
    }

    @Bean
    public JdbcTemplate foodJdbcTemplate(@Qualifier("foodDataSource") DataSource source) {
        return new JdbcTemplate(source);
    }

    @Bean
    public DataSourceTransactionManager foodTransactionManager(
            @Qualifier("foodDataSource") DataSource source) {
        return new DataSourceTransactionManager(source);
    }

    @Bean
    public TransactionTemplate foodTransactions(
            @Qualifier("foodTransactionManager") DataSourceTransactionManager manager) {
        return new TransactionTemplate(manager);
    }
}
