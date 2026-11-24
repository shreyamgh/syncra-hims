package com.syncra.hims.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.pharmacy.support.PharmacyTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PharmacyApiIntegrationTest extends PharmacyTestSupport {

    static final LocalDate SOON = LocalDate.now().plusMonths(2).withDayOfMonth(1).minusDays(1);   // end of next month
    static final LocalDate LATER = LocalDate.now().plusYears(1).withDayOfMonth(1).minusDays(1);

    @BeforeEach
    void staff() {
        pharmacist();
    }

    Map<String, Object> line(String code, String batch, LocalDate expiry, int quantity, String mrp) {
        Map<String, Object> line = new HashMap<>(Map.of("drugId", drug(code), "batchNumber", batch, "expiryDate", expiry.toString(),
                "quantity", quantity, "purchaseRate", new BigDecimal(mrp).multiply(new BigDecimal("0.7")).setScale(2, java.math.RoundingMode.HALF_UP),
                "mrp", mrp));
        return line;
    }

    void receive(String invoice, List<Map<String, Object>> lines) throws Exception {
        postJson("/api/v1/pharmacy/receipts", Map.of("supplier", "Medplus Distributors", "invoiceNumber", invoice,
                "invoiceDate", LocalDate.now().toString(), "lines", lines), PHARMACIST).andExpect(status().isCreated());
    }

    BigDecimal onHand(String store, String batch) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(s.quantity), 0) FROM pharmacy.stock s JOIN pharmacy.store st ON st.id = s.store_id
                JOIN pharmacy.batch b ON b.id = s.batch_id WHERE st.code = :store AND b.batch_number = :batch
                """).param("store", store).param("batch", batch).query(BigDecimal.class).single();
    }

    @Test
    void stockFlowsFromReceiptToTheCounterAndIsDispensedEarliestExpiryFirst() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        receive("INV-" + suffix, List.of(line("AMX-500", "AMX-SOON-" + suffix, SOON, 20, "8.50"),
                line("AMX-500", "AMX-LATE-" + suffix, LATER, 100, "8.50"), line("CFX-200", "CFX-" + suffix, LATER, 50, "12.00")));
        // The same supplier invoice twice, and stock that has already expired, are refused.
        postJson("/api/v1/pharmacy/receipts", Map.of("supplier", "Medplus Distributors", "invoiceNumber", "INV-" + suffix,
                "invoiceDate", LocalDate.now().toString(), "lines", List.of(line("PCM-650", "P-" + suffix, LATER, 10, "2.00"))), PHARMACIST)
                .andExpect(status().isConflict());
        postJson("/api/v1/pharmacy/receipts", Map.of("supplier", "Medplus Distributors", "invoiceNumber", "OLD-" + suffix,
                "invoiceDate", LocalDate.now().toString(), "lines", List.of(line("PCM-650", "P-OLD-" + suffix, LocalDate.now().minusDays(1), 10, "2.00"))), PHARMACIST)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EXPIRED_STOCK"));
        assertThat(onHand("MAIN", "AMX-SOON-" + suffix)).isEqualByComparingTo("20");

        // The OPD pharmacy asks the main store; issue takes the earliest expiry first.
        JsonNode requisition = body(postJson("/api/v1/pharmacy/requisitions", Map.of("requestingStore", "DISPENSARY", "issuingStore", "MAIN",
                "lines", List.of(Map.of("drugId", drug("AMX-500"), "quantity", 30), Map.of("drugId", drug("CFX-200"), "quantity", 20))), PHARMACIST)
                .andExpect(status().isCreated()));
        postJson("/api/v1/pharmacy/requisitions/" + requisition.path("id").asText() + "/issue", Map.of(), PHARMACIST)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ISSUED"));
        assertThat(onHand("MAIN", "AMX-SOON-" + suffix)).isEqualByComparingTo("0");
        assertThat(onHand("DISPENSARY", "AMX-SOON-" + suffix)).isEqualByComparingTo("20");
        assertThat(onHand("DISPENSARY", "AMX-LATE-" + suffix)).isEqualByComparingTo("10");

        // At the counter: the written lines are matched to the formulary with a course quantity.
        UUID rx = prescription(new String[] {"Cap Mox 500", "1 cap", "TDS", "5"}, new String[] {"Tab Taxim-O 200", "1 tab", "BD", "5"});
        JsonNode pending = body(get("/api/v1/pharmacy/prescriptions", PHARMACIST).andExpect(status().isOk()));
        JsonNode mine = null;
        for (JsonNode p : pending) {
            if (p.at("/prescription/id").asText().equals(rx.toString())) {
                mine = p;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.at("/items/0/suggested/code").asText()).isEqualTo("AMX-500");
        assertThat(mine.at("/items/0/suggestedQuantity").decimalValue()).isEqualByComparingTo("15");
        assertThat(mine.at("/items/1/suggested/code").asText()).isEqualTo("CFX-200");
        assertThat(mine.at("/items/1/suggestedQuantity").decimalValue()).isEqualByComparingTo("10");

        JsonNode dispense = body(postJson("/api/v1/pharmacy/dispenses", Map.of("prescriptionId", rx, "lines", List.of(
                Map.of("prescriptionItemId", mine.at("/items/0/item/id").asText(), "drugId", drug("AMX-500"), "quantity", 15),
                Map.of("prescriptionItemId", mine.at("/items/1/item/id").asText(), "drugId", drug("CFX-200"), "quantity", 10))), PHARMACIST)
                .andExpect(status().isCreated()));
        assertThat(dispense.path("number").asText()).matches("DSP/\\d{2}-\\d{2}/\\d{6}");
        assertThat(dispense.path("lines").findValuesAsText("batchNumber")).contains("AMX-SOON-" + suffix).doesNotContain("AMX-LATE-" + suffix);
        assertThat(onHand("DISPENSARY", "AMX-SOON-" + suffix)).isEqualByComparingTo("5");
        // The counter shows each line's amount at MRP and the total.
        java.math.BigDecimal lineSum = java.math.BigDecimal.ZERO;
        for (JsonNode line : dispense.path("lines")) {
            assertThat(line.has("amount")).as("line amount in the response").isTrue();
            lineSum = lineSum.add(line.path("amount").decimalValue());
        }
        assertThat(dispense.path("total").decimalValue()).isPositive().isEqualByComparingTo(lineSum);
        postJson("/api/v1/pharmacy/dispenses", Map.of("prescriptionId", rx, "lines", List.of(Map.of("drugId", drug("AMX-500"), "quantity", 1))), PHARMACIST)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_DISPENSED"));

        // Not enough in date at the counter: nothing is taken.
        UUID second = prescription(new String[] {"Cap Mox 500", "1 cap", "TDS", "7"});
        postJson("/api/v1/pharmacy/dispenses", Map.of("prescriptionId", second, "lines", List.of(
                Map.of("drugId", drug("CFX-200"), "quantity", 5), Map.of("drugId", drug("AMX-500"), "quantity", 500))), PHARMACIST)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(onHand("DISPENSARY", "CFX-" + suffix)).isEqualByComparingTo("10");

        // Cefixime is Schedule H1: it is in the register with the prescriber's registration.
        JsonNode register = body(get("/api/v1/pharmacy/registers/schedule-h1?from=" + LocalDate.now() + "&to=" + LocalDate.now(), PHARMACIST)
                .andExpect(status().isOk()));
        JsonNode entry = null;
        for (JsonNode e : register) {
            if (e.path("dispenseNumber").asText().equals(dispense.path("number").asText())) {
                entry = e;
            }
        }
        assertThat(entry).isNotNull();
        assertThat(entry.path("brandName").asText()).isEqualTo("Taxim-O 200");
        assertThat(entry.path("prescriberRegistration").asText()).startsWith("KMC/");
        assertThat(entry.path("patientAddress").asText()).contains("Mysuru");
        assertThat(register.findValuesAsText("brandName")).doesNotContain("Mox 500");
    }

    @Test
    void expiredStockIsNeverDispensedAndLeavesOnlyByAWriteOff() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        receive("EXP-" + suffix, List.of(line("OND-4", "OND-" + suffix, SOON, 40, "6.00")));
        JsonNode requisition = body(postJson("/api/v1/pharmacy/requisitions", Map.of("requestingStore", "DISPENSARY", "issuingStore", "MAIN",
                "lines", List.of(Map.of("drugId", drug("OND-4"), "quantity", 40))), PHARMACIST).andExpect(status().isCreated()));
        postJson("/api/v1/pharmacy/requisitions/" + requisition.path("id").asText() + "/issue", Map.of(), PHARMACIST).andExpect(status().isOk());
        // Time passes: the batch expires on the shelf.
        jdbc.sql("UPDATE pharmacy.batch SET expiry_date = CURRENT_DATE - 1 WHERE batch_number = :b").param("b", "OND-" + suffix).update();

        UUID rx = prescription(new String[] {"Tab Emeset 4", "1 tab", "TDS", "2"});
        postJson("/api/v1/pharmacy/dispenses", Map.of("prescriptionId", rx, "lines", List.of(Map.of("drugId", drug("OND-4"), "quantity", 6))), PHARMACIST)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        JsonNode ondansetron = body(get("/api/v1/pharmacy/drugs?q=Emeset", PHARMACIST)).get(0);
        assertThat(ondansetron.path("expired").decimalValue()).isGreaterThanOrEqualTo(new BigDecimal("40"));

        UUID batch = jdbc.sql("SELECT id FROM pharmacy.batch WHERE batch_number = :b").param("b", "OND-" + suffix).query(UUID.class).single();
        postJson("/api/v1/pharmacy/write-offs", Map.of("store", "DISPENSARY", "batchId", batch, "quantity", 40, "reason", " "), PHARMACIST)
                .andExpect(status().isBadRequest());
        postJson("/api/v1/pharmacy/write-offs", Map.of("store", "DISPENSARY", "batchId", batch, "quantity", 41, "reason", "Expired"), PHARMACIST)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MORE_THAN_ON_HAND"));
        postJson("/api/v1/pharmacy/write-offs", Map.of("store", "DISPENSARY", "batchId", batch, "quantity", 40, "reason", "Expired on shelf"), PHARMACIST)
                .andExpect(status().isNoContent());
        assertThat(onHand("DISPENSARY", "OND-" + suffix)).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT kind FROM pharmacy.stock_movement WHERE batch_id = :b ORDER BY id").param("b", batch).query(String.class).list())
                .containsExactly("RECEIPT", "TRANSFER_OUT", "TRANSFER_IN", "WRITE_OFF");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM pharmacy.stock_movement WHERE batch_id = :b").param("b", batch).update())
                .hasMessageContaining("append-only");
    }

    @Test
    void pharmacistsWorkTheStockAndAdministratorsMayLook() throws Exception {
        postJson("/api/v1/pharmacy/receipts", Map.of("supplier", "X", "invoiceNumber", "Y", "invoiceDate", LocalDate.now().toString(),
                "lines", List.of(line("PCM-650", "B1", LATER, 1, "2.00"))), NURSE).andExpect(status().isForbidden());
        get("/api/v1/pharmacy/drugs", as("test.doctor", "DOCTOR")).andExpect(status().isForbidden());
        get("/api/v1/pharmacy/drugs?q=paracetamol", as("test.admin", "ADMINISTRATOR"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("PCM-650"));
        postJson("/api/v1/pharmacy/write-offs", Map.of("store", "MAIN", "batchId", UUID.randomUUID(), "quantity", 1, "reason", "x"),
                as("test.admin", "ADMINISTRATOR")).andExpect(status().isForbidden());
    }
}
