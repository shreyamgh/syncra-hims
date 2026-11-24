package com.syncra.hims.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.patient.emr.imaging.PacsIndexer;
import com.syncra.hims.patient.support.IntegrationTestSupport;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * CPOE to result: a lab order through sample and structured result (the creatinine reaches the
 * EMR for the renal guard), and a radiology order matched to its PACS study and reported.
 */
class OrdersAndImagingIntegrationTest extends IntegrationTestSupport {

    static final RequestPostProcessor LAB = as("test.lab", "LAB_TECHNICIAN");
    static final RequestPostProcessor RADIOLOGIST = as("test.radiologist", "RADIOLOGIST");

    @Autowired
    PacsIndexer indexer;

    @Test
    void labOrderGoesFromOrderToStructuredResult() throws Exception {
        doctorIn("GMED");
        staffMember("test.lab", "LAB_TECHNICIAN", "Lab Technician Test");
        JsonNode visit = visit();
        String encounter = visit.get("encounterId").asText();

        JsonNode rft = body(postJson("/api/v1/emr/encounters/" + encounter + "/orders",
                Map.of("serviceCode", "LAB-RFT", "priority", "URGENT", "clinicalIndication", "Rising creatinine?"),
                asDoctor("GMED")).andExpect(status().isCreated()));
        assertThat(rft.path("status").asText()).isEqualTo("ORDERED");
        assertThat(rft.path("orderType").asText()).isEqualTo("LABORATORY");
        postJson("/api/v1/emr/encounters/" + encounter + "/orders", Map.of("serviceCode", "CON-NEW"), asDoctor("GMED"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NOT_ORDERABLE"));
        postJson("/api/v1/emr/encounters/" + encounter + "/orders", Map.of("serviceCode", "LAB-CBC"), NURSE)
                .andExpect(status().isForbidden());

        String order = "/api/v1/emr/orders/" + rft.path("id").asText();
        Map<String, Object> result = Map.of("resultSummary", "Renal impairment", "values", List.of(
                Map.of("code", "CREAT", "name", "Serum creatinine", "value", 2.4, "unit", "mg/dL",
                        "referenceRange", "0.7-1.3", "flag", "H"),
                Map.of("code", "UREA", "name", "Blood urea", "value", 78, "unit", "mg/dL", "flag", "H")));
        postJson(order + "/result", result, LAB)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SAMPLE_NOT_COLLECTED"));
        postJson(order + "/sample", Map.of(), NURSE)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SAMPLE_COLLECTED"));
        JsonNode worklist = body(perform(get("/api/v1/emr/worklist").param("type", "LABORATORY"), LAB)
                .andExpect(status().isOk()));
        assertThat(worklist.findValuesAsText("id")).contains(rft.path("id").asText());

        postJson(order + "/result", result, LAB)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPORTED"))
                .andExpect(jsonPath("$.resultValues[0].code").value("CREAT"))
                .andExpect(jsonPath("$.reportedBy").value("Lab Technician Test"));
        postJson(order + "/result", result, LAB).andExpect(status().isConflict());
        postJson(order + "/cancel", Map.of("reason", "Duplicate"), asDoctor("GMED")).andExpect(status().isConflict());

        perform(get("/api/v1/emr/encounters/" + encounter), NURSE)
                .andExpect(jsonPath("$.latestCreatinine.mgPerDl").value(2.4))
                .andExpect(jsonPath("$.orders[0].status").value("REPORTED"));
    }

    @Test
    void labValuesAreFlaggedByTheServerFromTheTestParameters() throws Exception {
        doctorIn("GMED");
        staffMember("test.lab", "LAB_TECHNICIAN", "Lab Technician Test");
        String encounter = visit("F", LocalDate.of(1970, 5, 5)).get("encounterId").asText();
        String order = "/api/v1/emr/orders/" + body(postJson("/api/v1/emr/encounters/" + encounter + "/orders",
                Map.of("serviceCode", "LAB-RFT"), asDoctor("GMED")).andExpect(status().isCreated())).path("id").asText();

        // The result form: the test's parameters with this (female, adult) patient's ranges.
        JsonNode template = body(perform(get(order + "/analytes"), LAB).andExpect(status().isOk()));
        assertThat(template.path("sex").asText()).isEqualTo("F");
        assertThat(template.path("rangesApply").asBoolean()).isTrue();
        assertThat(template.path("analytes").findValuesAsText("code")).containsExactly("UREA", "CREAT", "SODIUM", "POTASSIUM");
        assertThat(template.path("analytes").get(1).path("referenceRange").asText()).isEqualTo("0.6-1.1");
        assertThat(template.path("analytes").get(3).path("criticalHigh").decimalValue()).isEqualByComparingTo("6.2");
        perform(get(order + "/analytes"), RECEPTION).andExpect(status().isForbidden());

        postJson(order + "/sample", Map.of(), NURSE).andExpect(status().isOk());
        JsonNode reported = body(postJson(order + "/result", Map.of("values", List.of(
                // 1.2 is normal for a man but high for a woman.
                Map.of("code", "CREAT", "name", "Serum creatinine", "value", 1.2),
                // Entered as normal; the server makes it critical.
                Map.of("code", "POTASSIUM", "name", "Potassium", "value", 6.5, "flag", "N"),
                Map.of("code", "SODIUM", "name", "Sodium", "value", 140),
                // A different unit: the mg/dL range cannot judge it, so the hand-entered flag stands.
                Map.of("code", "UREA", "name", "Blood urea", "value", 9.1, "unit", "mmol/L", "flag", "H"))), LAB)
                .andExpect(status().isOk()));
        JsonNode values = reported.path("resultValues");
        assertThat(values.get(0).path("flag").asText()).isEqualTo("H");
        assertThat(values.get(0).path("referenceRange").asText()).isEqualTo("0.6-1.1");
        assertThat(values.get(0).path("unit").asText()).isEqualTo("mg/dL");
        assertThat(values.get(1).path("flag").asText()).isEqualTo("C");
        assertThat(values.get(2).path("flag").asText()).isEqualTo("N");
        assertThat(values.get(3).path("flag").asText()).isEqualTo("H");
        assertThat(values.get(3).has("referenceRange")).isFalse();

        // Recently reported orders, filtered by activity time.
        perform(get("/api/v1/emr/worklist").param("type", "LABORATORY").param("status", "REPORTED")
                .param("since", java.time.Instant.now().minusSeconds(3600).toString()), LAB)
                .andExpect(jsonPath("$[?(@.id == '%s')]", reported.path("id").asText()).exists());
        perform(get("/api/v1/emr/worklist").param("type", "LABORATORY").param("status", "REPORTED")
                .param("since", java.time.Instant.now().plusSeconds(3600).toString()), LAB)
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void criticalResultIsCommunicatedWithReadBackThenAcknowledgedByTheDoctor() throws Exception {
        doctorIn("GMED");
        staffMember("test.lab", "LAB_TECHNICIAN", "Lab Technician Test");
        String encounter = visit("M", LocalDate.of(1955, 1, 1)).get("encounterId").asText();
        String rft = "/api/v1/emr/orders/" + body(postJson("/api/v1/emr/encounters/" + encounter + "/orders",
                Map.of("serviceCode", "LAB-RFT", "priority", "STAT"), asDoctor("GMED")).andExpect(status().isCreated()))
                .path("id").asText();
        String rbs = "/api/v1/emr/orders/" + body(postJson("/api/v1/emr/encounters/" + encounter + "/orders",
                Map.of("serviceCode", "LAB-RBS"), asDoctor("GMED")).andExpect(status().isCreated())).path("id").asText();
        for (String order : List.of(rft, rbs)) {
            postJson(order + "/sample", Map.of(), NURSE).andExpect(status().isOk());
        }
        postJson(rft + "/result", Map.of("values", List.of(Map.of("code", "POTASSIUM", "name", "Potassium", "value", 6.9))), LAB)
                .andExpect(status().isOk());
        // A normal result opens nothing.
        postJson(rbs + "/result", Map.of("values", List.of(Map.of("code", "GLU_R", "name", "Glucose", "value", 110))), LAB)
                .andExpect(status().isOk());

        JsonNode open = body(perform(get("/api/v1/emr/critical-results").param("communicated", "false")
                .param("encounterId", encounter), LAB).andExpect(status().isOk()));
        assertThat(open).hasSize(1);
        assertThat(open.get(0).path("status").asText()).isEqualTo("TO_COMMUNICATE");
        assertThat(open.get(0).path("criticalValues").get(0).path("code").asText()).isEqualTo("POTASSIUM");
        String id = "/api/v1/emr/critical-results/" + open.get(0).path("id").asText();

        Map<String, Object> call = new java.util.HashMap<>(Map.of("communicatedTo", "Dr Test GMED", "method", "PHONE", "readBack", false));
        postJson(id + "/communicate", call, LAB)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("READ_BACK_REQUIRED"));
        call.put("readBack", true);
        postJson(id + "/communicate", call, asDoctor("GMED")).andExpect(status().isForbidden());
        postJson(id + "/communicate", Map.of("communicatedTo", "Dr Test", "method", "FAX", "readBack", true), LAB)
                .andExpect(status().isUnprocessableEntity());
        postJson(id + "/communicate", call, LAB)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMUNICATED"))
                .andExpect(jsonPath("$.communicatedBy").value("Lab Technician Test"));
        postJson(id + "/communicate", call, LAB).andExpect(status().isConflict());

        // The ordering doctor's list of results waiting for acknowledgement.
        JsonNode mine = body(perform(get("/api/v1/emr/critical-results").param("acknowledged", "false")
                .param("orderedByMe", "true"), asDoctor("GMED")).andExpect(status().isOk()));
        assertThat(mine.findValuesAsText("id")).contains(open.get(0).path("id").asText());
        postJson(id + "/acknowledge", Map.of(), LAB).andExpect(status().isForbidden());
        postJson(id + "/acknowledge", Map.of("note", "Repeat with ECG; calcium gluconate given"), asDoctor("GMED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        postJson(id + "/acknowledge", Map.of(), asDoctor("GMED")).andExpect(status().isConflict());

        perform(get("/api/v1/emr/encounters/" + encounter), NURSE)
                .andExpect(jsonPath("$.criticalResults.length()").value(1))
                .andExpect(jsonPath("$.criticalResults[0].status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.criticalResults[0].communicationMethod").value("PHONE"));
    }

    @Test
    void childrensValuesAreNotFlaggedFromAdultRanges() throws Exception {
        doctorIn("GMED");
        staffMember("test.lab", "LAB_TECHNICIAN", "Lab Technician Test");
        String encounter = visit("M", LocalDate.now().minusYears(6)).get("encounterId").asText();
        String order = "/api/v1/emr/orders/" + body(postJson("/api/v1/emr/encounters/" + encounter + "/orders",
                Map.of("serviceCode", "LAB-CBC"), asDoctor("GMED")).andExpect(status().isCreated())).path("id").asText();

        JsonNode template = body(perform(get(order + "/analytes"), LAB).andExpect(status().isOk()));
        assertThat(template.path("rangesApply").asBoolean()).isFalse();
        assertThat(template.path("note").asText()).contains("paediatric");
        assertThat(template.path("analytes").get(0).has("referenceRange")).isFalse();

        postJson(order + "/sample", Map.of(), NURSE).andExpect(status().isOk());
        JsonNode values = body(postJson(order + "/result", Map.of("values", List.of(
                Map.of("code", "HB", "name", "Haemoglobin", "value", 10.9),
                Map.of("code", "PLT", "name", "Platelet count", "value", 95, "flag", "L"))), LAB)
                .andExpect(status().isOk())).path("resultValues");
        assertThat(values.get(0).has("flag")).isFalse();
        assertThat(values.get(1).path("flag").asText()).isEqualTo("L");
    }

    @Test
    void orderableCatalogueIsSearchedByNameOrCode() throws Exception {
        JsonNode renal = body(perform(get("/api/v1/emr/orderables").param("q", "renal"), asDoctor("GMED"))
                .andExpect(status().isOk()));
        assertThat(renal.findValuesAsText("code")).contains("LAB-RFT");

        JsonNode chest = body(perform(get("/api/v1/emr/orderables").param("q", "chest").param("type", "RADIOLOGY"), NURSE)
                .andExpect(status().isOk()));
        assertThat(chest.findValuesAsText("category")).containsOnly("RADIOLOGY");
        assertThat(chest.findValuesAsText("code")).contains("RAD-XCH");
        assertThat(chest.get(0).path("modality").asText()).isNotBlank();

        // Only investigations and procedures are orderable; consultations and beds are not.
        JsonNode co = body(perform(get("/api/v1/emr/orderables").param("q", "co"), asDoctor("GMED"))
                .andExpect(status().isOk()));
        assertThat(co.findValuesAsText("code")).contains("LAB-CBC").doesNotContain("CON-NEW", "CON-FUP");
        assertThat(co.findValuesAsText("category"))
                .allMatch(c -> List.of("LABORATORY", "RADIOLOGY", "PROCEDURE").contains(c));
        // LIKE wildcards are matched literally.
        perform(get("/api/v1/emr/orderables").param("q", "%%"), asDoctor("GMED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        perform(get("/api/v1/emr/orderables").param("q", "x"), asDoctor("GMED"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_QUERY"));
        perform(get("/api/v1/emr/orderables").param("q", "chest").param("type", "BED"), asDoctor("GMED"))
                .andExpect(status().isBadRequest());
        perform(get("/api/v1/emr/orderables").param("q", "chest"), RECEPTION).andExpect(status().isForbidden());
    }

    @Test
    void radiologyStudyIsIndexedFromThePacsMatchedAndReported() throws Exception {
        doctorIn("GMED");
        staffMember("test.radiologist", "RADIOLOGIST", "Dr Radiologist Test");
        JsonNode visit = visit();
        String encounter = visit.get("encounterId").asText();
        String uhid = visit.get("uhid").asText();

        JsonNode xray = body(postJson("/api/v1/emr/encounters/" + encounter + "/orders",
                Map.of("serviceCode", "RAD-XCH", "clinicalIndication", "Cough 3 weeks"), asDoctor("GMED"))
                .andExpect(status().isCreated()));
        String accession = xray.path("accessionNumber").asText();
        assertThat(accession).matches("RAD/\\d{2}-\\d{2}/\\d{6}");
        postJson("/api/v1/emr/orders/" + xray.path("id").asText() + "/result", Map.of("resultSummary", "x"),
                as("test.lab", "LAB_TECHNICIAN"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("USE_RADIOLOGY_REPORT"));

        String uid = "1.2.826.0.1.3680043.8.498." + Math.abs(UUID.randomUUID().getMostSignificantBits());
        UPSTREAMS.stableStudy("orthanc-" + uid.hashCode(), uid, uhid, accession, "DX", "Chest PA", 1, 2);
        UPSTREAMS.stableStudy("orthanc-other-" + uid.hashCode(), uid + ".9", "SOMEONE-ELSE", null, "CT", "Head", 3, 120);
        PacsIndexer.Round round = indexer.sync();
        assertThat(round.matchedToOrder()).isGreaterThanOrEqualTo(1);
        assertThat(round.unknownPatient()).isGreaterThanOrEqualTo(1);

        JsonNode studies = body(perform(get("/api/v1/emr/imaging/studies").param("reportStatus", "UNREPORTED")
                .param("matched", "true"), RADIOLOGIST).andExpect(status().isOk()));
        JsonNode study = null;
        for (JsonNode s : studies) {
            if (s.path("studyInstanceUid").asText().equals(uid)) {
                study = s;
            }
        }
        assertThat(study).isNotNull();
        assertThat(study.path("modality").asText()).isEqualTo("DX");
        assertThat(study.path("instanceCount").asInt()).isEqualTo(2);
        assertThat(study.path("dicomWebPath").asText()).isEqualTo("/pacs/dicom-web/studies/" + uid);
        // The radiologist sees the clinical question with the study.
        assertThat(study.path("clinicalIndication").asText()).isEqualTo("Cough 3 weeks");
        assertThat(study.path("priority").asText()).isEqualTo("ROUTINE");
        assertThat(study.has("reportContent")).isFalse();
        assertThat(jdbc.sql("SELECT status FROM clinical.service_order WHERE id = :id")
                .param("id", UUID.fromString(xray.path("id").asText())).query(String.class).single()).isEqualTo("COMPLETED");

        String report = "/api/v1/emr/imaging/studies/" + study.path("id").asText() + "/report";
        postJson(report, Map.of("content", "Lungs clear."), asDoctor("GMED")).andExpect(status().isForbidden());
        postJson(report, Map.of("content", "Chest PA: lungs clear, no effusion. Heart size normal.",
                "impression", "Normal chest radiograph"), RADIOLOGIST)
                .andExpect(status().isOk()).andExpect(jsonPath("$.reportStatus").value("FINAL"));
        postJson(report, Map.of("content", "Addendum: old healed rib fracture, right 6th rib."), RADIOLOGIST)
                .andExpect(status().isOk()).andExpect(jsonPath("$.reportStatus").value("ADDENDUM"))
                .andExpect(jsonPath("$.reportContent").value("Addendum: old healed rib fracture, right 6th rib."));

        // Recently reported, by several statuses at once.
        String id = study.path("id").asText();
        perform(get("/api/v1/emr/imaging/studies").param("reportStatus", "FINAL", "ADDENDUM")
                .param("reportedSince", java.time.Instant.now().minusSeconds(3600).toString()), RADIOLOGIST)
                .andExpect(jsonPath("$[?(@.id == '%s')]", id).exists());
        perform(get("/api/v1/emr/imaging/studies").param("reportStatus", "FINAL", "ADDENDUM")
                .param("reportedSince", java.time.Instant.now().plusSeconds(3600).toString()), RADIOLOGIST)
                .andExpect(jsonPath("$.length()").value(0));

        JsonNode record = body(perform(get("/api/v1/emr/encounters/" + encounter), NURSE).andExpect(status().isOk()));
        assertThat(record.at("/orders/0/status").asText()).isEqualTo("REPORTED");
        assertThat(record.at("/notes").findValuesAsText("noteType")).containsExactly("RADIOLOGY_REPORT", "RADIOLOGY_REPORT");
        assertThat(record.at("/notes/1/amendsNoteId").asText()).isEqualTo(record.at("/notes/0/id").asText());

        // Syncing again does not duplicate anything
        indexer.sync();
        assertThat(jdbc.sql("SELECT count(*) FROM imaging.study WHERE study_instance_uid = :u").param("u", uid)
                .query(Integer.class).single()).isEqualTo(1);
    }

    private JsonNode visit() throws Exception {
        return visit("M", LocalDate.of(1960, 3, 3));
    }

    private JsonNode visit(String gender, LocalDate dateOfBirth) throws Exception {
        JsonNode patient = registerPatient(gender, dateOfBirth);
        JsonNode visit = body(postJson("/api/v1/opd/visits",
                Map.of("uhid", patient.get("uhid").asText(), "departmentCode", "GMED"), RECEPTION)
                .andExpect(status().isCreated()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) visit).put("uhid", patient.get("uhid").asText());
        return visit;
    }
}
