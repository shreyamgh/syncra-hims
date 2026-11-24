package com.syncra.hims.patient.emr.orders;

import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.patient.emr.critical.CriticalResultService;
import com.syncra.hims.patient.emr.entries.EntriesRepository;
import com.syncra.hims.patient.emr.orders.OrderRepository.Order;
import com.syncra.hims.patient.emr.orders.OrderRepository.Orderable;
import com.syncra.hims.patient.emr.orders.OrderRepository.ResultValue;
import com.syncra.hims.patient.reference.ReferenceData;
import com.syncra.hims.patient.reference.Staff;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computerised provider order entry for investigations, and the laboratory side of them:
 *
 * <pre>
 *  ORDERED ──sample──► SAMPLE_COLLECTED ──result──► REPORTED        (laboratory)
 *  ORDERED ──study arrives in PACS──► COMPLETED ──radiologist report──► REPORTED   (radiology)
 *  any open state ──cancel (reason)──► CANCELLED   (not once billed or reported)
 * </pre>
 * Radiology orders get a DICOM accession number at once; the modality and PACS use it to
 * match the study back to the order. A reported result is final.
 */
@Service
public class OrderService {

    static final Map<String, String> ORDER_TYPES = Map.of(
            "LABORATORY", "LABORATORY", "RADIOLOGY", "RADIOLOGY", "PROCEDURE", "PROCEDURE");
    private static final Set<String> CLOSED = Set.of("COMPLETED", "REPORTED", "CANCELLED");
    private static final int LATE_HOURS = 24;

    private final OrderRepository orders;
    private final EntriesRepository encounters;
    private final ReferenceData reference;
    private final AuditService audit;
    private final HospitalTime time;
    private final CriticalResultService critical;

    public OrderService(OrderRepository orders, EntriesRepository encounters, ReferenceData reference, AuditService audit,
                        HospitalTime time, CriticalResultService critical) {
        this.critical = critical;
        this.orders = orders;
        this.encounters = encounters;
        this.reference = reference;
        this.audit = audit;
        this.time = time;
    }

    @Transactional
    public Order place(UUID encounterId, String serviceCode, String priority, String indication) {
        EntriesRepository.EncounterFacts encounter = encounters.encounter(encounterId)
                .orElseThrow(() -> ApiException.notFound("Encounter", encounterId));
        if ("CANCELLED".equals(encounter.status())) {
            throw ApiException.conflict("ENCOUNTER_CANCELLED", "This encounter was cancelled");
        }
        if (encounter.endedAt() != null && encounter.endedAt().isBefore(time.now().minus(Duration.ofHours(LATE_HOURS)))) {
            throw ApiException.conflict("ENCOUNTER_CLOSED",
                    "Investigations can be ordered up to " + LATE_HOURS + " hours after the visit ends");
        }
        Orderable service = orders.orderable(serviceCode.trim().toUpperCase())
                .orElseThrow(() -> ApiException.unprocessable("UNKNOWN_SERVICE", "Service " + serviceCode + " is not in the catalogue"));
        String type = ORDER_TYPES.get(service.category());
        if (type == null) {
            throw ApiException.unprocessable("NOT_ORDERABLE", service.name() + " is not an investigation or procedure");
        }
        Staff doctor = reference.currentStaff().filter(Staff::isDoctor)
                .orElseThrow(() -> ApiException.unprocessable("STAFF_RECORD_REQUIRED",
                        "Orders are placed by a doctor whose login is linked to a staff record"));
        UUID id = orders.insert(encounterId, encounter.patientId(), service, type, priority == null ? "ROUTINE" : priority,
                blankToNull(indication), doctor.getId());
        audit.record(Action.CREATE, "service_order", id, encounter.patientId(), Map.of("service", service.code()));
        return orders.find(id).orElseThrow();
    }

    @Transactional
    public Order cancel(UUID orderId, String reason) {
        Order order = orders.lock(orderId).orElseThrow(() -> ApiException.notFound("Order", orderId));
        if (CLOSED.contains(order.status())) {
            throw ApiException.conflict("ORDER_CLOSED", "This order is " + order.status().toLowerCase());
        }
        if (orders.isBilled(orderId)) {
            throw ApiException.conflict("ORDER_BILLED", "This order is on a bill; cancel the bill line first");
        }
        orders.cancel(orderId, reason.trim());
        audit.record(Action.UPDATE, "service_order", orderId, order.patientId(), Map.of("action", "cancelled"));
        return orders.find(orderId).orElseThrow();
    }

    @Transactional
    public Order sampleCollected(UUID orderId, Instant at) {
        Order order = orders.lock(orderId).orElseThrow(() -> ApiException.notFound("Order", orderId));
        if (!"LABORATORY".equals(order.orderType())) {
            throw ApiException.unprocessable("NOT_A_LAB_ORDER", "Samples are collected for laboratory orders");
        }
        if (!Set.of("ORDERED", "SCHEDULED").contains(order.status())) {
            throw ApiException.conflict("ORDER_STATE", "The sample cannot be collected: the order is "
                    + order.status().toLowerCase().replace('_', ' '));
        }
        Instant collected = at == null ? time.now() : at;
        if (collected.isAfter(time.now().plusSeconds(300)) || collected.isBefore(order.orderedAt())) {
            throw ApiException.unprocessable("COLLECTION_TIME", "The collection time must be between the order and now");
        }
        orders.sampleCollected(orderId, collected);
        audit.record(Action.UPDATE, "service_order", orderId, order.patientId(), Map.of("action", "sample_collected"));
        return orders.find(orderId).orElseThrow();
    }

