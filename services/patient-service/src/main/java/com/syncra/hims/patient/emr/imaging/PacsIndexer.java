package com.syncra.hims.patient.emr.imaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.common.config.HospitalTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Keeps {@code imaging.study} in step with Orthanc. Follows Orthanc's change log from the
 * stored cursor and, for every study that became stable, records its UIDs, modality and counts,
 * matched to the patient (DICOM PatientID = UHID) and to the radiology order (accession number).
 * A matched order moves to COMPLETED, ready for the radiologist. Pixel data stays in Orthanc.
 */
@Component
public class PacsIndexer {

    private static final Logger log = LoggerFactory.getLogger(PacsIndexer.class);
    private static final int PAGE = 100;
    private static final DateTimeFormatter DICOM_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** Outcome of one sync round (for tests and logs). */
    public record Round(int indexed, int matchedToOrder, int unknownPatient, long cursor) {
    }

    private final PacsProperties properties;
    private final PacsClient pacs;
    private final ImagingRepository studies;
    private final HospitalTime time;
    private final ReentrantLock running = new ReentrantLock();

    public PacsIndexer(PacsProperties properties, PacsClient pacs, ImagingRepository studies, HospitalTime time) {
        this.properties = properties;
        this.pacs = pacs;
        this.studies = studies;
        this.time = time;
    }

    @Scheduled(fixedDelayString = "${syncra.pacs.poll-interval:PT30S}", initialDelayString = "${syncra.pacs.initial-delay:PT20S}")
    public void poll() {
        if (properties.enabled()) {
            try {
                sync();
            } catch (RestClientException e) {
                log.warn("PACS index sync failed: {}", e.getClass().getSimpleName());
            }
        }
    }

    /** Indexes everything Orthanc has reported since the cursor. */
    public Round sync() {
        if (!running.tryLock()) {
            return new Round(0, 0, 0, studies.cursor());
        }
        try {
            long cursor = studies.cursor();
            int indexed = 0;
            int matched = 0;
            int unknown = 0;
            boolean done = false;
            while (!done) {
                JsonNode page = pacs.changes(cursor, PAGE);
                Set<String> stable = new LinkedHashSet<>();
                page.path("Changes").forEach(change -> {
                    if ("StableStudy".equals(change.path("ChangeType").asText())) {
                        stable.add(change.path("ID").asText());
                    }
                });
                for (String orthancId : stable) {
                    switch (index(orthancId)) {
                        case MATCHED -> { indexed++; matched++; }
                        case INDEXED -> indexed++;
                        case UNKNOWN_PATIENT -> unknown++;
                        default -> { }
                    }
                }
                long last = page.path("Last").asLong(cursor);
                done = page.path("Done").asBoolean(true) || last <= cursor;
                cursor = Math.max(cursor, last);
                studies.advance(cursor, time.now());
            }
            if (unknown > 0) {
                log.info("PACS index: {} stable stud(ies) with a PatientID that is not a UHID here", unknown);
            }
            return new Round(indexed, matched, unknown, cursor);
        } finally {
            running.unlock();
        }
    }

    private enum Outcome { MATCHED, INDEXED, UNKNOWN_PATIENT, SKIPPED }

    private Outcome index(String orthancId) {
        JsonNode study = pacs.study(orthancId);
        if (study == null || study.isMissingNode()) {
            return Outcome.SKIPPED;
        }
        JsonNode tags = study.path("MainDicomTags");
        String uid = text(tags, "StudyInstanceUID");
        String patientId = text(study.path("PatientMainDicomTags"), "PatientID");
        if (uid == null || patientId == null || !uid.matches("^[0-9]+(\\.[0-9]+)*$") || uid.length() > 64) {
            return Outcome.SKIPPED;
        }
        Optional<UUID> patient = studies.patientByUhid(patientId.trim().toUpperCase());
        if (patient.isEmpty()) {
            return Outcome.UNKNOWN_PATIENT;
        }
        String accession = text(tags, "AccessionNumber");
        UUID order = accession == null ? null : studies.radiologyOrder(patient.get(), accession).orElse(null);

        Set<String> modalities = new LinkedHashSet<>();
        pacs.series(orthancId).forEach(series -> {
            String modality = text(series.path("MainDicomTags"), "Modality");
            if (modality != null && !"SR".equals(modality) && !"PR".equals(modality)) {
                modalities.add(modality);
            }
        });
        JsonNode stats = pacs.statistics(orthancId);
        studies.upsert(new ImagingRepository.Incoming(patient.get(), order, uid,
                accession == null || accession.length() > 16 ? null : accession, orthancId,
                modalities.isEmpty() ? null : truncate(modalities.iterator().next(), 4), date(text(tags, "StudyDate")),
                truncate(text(tags, "StudyDescription"), 200),
                stats.path("CountSeries").isNumber() ? stats.path("CountSeries").asInt() : null,
                stats.path("CountInstances").isNumber() ? stats.path("CountInstances").asInt() : null));
        if (order != null) {
            studies.orderImaged(order, time.now());
            return Outcome.MATCHED;
        }
        return Outcome.INDEXED;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText().trim();
    }

    private static LocalDate date(String dicom) {
        try {
            return dicom == null ? null : LocalDate.parse(dicom.trim(), DICOM_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
