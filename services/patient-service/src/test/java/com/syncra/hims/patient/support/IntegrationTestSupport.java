package com.syncra.hims.patient.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Boots the service against a real PostgreSQL 16 initialised exactly like
 * production: the repository's own {@code 00-bootstrap.sh} creates the
 * least-privilege role and {@code 10-apply-schema.sh} runs
 * {@code database/init-schema.sql}. The service connects as that DML-only role,
 * so missing grants or schema drift fail the build. One container is shared by
 * all test classes; tests isolate themselves with fresh patients and their own
 * beds/departments rather than by truncating.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestSupport {

    protected static final String JWT_SECRET = "integration-test-secret-integration-test-secret";
    protected static final String JWT_ISSUER = "https://auth.test";
    protected static final String JWT_AUDIENCE = "syncra-hims";
    private static final String APP_DB_PASSWORD = "app-test-password";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Path REPO_ROOT = locateRepoRoot();

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("syncra_hims")
                    .withUsername("syncra_admin")
                    .withPassword("admin-test-password")
                    .withEnv(Map.of(
                            "SYNCRA_APP_DB_USER", "syncra_app",
                            "SYNCRA_APP_DB_PASSWORD", APP_DB_PASSWORD,
                            "ORTHANC_DB_PASSWORD", "orthanc-test-password",
                            "TZ", "Asia/Kolkata"))
                    .withCopyFileToContainer(script("infra/postgres/initdb/00-bootstrap.sh"),
                            "/docker-entrypoint-initdb.d/00-bootstrap.sh")
                    .withCopyFileToContainer(script("infra/postgres/initdb/10-apply-schema.sh"),
                            "/docker-entrypoint-initdb.d/10-apply-schema.sh")
                    .withCopyFileToContainer(MountableFile.forHostPath(REPO_ROOT.resolve("database/init-schema.sql")),
                            "/syncra/database/init-schema.sql");

    /** Orthanc and the AI service's CDSS, faked on one local server. */
    protected static final FakeUpstreams UPSTREAMS = new FakeUpstreams();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "syncra_app");
        registry.add("spring.datasource.password", () -> APP_DB_PASSWORD);
        registry.add("syncra.security.jwt.secret", () -> JWT_SECRET);
        registry.add("syncra.security.jwt.issuer", () -> JWT_ISSUER);
        registry.add("syncra.security.jwt.audience", () -> JWT_AUDIENCE);
        registry.add("management.server.port", () -> "0");
        registry.add("syncra.cdss.url", UPSTREAMS::url);
        registry.add("syncra.pacs.url", UPSTREAMS::url);
        registry.add("syncra.pacs.username", () -> FakeUpstreams.PACS_USER);
        registry.add("syncra.pacs.password", () -> FakeUpstreams.PACS_PASSWORD);
        // Tests drive the PACS sync themselves.
        registry.add("syncra.pacs.initial-delay", () -> "PT1H");
    }

    private static String employeeCode(String subject) {
        String code = "T-" + subject.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return code.length() > 20 ? code.substring(0, 20) : code;
    }

    /** A member of staff whose login subject is {@code subject}; returns the staff id. */
    protected UUID staffMember(String subject, String role, String name) {
        jdbc.sql("""
                INSERT INTO core.staff (employee_code, iam_subject, full_name, staff_role, council_registration)
                VALUES (:code, :subject, :name, :role,
                        CASE WHEN :role IN ('DOCTOR', 'RADIOLOGIST') THEN 'TEST/' || :code END)
                ON CONFLICT (employee_code) DO NOTHING
                """)
                .param("code", employeeCode(subject))
                .param("subject", subject).param("name", name).param("role", role).update();
        return jdbc.sql("SELECT id FROM core.staff WHERE iam_subject = :s").param("s", subject).query(UUID.class).single();
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcClient jdbc;

    // ---------------------------------------------------------------- callers

    protected static RequestPostProcessor as(String subject, String... roles) {
        return jwt()
                .jwt(token -> token.subject(subject).claim("roles", List.of(roles)))
                .authorities(Arrays.stream(roles)
                        .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                        .toList());
    }

    protected static final RequestPostProcessor RECEPTION = as("test.reception", "RECEPTIONIST");
    protected static final RequestPostProcessor NURSE = as("test.nurse", "NURSE");
    protected static final RequestPostProcessor BILLING = as("test.billing", "BILLING_CLERK");

    protected static RequestPostProcessor asDoctor(String departmentCode) {
        return as(doctorSubject(departmentCode), "DOCTOR");
    }

    private static String doctorSubject(String departmentCode) {
        return "test.doctor." + departmentCode.toLowerCase();
    }

    /** Ensures a doctor (whose login subject is known) exists in a department; returns their staff id. */
    protected UUID doctorIn(String departmentCode) {
        String employeeCode = "TEST-DR-" + departmentCode;
        jdbc.sql("""
                INSERT INTO core.staff (employee_code, iam_subject, title, full_name, staff_role,
                                       department_id, council_registration)
                SELECT :code, :subject, 'Dr', :name, 'DOCTOR', d.id, :registration
                FROM core.department AS d WHERE d.code = :department
                ON CONFLICT (employee_code) DO NOTHING
                """)
                .param("code", employeeCode)
                .param("subject", doctorSubject(departmentCode))
                .param("name", "Test " + departmentCode + " Physician")
                .param("registration", "TEST/" + departmentCode)
                .param("department", departmentCode)
                .update();
        return jdbc.sql("SELECT id FROM core.staff WHERE employee_code = :code")
                .param("code", employeeCode).query(UUID.class).single();
    }

    // ---------------------------------------------------------------- requests

    protected ResultActions perform(MockHttpServletRequestBuilder request, RequestPostProcessor caller)
            throws Exception {
        return mvc.perform(request.with(caller).accept(MediaType.APPLICATION_JSON));
    }

    protected ResultActions postJson(String url, Object body, RequestPostProcessor caller) throws Exception {
        return perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)),
                caller);
    }

    protected JsonNode body(ResultActions result) throws Exception {
        MvcResult mvcResult = result.andReturn();
        return json.readTree(mvcResult.getResponse().getContentAsString());
    }

    // ---------------------------------------------------------------- fixtures

    protected static String randomMobile() {
        return "9" + String.format("%09d", RANDOM.nextInt(1_000_000_000));
    }

    protected static String uniqueName(String base) {
        return base + Integer.toString(RANDOM.nextInt(46_656), 36);
    }

    /** Minimal valid registration body. */
    protected static Map<String, Object> patientBody(String firstName, String lastName, String gender,
                                                      LocalDate dateOfBirth, String mobile) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("gender", gender);
        body.put("dateOfBirth", dateOfBirth == null ? null : dateOfBirth.toString());
        body.put("mobile", mobile);
        return body;
    }

    /** Registers a fresh patient (skipping duplicate detection) and returns the response body. */
    protected JsonNode registerPatient(String gender, LocalDate dateOfBirth) throws Exception {
        Map<String, Object> body = patientBody(uniqueName("Test"), "Patient", gender, dateOfBirth, randomMobile());
        return body(postJson("/api/v1/patients?confirmNotDuplicate=true", body, RECEPTION)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()));
    }

    // ---------------------------------------------------------------- helpers

    private static MountableFile script(String relativePath) {
        return MountableFile.forHostPath(REPO_ROOT.resolve(relativePath), 0755);
    }

    private static Path locateRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("database/init-schema.sql"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("Could not find database/init-schema.sql above the working directory");
        }
        return dir;
    }
}
