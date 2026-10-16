package com.locksteptickets;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Testcontainers
class PostgresMigrationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void configureMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(springSecurity())
            .build();
    }

    @Test
    void startsAgainstPostgresAndAppliesFlywayMigration() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("select current_database()")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo(POSTGRES.getDatabaseName());
        }

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("7");
    }

    @Test
    void upgradesAnExistingV1SchemaToLatest() {
        Flyway baseline = Flyway.configure()
            .dataSource(dataSource)
            .schemas("lockstep_upgrade")
            .defaultSchema("lockstep_upgrade")
            .createSchemas(true)
            .target(MigrationVersion.fromVersion("1"))
            .load();
        baseline.migrate();
        assertThat(baseline.info().current().getVersion().getVersion()).isEqualTo("1");

        Flyway upgrade = Flyway.configure()
            .dataSource(dataSource)
            .schemas("lockstep_upgrade")
            .defaultSchema("lockstep_upgrade")
            .createSchemas(true)
            .load();
        upgrade.migrate();
        assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("7");
    }

    @Test
    void exposesOnlyRedactedHealthWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist());

        mockMvc.perform(get("/actuator/info"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void doesNotGenerateADevelopmentUser() {
        assertThat(applicationContext.getBeanNamesForType(UserDetailsService.class))
            .isEmpty();
        assertThat(org.springframework.jdbc.core.simple.JdbcClient.create(dataSource)
            .sql("SELECT count(*) FROM account")
            .query(Long.class)
            .single()).isZero();
    }
}