    @Transactional
    public Order report(UUID orderId, String summary, List<ResultValue> values) {
        Order order = orders.lock(orderId).orElseThrow(() -> ApiException.notFound("Order", orderId));
        if ("RADIOLOGY".equals(order.orderType())) {
            throw ApiException.unprocessable("USE_RADIOLOGY_REPORT", "Imaging is reported from the radiology worklist");
        }
        if ("CANCELLED".equals(order.status()) || "REPORTED".equals(order.status())) {
            throw ApiException.conflict("ORDER_CLOSED", "This order is " + order.status().toLowerCase());
        }
        if ("LABORATORY".equals(order.orderType()) && !"SAMPLE_COLLECTED".equals(order.status())
                && !"IN_PROGRESS".equals(order.status())) {
            throw ApiException.conflict("SAMPLE_NOT_COLLECTED", "Record the sample collection before the result");
        }
        if ((summary == null || summary.isBlank()) && (values == null || values.isEmpty())) {
            throw ApiException.unprocessable("RESULT_REQUIRED", "Enter a result summary or at least one value");
        }
        Staff reporter = reference.currentStaff().orElseThrow(() -> ApiException.unprocessable("STAFF_RECORD_REQUIRED",
                "Your login is not linked to a staff record"));
        List<ResultValue> flagged = flag(order, values == null ? List.of() : values);
        orders.report(orderId, blankToNull(summary), flagged, reporter.getId(), time.now());
        critical.openIfCritical(orderId, order.patientId(), flagged, reporter.getId());
        audit.record(Action.UPDATE, "service_order", orderId, order.patientId(), Map.of("action", "reported",
                "values", flagged.size(), "critical", flagged.stream().filter(v -> "C".equals(v.flag())).count()));
        return orders.find(orderId).orElseThrow();
    }

    // ------------------------------------------------------------------ lab parameters and flags

    /** What the result-entry form shows for an order: its parameters with this patient's ranges. */
    public record ResultTemplate(String sex, Integer ageYears, boolean rangesApply, String note,
                                 List<AnalyteView> analytes) {
    }

    public record AnalyteView(String code, String name, String unit, int decimals, BigDecimal low,
                              BigDecimal high, BigDecimal criticalLow,
                              BigDecimal criticalHigh, String referenceRange) {
    }

    public ResultTemplate template(UUID orderId) {
        Order order = orders.find(orderId).orElseThrow(() -> ApiException.notFound("Order", orderId));
        OrderRepository.PatientFacts patient = orders.patientFacts(order.patientId());
        Integer age = ageYears(patient);
        List<AnalyteView> views = orders.analytes(order.serviceCode()).stream().map(a -> {
            Optional<LabRange> range = LabRange.forPatient(a, patient.sex(), age);
            return new AnalyteView(a.code(), a.name(), a.unit(), a.decimals(),
                    range.map(LabRange::low).orElse(null), range.map(LabRange::high).orElse(null),
                    range.map(LabRange::criticalLow).orElse(null), range.map(LabRange::criticalHigh).orElse(null),
                    range.map(LabRange::text).orElse(null));
        }).toList();
        boolean adult = age != null && age >= LabRange.ADULT_AGE;
        String note = age == null ? "Age not recorded: no reference ranges applied; flag abnormal values by hand."
                : !adult ? "Child: adult ranges do not apply; flag abnormal values from the laboratory's paediatric charts."
                : views.stream().anyMatch(v -> v.referenceRange() == null && v.criticalLow() == null && v.criticalHigh() == null)
                        ? "Some parameters have no range for this patient; flag those by hand." : null;
        return new ResultTemplate(patient.sex(), age, adult, note, views);
    }

    /**
     * Flags each value from the test's parameters: the server, not the form, decides L/H/C for a
     * numeric value in the parameter's own unit. Values in another unit, text values and children
     * keep the flag entered by hand.
     */
    private List<ResultValue> flag(Order order, List<ResultValue> values) {
        if (values.isEmpty()) {
            return values;
        }
        Map<String, LabRange.Analyte> byCode = new HashMap<>();
        orders.analytes(order.serviceCode()).forEach(a -> byCode.put(a.code(), a));
        OrderRepository.PatientFacts patient = orders.patientFacts(order.patientId());
        Integer age = ageYears(patient);
        return values.stream().map(v -> {
            LabRange.Analyte analyte = byCode.get(v.code());
            if (analyte == null) {
                return v;
            }
            String unit = v.unit() == null || v.unit().isBlank() ? analyte.unit() : v.unit().trim();
            boolean sameUnit = analyte.unit() == null || analyte.unit().equalsIgnoreCase(unit);
            Optional<LabRange> range = sameUnit ? LabRange.forPatient(analyte, patient.sex(), age)
                    : Optional.empty();
            String flag = v.flag();
            String referenceRange = v.referenceRange();
            if (range.isPresent()) {
                if (v.value() != null) {
                    flag = LabRange.merge(range.get().flag(v.value()), v.flag());
                }
                if (referenceRange == null || referenceRange.isBlank()) {
                    referenceRange = range.get().text();
                }
            }
            String name = v.name() == null || v.name().isBlank() ? analyte.name() : v.name();
            return new ResultValue(v.code(), name, v.value(), v.text(), unit, referenceRange, flag);
        }).toList();
    }

    private Integer ageYears(OrderRepository.PatientFacts patient) {
        return patient.dateOfBirth() == null ? null
                : Period.between(patient.dateOfBirth(), time.today()).getYears();
    }

    public List<Order> forEncounter(UUID encounterId) {
        return orders.forEncounter(encounterId);
    }

    public List<Order> worklist(String orderType, List<String> statuses, Instant since) {
        return orders.worklist(orderType, statuses, since);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
