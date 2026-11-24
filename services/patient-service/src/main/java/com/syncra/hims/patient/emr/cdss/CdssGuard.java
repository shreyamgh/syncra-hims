package com.syncra.hims.patient.emr.cdss;

import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.patient.emr.cdss.CdssClient.Alert;
import com.syncra.hims.patient.emr.dto.ConsultationRequest.CdssOverride;
import com.syncra.hims.patient.emr.dto.ConsultationRequest.PrescriptionItemInput;
import com.syncra.hims.patient.emr.entries.EntriesRepository;
import com.syncra.hims.patient.emr.orders.OrderRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Server-side CDSS at signing. The Doctor Desk shows alerts as the prescription is written;
 * this re-runs the same check with what the database knows (active allergies, age, sex, latest
 * weight, serum creatinine from the last 90 days) so a prescription cannot be signed past a
 * CONTRAINDICATED or MAJOR alert without the prescriber's reason. Every alert is recorded in
 * {@code clinical.cdss_alert} with how it was resolved.
 */
@Component
public class CdssGuard {

    private static final Logger log = LoggerFactory.getLogger(CdssGuard.class);
    private static final Duration CREATININE_WINDOW = Duration.ofDays(90);

    /** An alert as the staff API shows it (camelCase). */
    public record AlertView(String alertType, String severity, String ruleCode, String message, String management,
                            List<Integer> lines, String allergen) {

        static AlertView of(Alert a) {
            return new AlertView(a.alertType(), a.severity(), a.ruleCode(), a.message(), a.management(), a.lines(),
                    a.allergen());
        }
    }

    /** The check's outcome: {@code checked} false when the CDSS could not be reached. */
    public record Outcome(boolean checked, String kbVersion, List<Alert> alerts, Map<String, String> overrides) {
    }

    private final CdssProperties properties;
    private final CdssClient client;
    private final EntriesRepository entries;
    private final OrderRepository orders;
    private final JdbcClient jdbc;
    private final HospitalTime time;

    public CdssGuard(CdssProperties properties, CdssClient client, EntriesRepository entries, OrderRepository orders,
                     JdbcClient jdbc, HospitalTime time) {
        this.properties = properties;
        this.client = client;
        this.entries = entries;
        this.orders = orders;
        this.jdbc = jdbc;
        this.time = time;
    }

    /**
     * @throws ApiException 422 CDSS_OVERRIDE_REQUIRED (with the alerts) when a serious alert has no reason;
     *                      503 CDSS_UNAVAILABLE when the check is required and could not run
     */
    public Outcome check(UUID patientId, String gender, LocalDate dateOfBirth, BigDecimal weightKg,
                         List<PrescriptionItemInput> items, List<CdssOverride> overrides) {
        Map<String, String> reasons = overrides == null ? Map.of() : overrides.stream()
                .collect(Collectors.toMap(CdssOverride::ruleCode, o -> o.reason().trim(), (a, b) -> a));
        if (!properties.configured()) {
            return unavailable(reasons, "not configured");
        }
        CdssClient.Request request = new CdssClient.Request(
                items.stream().map(i -> new CdssClient.Line(i.drugText(), i.dose(),
                        i.frequency() == null ? null : i.frequency().name(), i.durationDays())).toList(),
                entries.allergies(patientId).stream().filter(a -> "ACTIVE".equals(a.status()))
                        .map(a -> new CdssClient.Allergy(a.allergenText(), a.drugClass())).toList(),
                new CdssClient.Patient(dateOfBirth == null ? null : Period.between(dateOfBirth, time.today()).getYears(),
                        gender, weightKg != null ? weightKg : latestWeight(patientId),
                        orders.latestCreatinine(patientId, time.now().minus(CREATININE_WINDOW))
                                .map(OrderRepository.Creatinine::mgPerDl).orElse(null)));
        CdssClient.Response response;
        try {
            response = client.check(request);
        } catch (HttpClientErrorException e) {
            // A 4xx means this service sent something the CDSS cannot read: a defect, not an outage.
            log.error("CDSS rejected the signing check with HTTP {}; prescriptions are going unchecked",
                    e.getStatusCode().value());
            return unavailable(reasons, "request rejected");
        } catch (RestClientException e) {
            log.warn("CDSS unavailable at signing: {}", e.getClass().getSimpleName());
            return unavailable(reasons, "unreachable");
        }
        List<Alert> unresolved = response.alerts().stream()
                .filter(Alert::isSerious)
                .filter(a -> reasons.getOrDefault(a.ruleCode(), "").length() < 5)
                .toList();
        if (!unresolved.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CDSS_OVERRIDE_REQUIRED",
                    unresolved.size() + " serious CDSS alert(s) need a reason to override before signing",
                    Map.of("alerts", unresolved.stream().map(AlertView::of).toList(), "kbVersion", response.kbVersion()));
        }
        return new Outcome(true, response.kbVersion(), response.alerts(), reasons);
    }

    /** Records every alert shown: serious ones as OVERRIDDEN with the reason, the rest as ACKNOWLEDGED. */
    public void record(UUID encounterId, UUID prescriptionId, Outcome outcome, String prescriber) {
        for (Alert alert : outcome.alerts()) {
            boolean serious = alert.isSerious();
            String message = alert.message() == null ? alert.ruleCode()
                    : alert.message().length() <= 1000 ? alert.message() : alert.message().substring(0, 1000);
            jdbc.sql("""
                    INSERT INTO clinical.cdss_alert (encounter_id, prescription_id, alert_type, severity, message,
                                                     rule_code, kb_version, resolution, resolved_by, resolved_at,
                                                     override_reason)
                    VALUES (:e, :rx, :type, :severity, :message, :rule, :kb, :resolution, :by, :at, :reason)
                    """).param("e", encounterId).param("rx", prescriptionId).param("type", alert.alertType())
                    .param("severity", alert.severity()).param("message", message).param("rule", alert.ruleCode())
                    .param("kb", outcome.kbVersion()).param("resolution", serious ? "OVERRIDDEN" : "ACKNOWLEDGED")
                    .param("by", prescriber).param("at", Timestamp.from(time.now()))
                    .param("reason", serious ? outcome.overrides().get(alert.ruleCode()) : null)
                    .update();
        }
    }

    private Outcome unavailable(Map<String, String> reasons, String why) {
        if (properties.required()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CDSS_UNAVAILABLE",
                    "The drug-safety check is unavailable (" + why + "); prescriptions cannot be signed until it returns");
        }
        return new Outcome(false, null, List.of(), reasons);
    }

    private BigDecimal latestWeight(UUID patientId) {
        return jdbc.sql("""
                SELECT v.weight_kg FROM clinical.vital_sign v JOIN clinical.encounter e ON e.id = v.encounter_id
                WHERE e.patient_id = :p AND v.weight_kg IS NOT NULL AND NOT v.is_entered_in_error
                  AND v.recorded_at >= now() - interval '180 days'
                ORDER BY v.recorded_at DESC LIMIT 1
                """).param("p", patientId).query(BigDecimal.class).optional().orElse(null);
    }
}
