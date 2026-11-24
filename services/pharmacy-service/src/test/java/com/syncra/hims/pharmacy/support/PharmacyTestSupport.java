package com.syncra.hims.pharmacy.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** The Pharmacy Service against a real PostgreSQL 16 built by the production bootstrap and schema. */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class PharmacyTestSupport {

    public static final String SECRET = "pharmacy-test-secret-pharmacy-test-0123456";
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

    protected static final RequestPostProcessor PHARMACIST = as("test.pharmacist", "PHARMACIST");
    protected static final RequestPostProcessor NURSE = as("test.nurse", "NURSE");

    protected ResultActions get(String url, RequestPostProcessor caller) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).with(caller));
    }

    protected ResultActions postJson(String url, Object body, RequestPostProcessor caller) throws Exception {
        return mvc.perform(post(url).with(caller).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** The pharmacist's staff record (dispensing and receipts are attributed to it). */
    protected void pharmacist() {
        jdbc.sql("""
                INSERT INTO core.staff (iam_subject, employee_code, full_name, staff_role)
                VALUES ('test.pharmacist', 'PH-TEST', 'Test Pharmacist', 'PHARMACIST') ON CONFLICT (employee_code) DO NOTHING
                """).update();
    }

    protected UUID drug(String code) {
        return jdbc.sql("SELECT id FROM pharmacy.drug WHERE code = :c").param("c", code).query(UUID.class).single();
    }

    /**
     * An issued prescription (written by a doctor with a council registration) for a new patient,
     * with the given lines: {drugText, dose, frequency, durationDays}.
     */
    protected UUID prescription(String[]... lines) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID doctor = jdbc.sql("""
                INSERT INTO core.staff (employee_code, full_name, title, staff_role, department_id, council_registration)
                SELECT :code, 'Prescriber ' || :code, 'Dr', 'DOCTOR', id, 'KMC/' || :code FROM core.department WHERE code = 'GMED'
                RETURNING id
                """).param("code", "RX-" + suffix).query(UUID.class).single();
        UUID patient = jdbc.sql("""
                INSERT INTO patient.patient (first_name, last_name, gender, date_of_birth, address_line, city_village)
                VALUES ('Patient', :s, 'F', DATE '1980-02-02', '4 MG Road', 'Mysuru') RETURNING id
                """).param("s", suffix).query(UUID.class).single();
        UUID encounter = jdbc.sql("""
                INSERT INTO clinical.encounter (patient_id, encounter_type, department_id, attending_doctor_id, status)
                SELECT :p, 'OPD', id, :d, 'IN_PROGRESS' FROM core.department WHERE code = 'GMED' RETURNING id
                """).param("p", patient).param("d", doctor).query(UUID.class).single();
        UUID rx = jdbc.sql("""
                INSERT INTO clinical.prescription (encounter_id, patient_id, prescribed_by)
                VALUES (:e, :p, :d) RETURNING id
                """).param("e", encounter).param("p", patient).param("d", doctor).query(UUID.class).single();
        int n = 1;
        for (String[] line : lines) {
            jdbc.sql("""
                    INSERT INTO clinical.prescription_item (prescription_id, line_no, drug_text, dose, frequency, duration_days)
                    VALUES (:rx, :n, :text, :dose, :freq, :days)
                    """).param("rx", rx).param("n", n++).param("text", line[0]).param("dose", line[1]).param("freq", line[2])
                    .param("days", line[3] == null ? null : Short.valueOf(line[3])).update();
        }
        // Signed: from here its lines are locked, as on the Doctor Desk.
        jdbc.sql("UPDATE clinical.prescription SET status = 'ISSUED', issued_at = now() WHERE id = :id").param("id", rx).update();
        return rx;
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
