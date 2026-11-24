package com.syncra.hims.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.billing.support.BillingTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class BillingApiIntegrationTest extends BillingTestSupport {

    private static final String OPB = "^OPB/\\d{2}-\\d{2}/\\d{6}$";

    @Test
    void opdSlipChargesRegistrationOnceAndConsultationByVisitType() throws Exception {
        UUID patient = patient("Ramesh", "M");
        Visit first = opdVisit(patient, "GMED", "NEW");

        JsonNode slip = body(postJson("/api/v1/billing/opd-slips", Map.of("visitId", first.visitId(),
                "payment", Map.of("mode", "CASH")), RECEPTION)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.billNumber").value(Matchers.matchesPattern(OPB)))
                .andExpect(jsonPath("$.title").value("Bill of Supply"))
                .andExpect(jsonPath("$.status").value("FINAL"))
                .andExpect(jsonPath("$.lines[*].serviceCode", Matchers.contains("REG-OPD", "CON-NEW")))
                .andExpect(jsonPath("$.totals.payable").value(350.0))
                .andExpect(jsonPath("$.totals.balanceDue").value(0.0))
                .andExpect(jsonPath("$.receipts[0].receiptNumber").value(Matchers.startsWith("RCT/"))));
        assertThat(slip.get("lines").get(1).get("description").asText()).isEqualTo("Consultation - New Visit — General Medicine");

        postJson("/api/v1/billing/opd-slips", Map.of("visitId", first.visitId()), RECEPTION)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_BILLED"))
                .andExpect(jsonPath("$.billNumber").value(slip.get("billNumber").asText()));

        Visit followUp = opdVisit(patient, "ENT", "FOLLOW_UP");
        postJson("/api/v1/billing/opd-slips", Map.of("visitId", followUp.visitId()), RECEPTION)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[*].serviceCode", Matchers.contains("CON-FUP")))
                .andExpect(jsonPath("$.totals.payable").value(150.0))
                .andExpect(jsonPath("$.totals.balanceDue").value(150.0));
    }

    @Test
    void discountsNeedAnAdministratorAndPaymentsCannotExceedTheBalance() throws Exception {
        UUID patient = patient("Sita", "F");
        Visit visit = opdVisit(patient, "GMED", "NEW");
        Map<String, Object> withDiscount = Map.of("visitId", visit.visitId(),
                "items", List.of(Map.of("serviceCode", "LAB-CBC", "quantity", 1, "discountAmount", 50)));

        postJson("/api/v1/billing/opd-slips", withDiscount, RECEPTION)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DISCOUNT_NOT_PERMITTED"));
        postJson("/api/v1/billing/opd-slips", Map.of("visitId", visit.visitId(),
                "items", List.of(Map.of("serviceCode", "BED-DAY", "quantity", 1))), RECEPTION)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AUTOMATIC_CHARGE"));

        JsonNode bill = body(postJson("/api/v1/billing/opd-slips", withDiscount, ADMIN)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totals.discount").value(50.0))
                .andExpect(jsonPath("$.totals.payable").value(600.0)));
        String payments = "/api/v1/billing/bills/" + bill.get("billId").asText() + "/payments";

        postJson(payments, Map.of("type", "SETTLEMENT", "payment", Map.of("mode", "UPI")), RECEPTION)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REFERENCE_REQUIRED"));
        postJson(payments, Map.of("type", "SETTLEMENT", "payment", Map.of("mode", "CASH", "amount", 700)), RECEPTION)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OVERPAYMENT"));
        postJson(payments, Map.of("type", "SETTLEMENT", "payment", Map.of("mode", "UPI", "reference", "UPI-RRN-77")), RECEPTION)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totals.balanceDue").value(0.0));
        postJson(payments, Map.of("type", "SETTLEMENT", "payment", Map.of("mode", "CASH")), RECEPTION)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOTHING_DUE"));

        String approver = jdbc.sql("SELECT discount_approved_by FROM billing.bill_item WHERE discount_amount > 0 AND bill_id = :b")
                .param("b", UUID.fromString(bill.get("billId").asText())).query(String.class).single();
        assertThat(approver).isEqualTo("test.admin");
    }

    @Test
    void finalDischargeBillUsesMidnightCensusAndSetsOffAdvances() throws Exception {
        UUID patient = patient("Mahesh", "M");
        Stay stay = admission(patient, ist("2026-11-10T10:00"));
        bedStay(stay.admissionId(), "GMW-08", ist("2026-11-10T10:00"), ist("2026-11-12T09:00"));
        bedStay(stay.admissionId(), "PVT-03", ist("2026-11-12T09:00"), ist("2026-11-13T11:00"));
        String base = "/api/v1/billing/admissions/" + stay.admissionId();

        postJson(base + "/advances", Map.of("payment", Map.of("mode", "CASH", "amount", 5000)), CLERK)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptNumber").value(Matchers.startsWith("RCT/")))
                .andExpect(jsonPath("$.unallocatedAdvance").value(5000.0));
        postJson(base + "/bill/finalize", Map.of(), CLERK)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DISCHARGE_NOT_INITIATED"));

        discharge(stay.admissionId(), ist("2026-11-13T11:00"));
        JsonNode bill = body(postJson(base + "/bill/finalize", Map.of(), CLERK)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billNumber").value(Matchers.matchesPattern("^IPB/\\d{2}-\\d{2}/\\d{6}$")))
                .andExpect(jsonPath("$.admissionNumber").value(Matchers.startsWith("ADM/"))));

        // ADM 500 + General bed 2 x 800 + nursing 2 x 300 + Private bed 1 x 3000 + nursing 1 x 500
        assertThat(bill.at("/totals/payable").decimalValue()).isEqualByComparingTo("6200");
        assertThat(bill.at("/totals/received").decimalValue()).isEqualByComparingTo("5000");
        assertThat(bill.at("/totals/balanceDue").decimalValue()).isEqualByComparingTo("1200");
        assertThat(bill.at("/unallocatedAdvance").decimalValue()).isEqualByComparingTo("0");
        assertThat(bill.get("lines").findValuesAsText("description")).contains(
                "Bed charges — GMW-08, General (10 Nov – 11 Nov)", "Bed charges — PVT-03, Private (12 Nov)");

        postJson("/api/v1/billing/bills/" + bill.get("billId").asText() + "/payments",
                Map.of("type", "SETTLEMENT", "payment", Map.of("mode", "CARD", "reference", "AUTH123", "cardLast4", "4242")), CLERK)
                .andExpect(jsonPath("$.totals.balanceDue").value(0.0));
        postJson(base + "/bill/finalize", Map.of(), CLERK)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILL_FINAL"));
    }

    @Test
    void interimRefreshIsIdempotentAndKeepsDeskEnteredServices() throws Exception {
        UUID patient = patient("Lata", "F");
        Instant twoDaysAgo = Instant.now().minus(Duration.ofDays(2));
        Stay stay = admission(patient, twoDaysAgo);
        bedStay(stay.admissionId(), "SPV-04", twoDaysAgo, null);
        serviceOrder(stay.encounterId(), patient, "LAB-CBC");
        String base = "/api/v1/billing/admissions/" + stay.admissionId();

        JsonNode interim = body(postJson(base + "/bill/refresh", Map.of(), CLERK)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTERIM"))
                .andExpect(jsonPath("$.title").value("Interim Bill (not a tax document)"))
                .andExpect(jsonPath("$.billNumber").doesNotExist()));
        // ADM 500 + semi-private bed 2 x 1500 + nursing 2 x 400 + CBC 300
        assertThat(interim.at("/totals/payable").decimalValue()).isEqualByComparingTo("4600");
        String billId = interim.get("billId").asText();

        postJson("/api/v1/billing/bills/" + billId + "/lines", Map.of("serviceCode", "VIS-IPD", "quantity", 2), CLERK)
                .andExpect(jsonPath("$.totals.payable").value(5400.0));
        JsonNode again = body(postJson(base + "/bill/refresh", Map.of(), CLERK).andExpect(status().isOk()));
        assertThat(again.at("/totals/payable").decimalValue()).isEqualByComparingTo("5400");
        assertThat(again.get("lines")).hasSize(interim.get("lines").size() + 1);

        postJson("/api/v1/billing/bills/" + billId + "/lines", Map.of("serviceCode", "NUR-DAY", "quantity", 1), CLERK)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("AUTOMATIC_CHARGE"));
        String bedLine = again.get("lines").get(1).get("lineId").asText();
        perform(delete("/api/v1/billing/bills/" + billId + "/lines/" + bedLine), CLERK)
                .andExpect(status().isUnprocessableEntity());
        perform(get(base + "/bill"), NURSE).andExpect(status().isForbidden());
        perform(get(base + "/bill"), RECEPTION).andExpect(jsonPath("$.billId").value(billId));

        initiateDischarge(stay.admissionId());
        JsonNode finalBill = body(postJson(base + "/bill/finalize", Map.of(), CLERK).andExpect(status().isOk()));
        assertThat(finalBill.get("status").asText()).isEqualTo("FINAL");
        perform(delete("/api/v1/billing/bills/" + billId + "/lines/" + bedLine), CLERK)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BILL_CLOSED"));
    }

    @Test
    void printedBillHasLetterheadAmountInWordsAndEscapedText() throws Exception {
        UUID patient = patient("Ravi <script>alert(1)</script>", "M");
        Visit visit = opdVisit(patient, "GMED", "NEW");
        JsonNode bill = body(postJson("/api/v1/billing/opd-slips", Map.of("visitId", visit.visitId()), RECEPTION));

        String html = perform(get("/api/v1/billing/bills/" + bill.get("billId").asText() + "/print"), RECEPTION)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("Syncra Test Hospital", "GSTIN: 27ABCDE1234F1Z5", "Bill of Supply",
                bill.get("billNumber").asText(), "Rupees Three Hundred Fifty Only", "&lt;script&gt;");
        assertThat(html).doesNotContain("<script>");
        Long printed = jdbc.sql("SELECT count(*) FROM audit.event_log WHERE action = 'PRINT' AND entity_id = :id")
                .param("id", bill.get("billId").asText()).query(Long.class).single();
        assertThat(printed).isEqualTo(1L);
    }

    @Test
    void onlyUnpaidBillsCanBeCancelledAndOnlyByAnAdministrator() throws Exception {
        UUID patient = patient("Kiran", "F");
        Visit visit = opdVisit(patient, "DERM", "NEW");
        JsonNode bill = body(postJson("/api/v1/billing/opd-slips", Map.of("visitId", visit.visitId(),
                "payment", Map.of("mode", "CASH")), RECEPTION));
        String cancel = "/api/v1/billing/bills/" + bill.get("billId").asText() + "/cancel";

        postJson(cancel, Map.of("reason", "Wrong patient"), CLERK).andExpect(status().isForbidden());
        postJson(cancel, Map.of("reason", "Wrong patient"), ADMIN)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENTS_EXIST"));

        postJson("/api/v1/billing/payments/" + bill.at("/receipts/0/paymentId").asText() + "/void",
                Map.of("reason", "Collected in error"), ADMIN)
                .andExpect(jsonPath("$.status").value("VOIDED"));
        postJson(cancel, Map.of("reason", "Wrong patient"), ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.billNumber").value(bill.get("billNumber").asText()));

        // The visit can now be billed again, on a new serial number.
        postJson("/api/v1/billing/opd-slips", Map.of("visitId", visit.visitId()), RECEPTION)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.billNumber").value(Matchers.not(bill.get("billNumber").asText())));
    }

    @Test
    void rateCardsResolveWardClassRates() throws Exception {
        perform(get("/api/v1/billing/tariffs"), CLERK)
                .andExpect(jsonPath("$[?(@.code == 'GENERAL')].isDefault").value(true));
        perform(get("/api/v1/billing/tariffs/GENERAL/rates").param("bedCategory", "PRIVATE"), CLERK)
                .andExpect(jsonPath("$[?(@.serviceCode == 'BED-DAY')].amount").value(3000.0))
                .andExpect(jsonPath("$[?(@.serviceCode == 'CON-NEW')].amount").value(300.0));
        perform(get("/api/v1/billing/tariffs"), NURSE).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/billing/tariffs")).andExpect(status().isUnauthorized());
    }

    @Test
    void aDispenseIsBilledOnceAsAGstInvoiceAtMrp() throws Exception {
        UUID patient = patient("Kiran", "M");
        UUID doctor = doctor();
        UUID encounter = jdbc.sql("""
                INSERT INTO clinical.encounter (patient_id, encounter_type, department_id, attending_doctor_id)
                SELECT :p, 'OPD', id, :d FROM core.department WHERE code = 'GMED' RETURNING id
                """).param("p", patient).param("d", doctor).query(UUID.class).single();
        UUID rx = jdbc.sql("INSERT INTO clinical.prescription (encounter_id, patient_id, prescribed_by) VALUES (:e, :p, :d) RETURNING id")
                .param("e", encounter).param("p", patient).param("d", doctor).query(UUID.class).single();
        jdbc.sql("UPDATE clinical.prescription SET status = 'ISSUED', issued_at = now() WHERE id = :id").param("id", rx).update();
        UUID pharmacist = jdbc.sql("""
                INSERT INTO core.staff (employee_code, full_name, staff_role) VALUES (:c, 'Billing Test Pharmacist', 'PHARMACIST') RETURNING id
                """).param("c", "PH-" + UUID.randomUUID().toString().substring(0, 8)).query(UUID.class).single();
        UUID store = jdbc.sql("SELECT id FROM pharmacy.store WHERE code = 'DISPENSARY'").query(UUID.class).single();
        UUID dispense = jdbc.sql("""
                INSERT INTO pharmacy.dispense (prescription_id, patient_id, store_id, dispensed_by) VALUES (:rx, :p, :s, :by) RETURNING id
                """).param("rx", rx).param("p", patient).param("s", store).param("by", pharmacist).query(UUID.class).single();
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        for (Object[] line : new Object[][] {{"PCM-650", "P" + suffix, "2.24", "12", 10}, {"INS-R", "I" + suffix, "105.00", "5", 1}}) {
            UUID batch = jdbc.sql("""
                    INSERT INTO pharmacy.batch (drug_id, batch_number, expiry_date, mrp, purchase_rate)
                    SELECT id, :b, CURRENT_DATE + 365, CAST(:mrp AS numeric), 1.00 FROM pharmacy.drug WHERE code = :code RETURNING id
                    """).param("b", line[1]).param("mrp", line[2]).param("code", line[0]).query(UUID.class).single();
            jdbc.sql("""
                    INSERT INTO pharmacy.dispense_line (dispense_id, drug_id, batch_id, quantity, mrp, gst_rate, schedule)
                    SELECT :d, drug_id, id, :q, mrp, CAST(:gst AS numeric), 'H' FROM pharmacy.batch WHERE id = :b
                    """).param("d", dispense).param("q", line[4]).param("gst", line[3]).param("b", batch).update();
        }

        var pharmacistCaller = as("test.pharmacist", "PHARMACIST");
        postJson("/api/v1/billing/pharmacy-sales", Map.of("dispenseId", dispense), RECEPTION).andExpect(status().isForbidden());
        JsonNode bill = body(postJson("/api/v1/billing/pharmacy-sales", Map.of("dispenseId", dispense,
                "payment", Map.of("mode", "UPI", "reference", "UTR998877")), pharmacistCaller).andExpect(status().isCreated()));
        assertThat(bill.path("billType").asText()).isEqualTo("PHARMACY");
        assertThat(bill.path("billNumber").asText()).matches("PHB/\\d{2}-\\d{2}/\\d{6}");
        // MRP includes GST: Rs 2.24 at 12% is Rs 2.00 + 0.24; Rs 105 at 5% is Rs 100 + 5.
        JsonNode paracetamol = bill.path("lines").get(bill.path("lines").get(0).path("description").asText().startsWith("Dolo") ? 0 : 1);
        assertThat(paracetamol.path("unitPrice").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(paracetamol.path("gstRate").decimalValue()).isEqualByComparingTo("12");
        assertThat(paracetamol.path("lineTotal").decimalValue()).isEqualByComparingTo("22.40");
        assertThat(paracetamol.path("description").asText()).contains("batch P" + suffix).contains("HSN 3004");
        assertThat(bill.at("/totals/net").decimalValue()).isEqualByComparingTo("127.40");
        assertThat(bill.at("/totals/payable").decimalValue()).isEqualByComparingTo("127");
        assertThat(bill.at("/totals/balanceDue").decimalValue()).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT bill_id::text FROM pharmacy.dispense WHERE id = :id").param("id", dispense).query(String.class).single())
                .isEqualTo(bill.path("billId").asText());
        postJson("/api/v1/billing/pharmacy-sales", Map.of("dispenseId", dispense), pharmacistCaller)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_BILLED"));
    }
}
