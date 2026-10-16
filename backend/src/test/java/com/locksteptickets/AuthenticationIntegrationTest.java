package com.locksteptickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import com.locksteptickets.api.RequestIdFilter;
import com.locksteptickets.api.JsonRequestSizeFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest(properties = {
    "lockstep.authentication.ip-max-failures=6",
    "lockstep.http.trusted-proxy-cidrs=172.16.0.0/12"
})
@Testcontainers
class AuthenticationIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    private JsonRequestSizeFilter jsonRequestSizeFilter;

    private MockMvc mockMvc;
    private String passwordHash;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .addFilters(requestIdFilter, jsonRequestSizeFilter)
            .apply(springSecurity())
            .build();
        jdbcClient.sql("DELETE FROM access_session").update();
        jdbcClient.sql("DELETE FROM login_throttle").update();
        jdbcClient.sql("DELETE FROM account").update();
        passwordHash = passwordEncoder.encode(PASSWORD);
    }

    @Test
    void loginCreatesHashedSessionAndDerivesIdentityFromBearerToken() throws Exception {
        UUID accountId = insertAccount("customer@example.com", "CUSTOMER", true);

        MvcResult login = login("CUSTOMER@example.com", PASSWORD)
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.account.id").value(accountId.toString()))
            .andExpect(jsonPath("$.account.role").value("CUSTOMER"))
            .andReturn();

        String token = json(login).get("accessToken").asText();
        assertThat(token).hasSize(43);
        assertThat(jdbcClient.sql("SELECT octet_length(token_hash) FROM access_session")
            .query(Integer.class).single()).isEqualTo(32);

        mockMvc.perform(get("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(accountId.toString()))
            .andExpect(jsonPath("$.email").value("customer@example.com"));
    }

    @Test
    void invalidUnknownAndInactiveCredentialsShareSafeFailure() throws Exception {
        insertAccount("inactive@example.com", "CUSTOMER", false);

        login("missing@example.com", PASSWORD)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.error.message").value("The credentials are invalid."));
        login("inactive@example.com", PASSWORD)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        login("inactive@example.com", "wrong-password")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));

        assertThat(jdbcClient.sql("SELECT count(*) FROM access_session")
            .query(Long.class).single()).isZero();
    }

    @Test
    void rateLimitsNormalizedAccountAfterBoundedFailures() throws Exception {
        insertAccount("target@example.com", "CUSTOMER", true);

        for (int attempt = 1; attempt < 5; attempt++) {
            String email = attempt % 2 == 0 ? "TARGET@example.com" : "target@example.com";
            login(email, "wrong-password")
                .andExpect(status().isUnauthorized());
        }

        login("target@example.com", "wrong-password")
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.error.code").value("LOGIN_RATE_LIMITED"));
        login("target@example.com", PASSWORD)
            .andExpect(status().isTooManyRequests());
        assertThat(jdbcClient.sql("SELECT count(*) FROM access_session")
            .query(Long.class).single()).isZero();
    }

    @Test
    void rateLimitsIpAddressAcrossUnknownAccounts() throws Exception {
        for (int attempt = 1; attempt < 6; attempt++) {
            login("missing-" + attempt + "@example.com", "wrong-password")
                .andExpect(status().isUnauthorized());
        }

        login("another-missing@example.com", "wrong-password")
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.error.code").value("LOGIN_RATE_LIMITED"));
    }

    @Test
    void separatesForwardedClientsOnlyBehindConfiguredProxy() throws Exception {
        for (int attempt = 1; attempt < 6; attempt++) {
            proxiedLogin("203.0.113.10", "missing-proxied-" + attempt + "@example.com")
                .andExpect(status().isUnauthorized());
        }

        proxiedLogin("203.0.113.10", "blocked-proxied@example.com")
            .andExpect(status().isTooManyRequests());
        proxiedLogin("203.0.113.11", "separate-proxied@example.com")
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsOversizedJsonBeforeAuthenticationWork() throws Exception {
        String oversizedPassword = "x".repeat(65_536);

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(
                    new LoginBody("person@example.com", oversizedPassword))))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"))
            .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                .doesNotContain(oversizedPassword));

        assertThat(jdbcClient.sql("SELECT count(*) FROM login_throttle")
            .query(Long.class).single()).isZero();
    }

    @Test
    void rejectsUnknownJsonAndDoesNotEchoSensitiveInput() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"person@example.com","password":"secret-value","role":"ADMIN"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"))
            .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                .doesNotContain("secret-value", "ADMIN"));
    }

    @Test
    void enforcesAuthenticationRoleAndCurrentAccountActivity() throws Exception {
        UUID customerId = insertAccount("customer@example.com", "CUSTOMER", true);
        UUID adminId = insertAccount("admin@example.com", "ADMIN", true);
        String customerToken = tokenFor("customer@example.com");
        String adminToken = tokenFor("admin@example.com");

        mockMvc.perform(get("/api/admin/session"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/admin/session")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/actuator/info")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/admin/session")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(adminId.toString()))
            .andExpect(jsonPath("$.role").value("ADMIN"));

        jdbcClient.sql("UPDATE account SET active = false WHERE id = :id")
            .param("id", customerId).update();
        mockMvc.perform(get("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsExpiredAndMalformedTokens() throws Exception {
        insertAccount("customer@example.com", "CUSTOMER", true);
        String token = tokenFor("customer@example.com");
        jdbcClient.sql("""
                UPDATE access_session
                SET created_at = :createdAt, expires_at = :expiresAt
                """)
            .param("createdAt", Timestamp.from(Instant.now().minusSeconds(120)))
            .param("expiresAt", Timestamp.from(Instant.now().minusSeconds(60)))
            .update();

        for (String authorization : new String[] {
            "Bearer " + token,
            "Bearer too-short",
            "Basic Zm9vOmJhcg=="
        }) {
            mockMvc.perform(get("/api/auth/me")
                    .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        }
    }

    @Test
    void preservesOnlySafeRequestIds() throws Exception {
        mockMvc.perform(get("/api/auth/me").header("X-Request-ID", "safe_request-123"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("X-Request-ID", "safe_request-123"))
            .andExpect(jsonPath("$.error.requestId").value("safe_request-123"));

        mockMvc.perform(get("/api/auth/me").header("X-Request-ID", "unsafe value\n"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("X-Request-ID", org.hamcrest.Matchers.not("unsafe value\n")));
    }

    private UUID insertAccount(String email, String role, boolean active) {
        return jdbcClient.sql("""
                INSERT INTO account (email, password_hash, role, active)
                VALUES (:email, :passwordHash, :role, :active)
                RETURNING id
                """)
            .param("email", email)
            .param("passwordHash", passwordHash)
            .param("role", role)
            .param("active", active)
            .query(UUID.class)
            .single();
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password)
        throws Exception {
        String body = objectMapper.writeValueAsString(new LoginBody(email, password));
        return mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions proxiedLogin(
        String clientAddress,
        String email
    ) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginBody(email, "wrong-password"));
        return mockMvc.perform(post("/api/auth/login")
            .with(request -> {
                request.setRemoteAddr("172.20.0.5");
                return request;
            })
            .header("X-Forwarded-For", "198.51.100.200, " + clientAddress)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private String tokenFor(String email) throws Exception {
        return json(login(email, PASSWORD).andExpect(status().isOk()).andReturn())
            .get("accessToken").asText();
    }

    private JsonNode json(MvcResult result) {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private record LoginBody(String email, String password) {
    }
}
