package com.syncra.hims.patient.emr.imaging;

import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.patient.emr.entries.EntriesRepository;
import com.syncra.hims.patient.emr.imaging.ImagingRepository.Study;
import com.syncra.hims.patient.emr.orders.OrderRepository;
import com.syncra.hims.patient.reference.ReferenceData;
import com.syncra.hims.patient.reference.Staff;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The radiology worklist: studies indexed from the PACS, matched to orders, and reported. The
 * report is a signed RADIOLOGY_REPORT note on the order's encounter; reporting again adds an
 * addendum (a new signed note amending the previous one), never an edit.
 */
@Service
public class ImagingService {

    private final ImagingRepository studies;
    private final OrderRepository orders;
    private final EntriesRepository notes;
    private final ReferenceData reference;
    private final AuditService audit;
    private final HospitalTime time;

    public ImagingService(ImagingRepository studies, OrderRepository orders, EntriesRepository notes,
                          ReferenceData reference, AuditService audit, HospitalTime time) {
        this.studies = studies;
        this.orders = orders;
        this.notes = notes;
        this.reference = reference;
        this.audit = audit;
        this.time = time;
    }

    public List<Study> worklist(List<String> reportStatuses, Boolean matched, Instant reportedSince) {
        return studies.worklist(reportStatuses, matched, reportedSince);
    }

    /** Links a study that arrived without (or with a wrong) accession number to its order. */
    @Transactional
    public Study match(UUID studyId, UUID orderId) {
        Study study = studies.lock(studyId).orElseThrow(() -> ApiException.notFound("Study", studyId));
        if (study.orderId() != null) {
            throw ApiException.conflict("ALREADY_MATCHED", "This study is already matched to an order");
        }
        OrderRepository.Order order = orders.find(orderId).orElseThrow(() -> ApiException.notFound("Order", orderId));
        if (!order.patientId().equals(study.patientId()) || !"RADIOLOGY".equals(order.orderType())
                || "CANCELLED".equals(order.status())) {
            throw ApiException.unprocessable("ORDER_MISMATCH", "Choose an open radiology order of the same patient");
        }
        studies.match(studyId, orderId);
        studies.orderImaged(orderId, time.now());
        audit.record(Action.UPDATE, "imaging_study", studyId, study.patientId(), Map.of("action", "matched_to_order"));
        return studies.find(studyId).orElseThrow();
    }

    @Transactional
    public Study report(UUID studyId, String content, String impression) {
        Study study = studies.lock(studyId).orElseThrow(() -> ApiException.notFound("Study", studyId));
        if (study.orderId() == null) {
            throw ApiException.unprocessable("STUDY_NOT_MATCHED", "Match the study to its order before reporting it");
        }
        Staff radiologist = reference.currentStaff().orElseThrow(() -> ApiException.unprocessable(
                "STAFF_RECORD_REQUIRED", "Your login is not linked to a staff record"));
        Instant now = time.now();
        UUID note = notes.insertNote(study.encounterId(), "RADIOLOGY_REPORT", content.trim(), radiologist.getId(), true,
                study.reportNoteId());
        String status = study.reportNoteId() == null ? "FINAL" : "ADDENDUM";
        studies.reported(studyId, status, note, radiologist.getId(), now);
        String summary = impression != null && !impression.isBlank() ? impression.trim()
                : content.trim().length() <= 500 ? content.trim() : content.trim().substring(0, 497) + "...";
        orders.report(study.orderId(), summary, null, radiologist.getId(), now);
        audit.record(Action.UPDATE, "imaging_study", studyId, study.patientId(), Map.of("action", "reported",
                "status", status));
        return studies.find(studyId).orElseThrow();
    }
}
