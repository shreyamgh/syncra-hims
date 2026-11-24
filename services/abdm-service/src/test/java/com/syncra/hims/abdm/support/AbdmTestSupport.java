package com.syncra.hims.abdm.support;

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
import java.util.concurrent.ThreadLocalRandom;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The ABDM service against a real PostgreSQL 16 (production bootstrap and schema, DML-only
 * role), a real Redis and {@link FakeAbdm} standing in for the ABDM gateway, ABHA and an HIU.
 * Clinical facts are seeded in SQL because the Patient Service is not part of these tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbdmTestSupport {

    public static final String HIP_ID = "IN2710000001";
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

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.10-alpine")).withExposedPorts(6379);

    protected static final FakeAbdm ABDM = new FakeAbdm();

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "syncra_app");
        registry.add("spring.datasource.password", () -> APP_DB_PASSWORD);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("syncra.security.jwt.secret", () -> "abdm-test-secret-abdm-test-secret-0123456789");
        registry.add("management.server.port", () -> "0");
        registry.add("syncra.abdm.enabled", () -> "true");
        registry.add("syncra.abdm.hip-id", () -> HIP_ID);
        registry.add("syncra.abdm.hip-name", () -> "Syncra Test Hospital");
        registry.add("syncra.abdm.gateway.base-url", ABDM::gatewayUrl);
        registry.add("syncra.abdm.gateway.client-id", () -> FakeAbdm.CLIENT_ID);
        registry.add("syncra.abdm.gateway.client-secret", () -> FakeAbdm.CLIENT_SECRET);
        registry.add("syncra.abdm.abha.base-url", ABDM::abhaUrl);
        // The fake HIU listens on plain HTTP on loopback.
        registry.add("syncra.abdm.data-push.require-https", () -> "false");
        registry.add("syncra.abdm.hiu.public-url", () -> "https://bridge.hims.test");
        registry.add("syncra.abdm.fhir.hospital-name", () -> "Syncra Test Hospital");
        registry.add("syncra.abdm.otp.sms-url", ABDM::smsUrl);
        registry.add("syncra.abdm.otp.sms-auth-value", () -> "Key sms-test-key");
        registry.add("syncra.abdm.otp.sms-sender-id", () -> "SYNCRA");
        registry.add("syncra.abdm.otp.sms-template-id", () -> "1207170000000000001");
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
    protected static final RequestPostProcessor DOCTOR = as("test.doctor", "DOCTOR");
    protected static final RequestPostProcessor MRD = as("test.mrd", "MRD_OFFICER");

    protected ResultActions perform(MockHttpServletRequestBuilder request, RequestPostProcessor caller) throws Exception {
        return mvc.perform(request.with(caller).accept(MediaType.APPLICATION_JSON, MediaType.parseMediaType("application/fhir+json")));
    }

    protected ResultActions postJson(String url, Object body, RequestPostProcessor caller) throws Exception {
        return perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)), caller);
    }

    /** A callback from ABDM: gateway-signed bearer token, REQUEST-ID, X-HIP-ID. */
    protected ResultActions callback(String path, UUID requestId, Object body, String token) throws Exception {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))
                .header("REQUEST-ID", requestId.toString())
                .header("TIMESTAMP", Instant.now().toString())
                .header("X-HIP-ID", HIP_ID)
                .header("X-CM-ID", "sbx");
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(request);
    }

    protected ResultActions callback(String path, UUID requestId, Object body) throws Exception {
        return callback(path, requestId, body, ABDM.gatewayToken());
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ clinical fixtures (SQL)

    /** A fresh 14-digit ABHA number starting 91. */
    protected static String newAbhaNumber() {
        return "91" + String.format("%012d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
    }

    protected UUID patient(String firstName, String gender, int yearOfBirth) {
        return patient(firstName, "Kulkarni", gender, yearOfBirth, "9876543210");
    }

    protected UUID patient(String firstName, String lastName, String gender, int yearOfBirth, String mobile) {
        return jdbc.sql("""
                INSERT INTO patient.patient (first_name, last_name, gender, date_of_birth, mobile, address_line, city_village)
                VALUES (:first, :last, :gender, make_date(:year, 5, 5), :mobile, '12 Shivaji Nagar', 'Pune')
                RETURNING id
                """).param("first", firstName).param("last", lastName).param("gender", gender).param("year", yearOfBirth)
                .param("mobile", mobile).query(UUID.class).single();
    }

    /** A mobile number no other test patient has. */
    protected static String newMobile() {
        return "7" + String.format("%09d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
    }

    /** Stamps a verified ABHA directly (as if verified earlier). */
    protected void verifiedAbha(UUID patientId, String abhaNumber, String abhaAddress) {
        jdbc.sql("UPDATE patient.patient SET abha_number = :n, abha_address = :a, abha_verified_at = now() WHERE id = :id")
                .param("n", abhaNumber).param("a", abhaAddress).param("id", patientId).update();
    }

    protected String uhid(UUID patientId) {
        return jdbc.sql("SELECT uhid FROM patient.patient WHERE id = :id").param("id", patientId).query(String.class).single();
    }

    protected UUID doctor() {
        jdbc.sql("""
                INSERT INTO core.staff (employee_code, full_name, title, staff_role, department_id, council_registration,
                                        qualification)
                SELECT 'ABDM-TEST-DR', 'Anil Mehta', 'Dr', 'DOCTOR', id, 'MMC/2011/12345', 'MBBS, MD'
                FROM core.department WHERE code = 'GMED'
                ON CONFLICT (employee_code) DO NOTHING
                """).update();
        return jdbc.sql("SELECT id FROM core.staff WHERE employee_code = 'ABDM-TEST-DR'").query(UUID.class).single();
    }

    /**
     * A finished OPD consultation: vitals, a chief complaint, a provisional diagnosis, an
     * investigation order and an issued two-line prescription.
     */
    protected UUID opdConsultation(UUID patientId, Instant at) {
        UUID doctor = doctor();
        UUID encounter = jdbc.sql("""
                INSERT INTO clinical.encounter (patient_id, encounter_type, department_id, attending_doctor_id, status,
                                                started_at, ended_at)
                SELECT :patient, 'OPD', id, :doctor, 'FINISHED', :at, :end FROM core.department WHERE code = 'GMED'
                RETURNING id
                """).param("patient", patientId).param("doctor", doctor).param("at", utc(at))
                .param("end", utc(at.plusSeconds(1200))).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO clinical.vital_sign (encounter_id, recorded_at, recorded_by, temperature_c, pulse_bpm,
                                                 bp_systolic, bp_diastolic, spo2_percent)
                VALUES (:e, :at, 'test.nurse', 38.6, 104, 130, 84, 97)
                """).param("e", encounter).param("at", utc(at.plusSeconds(60))).update();
        jdbc.sql("""
                INSERT INTO clinical.complaint (encounter_id, description, duration_value, duration_unit, is_chief, recorded_by)
                VALUES (:e, 'Fever', 3, 'DAYS', true, 'test.doctor')
                """).param("e", encounter).update();
        jdbc.sql("""
                INSERT INTO clinical.diagnosis (encounter_id, icd10_code, description, is_primary, diagnosed_by)
                VALUES (:e, 'A90', 'Dengue fever', true, 'test.doctor')
                """).param("e", encounter).update();
        jdbc.sql("""
                INSERT INTO clinical.service_order (encounter_id, patient_id, service_id, order_type, ordered_by, ordered_at)
                SELECT :e, :p, id, 'LABORATORY', :doctor, :at FROM billing.service WHERE code = 'LAB-CBC'
                """).param("e", encounter).param("p", patientId).param("doctor", doctor)
                .param("at", utc(at.plusSeconds(600))).update();
        UUID rx = jdbc.sql("""
                INSERT INTO clinical.prescription (encounter_id, patient_id, prescribed_by, advice, follow_up_date)
                VALUES (:e, :p, :doctor, 'Oral fluids', CAST(:at AS date) + 3) RETURNING id
                """).param("e", encounter).param("p", patientId).param("doctor", doctor).param("at", utc(at))
                .query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO clinical.prescription_item (prescription_id, line_no, drug_text, dose, route, frequency,
                                                        timing, duration_days)
                VALUES (:rx, 1, 'Tab Paracetamol 650 mg', '1 tab', 'ORAL', 'TDS', 'AFTER_FOOD', 3),
                       (:rx, 2, 'ORS sachet', '1 sachet in 1 L water', 'ORAL', 'SOS', NULL, NULL)
                """).param("rx", rx).update();
        jdbc.sql("UPDATE clinical.prescription SET status = 'ISSUED', issued_at = :at WHERE id = :id")
                .param("at", utc(at.plusSeconds(1100))).param("id", rx).update();
        return encounter;
    }

    /**
     * Results for the encounter: its lab order reported, plus a chest X-ray with a signed,
     * final radiology report and its PACS study.
     */
    protected void reportedInvestigations(UUID patientId, UUID encounterId, Instant at) {
        UUID doctor = doctor();
        jdbc.sql("""
                UPDATE clinical.service_order SET status = 'REPORTED', completed_at = :at,
                       result_summary = 'Hb 13.2 g/dL; WBC 3,200/µL; platelets 1.1 lakh/µL'
                WHERE encounter_id = :e AND order_type = 'LABORATORY'
                """).param("at", utc(at)).param("e", encounterId).update();
        String accession = "T" + String.format("%012d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        UUID order = jdbc.sql("""
                INSERT INTO clinical.service_order (encounter_id, patient_id, service_id, order_type, status, ordered_by,
                                                    ordered_at, completed_at, accession_number)
                SELECT :e, :p, id, 'RADIOLOGY', 'COMPLETED', :doctor, :at, :at, :acc FROM billing.service WHERE code = 'RAD-XCH'
                RETURNING id
                """).param("e", encounterId).param("p", patientId).param("doctor", doctor).param("at", utc(at))
                .param("acc", accession).query(UUID.class).single();
        UUID note = jdbc.sql("""
                INSERT INTO clinical.clinical_note (encounter_id, note_type, content, author_id, signed_at)
                VALUES (:e, 'RADIOLOGY_REPORT', 'Chest PA: no consolidation or effusion. Impression: normal study.',
                        :doctor, :at) RETURNING id
                """).param("e", encounterId).param("doctor", doctor).param("at", utc(at)).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO imaging.study (patient_id, service_order_id, study_instance_uid, accession_number, modality,
                                           study_date, study_description, series_count, instance_count, report_status,
                                           report_note_id, reported_by, reported_at)
                VALUES (:p, :o, :uid, :acc, 'DX', CAST(:at AS date), 'Chest PA', 1, 1, 'FINAL', :note, :doctor, :at)
                """).param("p", patientId).param("o", order)
                .param("uid", "1.2.826.0.1.3680043.8.498." + ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE))
                .param("acc", accession).param("note", note).param("doctor", doctor).param("at", utc(at)).update();
    }

    /** A PENTA dose 1 given in the encounter, with the next dose due in four weeks. */
    protected void vaccination(UUID patientId, UUID encounterId, Instant at) {
        jdbc.sql("""
                INSERT INTO clinical.immunization (encounter_id, patient_id, vaccine_code, dose_number, administered_at,
                                                   lot_number, manufacturer, expiry_date, route, site, dose_ml,
                                                   next_due_date, recorded_by)
                VALUES (:e, :p, 'PENTA', 1, :at, 'SII-PENTA-2611', 'Serum Institute of India', DATE '2027-06-30', 'IM',
                        'LEFT_THIGH', 0.5, CAST(:at AS date) + 28, 'test.nurse')
                """).param("e", encounterId).param("p", patientId).param("at", utc(at)).update();
    }

    /** A care context already linked to the ABHA (as after a completed link flow). */
    protected void linkedCareContext(UUID patientId, UUID encounterId, String abhaAddress) {
        jdbc.sql("""
                INSERT INTO abdm.care_context (patient_id, encounter_id, reference_number, display, hi_types, link_status,
                                               linked_abha_address, linked_at)
                VALUES (:p, :e, :ref, 'OP consultation', ARRAY['OPConsultation','Prescription']::varchar[], 'LINKED',
                        :address, now())
                """).param("p", patientId).param("e", encounterId).param("ref", encounterId.toString())
                .param("address", abhaAddress).update();
    }

    protected static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
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
