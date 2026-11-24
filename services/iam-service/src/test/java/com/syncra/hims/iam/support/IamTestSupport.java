package com.syncra.hims.iam.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** The IAM service against a real PostgreSQL 16 built by the production bootstrap and schema. */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IamTestSupport {

    public static final String SECRET = "iam-test-secret-iam-test-secret-0123456789";
    private static final String APP_DB_PASSWORD = "app-test-password";
    private static final Path REPO_ROOT = locateRepoRoot();

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("syncra_hims").withUsername("syncra_admin").withPassword("admin-test-password")
                    .withEnv(Map.of("SYNCRA_APP_DB_USER", "syncra_app", "SYNCRA_APP_DB_PASSWORD", APP_DB_PASSWORD,
                            "ORTHANC_DB_PASSWORD", "orthanc-test-password", "TZ", "Asia/Kolkata"))
                    .withCopyFileToContainer(MountableFile.forHostPath(REPO_ROOT.resolve("infra/postgres/initdb/00-bootstrap.sh"), 0755),
                            "/docker-entrypoint-initdb.d/00-bootstrap.sh")
                    .withCopyFileToContainer(MountableFile.forHostPath(REPO_ROOT.resolve("infra/postgres/initdb/10-apply-schema.sh"), 0755),
                            "/docker-entrypoint-initdb.d/10-apply-schema.sh")
                    .withCopyFileToContainer(MountableFile.forHostPath(REPO_ROOT.resolve("database/init-schema.sql")),
                            "/syncra/database/init-schema.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "syncra_app");
        registry.add("spring.datasource.password", () -> APP_DB_PASSWORD);
        registry.add("syncra.security.jwt.secret", () -> SECRET);
        registry.add("management.server.port", () -> "0");
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcClient jdbc;

    protected static RequestPostProcessor as(String subject, String... roles) {
        return jwt().jwt(t -> t.subject(subject).claim("roles", List.of(roles)))
                .authorities(Arrays.stream(roles).map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    protected static final RequestPostProcessor ADMIN = as("test.admin", "ADMINISTRATOR");

    /** A call with no token (the sign-in endpoints). */
    protected ResultActions call(String url, Object body, Cookie... cookies) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body == null ? Map.of() : body));
        if (cookies.length > 0) {
            request = request.cookie(cookies);
        }
        return mvc.perform(request);
    }

    protected ResultActions postJson(String url, Object body, RequestPostProcessor caller) throws Exception {
        return mvc.perform(post(url).with(caller).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** The refresh cookie the response set. */
    protected static Cookie refreshCookie(ResultActions result) {
        String header = result.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        String value = header.substring("SYNCRA_REFRESH=".length(), header.indexOf(';'));
        return new Cookie("SYNCRA_REFRESH", value);
    }

    protected UUID staff(String name, String role) {
        return jdbc.sql("""
                INSERT INTO core.staff (employee_code, full_name, staff_role, department_id, council_registration)
                SELECT :code, :name, :role, id, CASE WHEN :role = 'DOCTOR' THEN 'TEST/' || :code END
                FROM core.department WHERE code = 'GMED' RETURNING id
                """).param("code", "IAM-" + UUID.randomUUID().toString().substring(0, 8)).param("name", name)
                .param("role", role).query(UUID.class).single();
    }

    private static Path locateRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("database/init-schema.sql"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("database/init-schema.sql not found above the working directory");
        }
        return dir;
    }
}
