package com.syncra.hims.abdm.fhir;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The FHIR documents of an encounter, generated on demand from the clinical record (never stored). */
@Service
public class HealthRecords {

    /** A generated document. {@code sha256} and {@code md5} are of the UTF-8 JSON. */
    public record Document(HiType type, String json, String sha256, String md5, int sizeBytes) {

        @Override
        public String toString() {
            return "Document[type=" + type + ", sha256=" + sha256 + ", sizeBytes=" + sizeBytes + "]";
        }
    }

    private final ClinicalRecordRepository records;
    private final HealthRecordBuilder builder;
    private final FhirJson fhir;

    public HealthRecords(ClinicalRecordRepository records, HealthRecordBuilder builder, FhirJson fhir) {
        this.records = records;
        this.builder = builder;
        this.fhir = fhir;
    }

    public Optional<EncounterRecord> record(UUID encounterId) {
        return records.load(encounterId);
    }

    public Set<HiType> available(EncounterRecord record) {
        return builder.available(record);
    }

    public Optional<Document> document(EncounterRecord record, HiType type) {
        return builder.build(type, record).map(bundle -> {
            String json = fhir.encode(bundle);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            return new Document(type, json, digest("SHA-256", bytes), digest("MD5", bytes), bytes.length);
        });
    }

    private static String digest(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }
}
