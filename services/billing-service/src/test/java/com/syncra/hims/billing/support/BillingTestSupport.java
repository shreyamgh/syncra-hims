package com.syncra.hims.billing.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Billing against a real PostgreSQL 16 initialised by the production bootstrap and
 * schema scripts; the service connects as the DML-only application role. Clinical
 * facts (patients, visits, admissions, bed stays, orders) are seeded directly in SQL
 * because the Patient Service is not part of these tests.
 */
@SpringBootTest(properties = {"syncra.billing.hospital.name=Syncra Test Hospital",
        "syncra.billing.hospital.address=1 Test Road, Pune", "syncra.billing.hospital.gstin=27ABCDE1234F1Z5"})
@AutoConfigureMockMvc
public abstract class BillingTestSupport {

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
        registry.add("syncra.security.jwt.secret", () -> "billing-test-secret-billing-test-secret-0123");
        registry.add("management.server.port", () -> "0");
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcClient jdbc;

    // ------------------------------------------------------------------ callers

    protected static RequestPostProcessor as(String subject, String... roles) {
        return jwt().jwt(t -> t.subject(subject).claim("roles", List.of(roles)))
                .authorities(Arrays.stream(roles).map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    protected static final RequestPostProcessor RECEPTION = as("test.reception", "RECEPTIONIST");
    protected static final RequestPostProcessor CLERK = as("test.billing", "BILLING_CLERK");
    protected static final RequestPostProcessor ADMIN = as("test.admin", "ADMINISTRATOR");
    protected static final RequestPostProcessor NURSE = as("test.nurse", "NURSE");

    protected ResultActions perform(MockHttpServletRequestBuilder request, RequestPostProcessor caller) throws Exception {
        return mvc.perform(request.with(caller).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_HTML));
    }

    protected ResultActions postJson(String url, Object body, RequestPostProcessor caller) throws Exception {
        return perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)), caller);
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ clinical fixtures (SQL)

    protected UUID patient(String firstName, String gender) {
        return jdbc.sql("""
                INSERT INTO patient.patient (first_name, last_name, gender, date_of_birth, address_line, city_village)
                VALUES (:first, 'Test', :gender, DATE '1975-05-05', '12 Shivaji Nagar', 'Pune') RETURNING id
                """).param("first", firstName).param("gender", gender).query(UUID.class).single();
    }

    protected UUID doctor() {
        jdbc.sql("""
                INSERT INTO core.staff (employee_code, full_name, staff_role, department_id, council_registration)
                SELECT 'BILL-TEST-DR', 'Billing Test Doctor', 'DOCTOR', id, 'TEST/1' FROM core.department WHERE code = 'GMED'
                ON CONFLICT (employee_code) DO NOTHING
                """).update();
        return jdbc.sql("SELECT id FROM core.staff WHERE employee_code = 'BILL-TEST-DR'").query(UUID.class).single();
    }

    protected record Visit(UUID visitId, UUID encounterId) {
    }

    protected Visit opdVisit(UUID patientId, String department, String visitType) {
        UUID encounter = jdbc.sql("""
                INSERT INTO clinical.encounter (patient_id, encounter_type, department_id, status)
                SELECT :patient, :type, id, 'ARRIVED' FROM core.department WHERE code = :dept RETURNING id
                """).param("patient", patientId).param("dept", department)
                .param("type", "EMERGENCY".equals(visitType) ? "EMERGENCY" : "OPD").query(UUID.class).single();
        UUID visit = jdbc.sql("""
                INSERT INTO opd.visit (encounter_id, patient_id, department_id, visit_type)
                SELECT :encounter, :patient, id, :visitType FROM core.department WHERE code = :dept RETURNING id
                """).param("encounter", encounter).param("patient", patientId).param("dept", department)
                .param("visitType", visitType).query(UUID.class).single();
        return new Visit(visit, encounter);
    }

    protected record Stay(UUID admissionId, UUID encounterId) {
    }

    protected Stay admission(UUID patientId, Instant admittedAt) {
        UUID encounter = jdbc.sql("""
                INSERT INTO clinical.encounter (patient_id, encounter_type, department_id, started_at)
                SELECT :patient, 'IPD', id, :at FROM core.department WHERE code = 'GMED' RETURNING id
                """).param("patient", patientId).param("at", utc(admittedAt)).query(UUID.class).single();
        UUID admission = jdbc.sql("""
                INSERT INTO ipd.admission (encounter_id, patient_id, department_id, admitting_doctor_id, admission_type,
                                           admitted_at, tariff_plan_id)
                SELECT :encounter, :patient, d.id, :doctor, 'EMERGENCY', :at, tp.id
                FROM core.department d, billing.tariff_plan tp WHERE d.code = 'GMED' AND tp.is_default
                RETURNING id
                """).param("encounter", encounter).param("patient", patientId).param("doctor", doctor())
                .param("at", utc(admittedAt)).query(UUID.class).single();
        return new Stay(admission, encounter);
    }

    protected void bedStay(UUID admissionId, String bedCode, Instant from, Instant to) {
        jdbc.sql("""
                INSERT INTO ipd.bed_assignment (admission_id, bed_id, assigned_at, released_at)
                SELECT :admission, id, :from, :to FROM ipd.bed WHERE bed_code = :bed
                """).param("admission", admissionId).param("bed", bedCode).param("from", utc(from))
                .param("to", to == null ? null : utc(to)).update();
    }

    protected void discharge(UUID admissionId, Instant at) {
        jdbc.sql("UPDATE ipd.admission SET status = 'DISCHARGED', discharged_at = :at WHERE id = :id")
                .param("at", utc(at)).param("id", admissionId).update();
    }

    protected void initiateDischarge(UUID admissionId) {
        jdbc.sql("UPDATE ipd.admission SET status = 'DISCHARGE_INITIATED', discharge_initiated_at = now() WHERE id = :id")
                .param("id", admissionId).update();
    }

    protected void serviceOrder(UUID encounterId, UUID patientId, String serviceCode) {
        jdbc.sql("""
                INSERT INTO clinical.service_order (encounter_id, patient_id, service_id, order_type, ordered_by)
                SELECT :encounter, :patient, s.id, 'LABORATORY', :doctor FROM billing.service s WHERE s.code = :code
                """).param("encounter", encounterId).param("patient", patientId).param("doctor", doctor())
                .param("code", serviceCode).update();
    }

    protected static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    protected static Instant ist(String localDateTime) {
        return OffsetDateTime.parse(localDateTime + "+05:30").toInstant();
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
