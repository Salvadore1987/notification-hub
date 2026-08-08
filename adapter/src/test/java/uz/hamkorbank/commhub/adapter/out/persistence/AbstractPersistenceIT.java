package uz.hamkorbank.commhub.adapter.out.persistence;

import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import uz.hamkorbank.commhub.adapter.out.persistence.support.PersistenceProperties;
import uz.hamkorbank.commhub.adapter.out.time.SystemClockAdapter;
import uz.hamkorbank.commhub.application.port.out.ClockPort;

/**
 * Base for the persistence integration tests: one PostgreSQL container with the Flyway schema, and a
 * Spring context holding nothing but the persistence adapters (QA-03, DB-01).
 *
 * <p>The context is deliberately minimal — no Spring Boot autoconfiguration — so what the tests exercise
 * is the SQL of the adapters, not a starter's opinion about it. Transactions are real, which is what
 * makes the {@code MANDATORY} propagation of the outbox testable.
 *
 * <p>The container is shared by the whole hierarchy: starting PostgreSQL per test class would dominate
 * the run time. Tests therefore empty the tables they use instead of relying on a fresh database.
 */
@Tag("integration")
@SpringJUnitConfig(AbstractPersistenceIT.PersistenceTestConfig.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
public abstract class AbstractPersistenceIT {

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    protected AbstractPersistenceIT(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
    }

    /** Direct SQL access for the assertions that check what actually landed in a table. */
    protected JdbcClient jdbc() {
        return jdbcClient;
    }

    /** Wraps a call in a real transaction, which the outbox needs to accept it at all (AD-03). */
    protected TransactionTemplate transactions() {
        return transactionTemplate;
    }

    /** Empties the given tables; the schema stays, only the rows go (tests must not depend on order). */
    protected void truncate(String... tables) {
        for (String table : tables) {
            jdbcClient.sql("TRUNCATE TABLE " + table + " CASCADE").update();
        }
    }

    @Configuration
    @EnableTransactionManagement
    @ComponentScan("uz.hamkorbank.commhub.adapter.out.persistence")
    public static class PersistenceTestConfig {

        @Bean
        DataSource dataSource() {
            PostgresSchema.start();
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setUrl(PostgresSchema.jdbcUrl());
            dataSource.setUsername(PostgresSchema.username());
            dataSource.setPassword(PostgresSchema.password());
            return dataSource;
        }

        /** The maintenance job needs both; the production context gets them from the scan and the properties. */
        @Bean
        ClockPort clock() {
            return new SystemClockAdapter();
        }

        @Bean
        PersistenceProperties persistenceProperties() {
            return new PersistenceProperties(null, null, null, null);
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new JdbcTransactionManager(dataSource);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }
    }
}
