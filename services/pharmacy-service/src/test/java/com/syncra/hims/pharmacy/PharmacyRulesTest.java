package com.syncra.hims.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncra.hims.pharmacy.DocumentRepository.PrescriptionItem;
import com.syncra.hims.pharmacy.StockRepository.DrugStock;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PharmacyRulesTest {

    static DrugStock drug(String code, String brand, String form, String generics, String stock) {
        return new DrugStock(UUID.randomUUID(), code, brand, null, form, "H", false, new BigDecimal("12"), "tablet", 0, generics,
                new BigDecimal(stock), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
    }

    static PrescriptionItem item(String text, String dose, String frequency, Integer days) {
        return new PrescriptionItem(UUID.randomUUID(), 1, null, text, dose, frequency, days, null);
    }

    final List<DrugStock> formulary = List.of(drug("PCM-650", "Dolo 650", "TABLET", "Paracetamol", "100"),
            drug("PCM-500", "Calpol 500", "TABLET", "Paracetamol", "900"), drug("SAL-INH", "Asthalin inhaler", "INHALER", "Salbutamol", "5"));

    @Test
    void matchesTheBrandWrittenElseTheGenericWithMostStock() {
        assertThat(PharmacyService.pendingItem(item("Tab Dolo 650", "1 tab", "TDS", 3), formulary).suggested().code()).isEqualTo("PCM-650");
        assertThat(PharmacyService.pendingItem(item("Tab Paracetamol 500 mg", "1 tab", "TDS", 3), formulary).suggested().code()).isEqualTo("PCM-500");
        assertThat(PharmacyService.pendingItem(item("Syp Something", null, "BD", 3), formulary).suggested()).isNull();
    }

    @Test
    void suggestsTheCourseForTabletsAndOneUnitOtherwise() {
        assertThat(PharmacyService.suggestedQuantity(item("Tab Dolo 650", "1 tab", "TDS", 5), formulary.get(0))).isEqualByComparingTo("15");
        assertThat(PharmacyService.suggestedQuantity(item("Tab Dolo 650", "2 tabs", "BD", 3), formulary.get(0))).isEqualByComparingTo("12");
        assertThat(PharmacyService.suggestedQuantity(item("Tab Dolo 650", "1 tab", "SOS", 3), formulary.get(0))).isNull();
        assertThat(PharmacyService.suggestedQuantity(item("Asthalin inhaler", "2 puffs", "SOS", null), formulary.get(2))).isEqualByComparingTo("1");
    }
}
