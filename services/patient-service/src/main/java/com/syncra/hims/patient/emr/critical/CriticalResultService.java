package com.syncra.hims.patient.emr.critical;

import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.patient.emr.critical.CriticalResultRepository.CriticalResult;
import com.syncra.hims.patient.emr.orders.OrderRepository.ResultValue;
import com.syncra.hims.patient.reference.ReferenceData;
import com.syncra.hims.patient.reference.Staff;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Critical results: opened when a lab value is reported critical, then the call to the treating
 * team is logged (to whom, how, read back) and the doctor acknowledges it.
 */
@Service
public class CriticalResultService {

    public static final Set<String> METHODS = Set.of("PHONE", "IN_PERSON", "WARD_NURSE");

    private final CriticalResultRepository repository;
    private final ReferenceData reference;
    private final AuditService audit;
    private final HospitalTime time;

    public CriticalResultService(CriticalResultRepository repository, ReferenceData reference, AuditService audit,
                                 HospitalTime time) {
        this.repository = repository;
        this.reference = reference;
        this.audit = audit;
        this.time = time;
    }

    /** Called in the reporting transaction: opens a record when any value is critical. */
    public void openIfCritical(UUID orderId, UUID patientId, List<ResultValue> values, UUID reportedBy) {
        List<ResultValue> critical = values.stream().filter(v -> "C".equals(v.flag())).toList();
        if (critical.isEmpty()) {
            return;
        }
        UUID id = repository.open(orderId, patientId, critical, reportedBy, time.now());
        audit.record(Action.CREATE, "critical_result", id, patientId, Map.of("values", critical.size()));
    }

    public List<CriticalResult> list(Boolean communicated, Boolean acknowledged, boolean orderedByMe, UUID encounterId) {
        UUID doctor = orderedByMe ? staff().getId() : null;
        return repository.list(communicated, acknowledged, doctor, encounterId);
    }

    @Transactional
    public CriticalResult communicate(UUID id, String to, String method, boolean readBack) {
        CriticalResult result = repository.lock(id).orElseThrow(() -> ApiException.notFound("Critical result", id));
        if (result.communicatedAt() != null) {
            throw ApiException.conflict("ALREADY_COMMUNICATED", "This result was already communicated to "
                    + result.communicatedTo());
        }
        if (!readBack) {
            throw ApiException.unprocessable("READ_BACK_REQUIRED",
                    "Ask the person told to read the value back, then confirm the read-back");
        }
        Staff by = staff();
        repository.communicate(id, to.trim(), method, by.getId(), time.now());
        audit.record(Action.UPDATE, "critical_result", id, result.patientId(), Map.of("action", "communicated",
                "method", method));
        return repository.find(id).orElseThrow();
    }

    @Transactional
    public CriticalResult acknowledge(UUID id, String note) {
        CriticalResult result = repository.lock(id).orElseThrow(() -> ApiException.notFound("Critical result", id));
        if (result.acknowledgedAt() != null) {
            throw ApiException.conflict("ALREADY_ACKNOWLEDGED", "Already acknowledged by " + result.acknowledgedBy());
        }
        Staff doctor = staff();
        repository.acknowledge(id, doctor.getId(), time.now(), note == null || note.isBlank() ? null : note.trim());
        audit.record(Action.UPDATE, "critical_result", id, result.patientId(), Map.of("action", "acknowledged"));
        return repository.find(id).orElseThrow();
    }

    private Staff staff() {
        return reference.currentStaff().orElseThrow(() -> ApiException.unprocessable("STAFF_RECORD_REQUIRED",
                "Your login is not linked to a staff record; ask the administrator to link it"));
    }
}
