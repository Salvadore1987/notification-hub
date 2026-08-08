package uz.hamkorbank.commhub.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * DB-01: the schema must be reproducible from scratch by migrations alone.
 *
 * <p>Requires a running Docker daemon; run via {@code ./gradlew integrationTest}.
 */
@Tag("integration")
class FlywayMigrationIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16");
    private static final String SCHEMA = "comm_hub";

    @Test
    @DisplayName("DB-01: migrations build the schema from an empty database and validate afterwards")
    void migratesFromScratch() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("commhub")
                .withUsername("commhub")
                .withPassword("commhub")) {
            // Arrange
            postgres.start();
            Flyway flyway = flywayFor(postgres);

            // Act
            MigrateResult result = flyway.migrate();

            // Assert
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).isPositive();
            assertThat(schemaHistoryCount(postgres)).isEqualTo(result.migrationsExecuted);

            // Повторный прогон идемпотентен и проходит валидацию контрольных сумм.
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    private Flyway flywayFor(PostgreSQLContainer postgres) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load();
    }

    private int schemaHistoryCount(PostgreSQLContainer postgres) {
        // type <> 'SCHEMA': Flyway records the creation of the schema itself as a history row, and it is
        // not one of the migrations it reports as executed.
        String sql = "SELECT count(*) FROM " + SCHEMA + ".flyway_schema_history WHERE success AND type <> 'SCHEMA'";
        String jdbcUrl = postgres.getJdbcUrl();
        String username = postgres.getUsername();
        String password = postgres.getPassword();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the Flyway schema history", e);
        }
    }
}
