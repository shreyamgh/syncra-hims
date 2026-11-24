package com.syncra.hims.pharmacy;

import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.pharmacy.DocumentRepository.Created;
import com.syncra.hims.pharmacy.DocumentRepository.Dispense;
import com.syncra.hims.pharmacy.DocumentRepository.RegisterEntry;
import com.syncra.hims.pharmacy.DocumentRepository.Requisition;
import com.syncra.hims.pharmacy.StockRepository.BatchStock;
import com.syncra.hims.pharmacy.StockRepository.DrugStock;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * GET  /api/v1/pharmacy/drugs?q=                       formulary with stock, reorder and expiry flags
 * GET  /api/v1/pharmacy/batches?store=&drugId=         batches on hand
 * POST /api/v1/pharmacy/receipts                       goods receipt into the main store
 * GET  /api/v1/pharmacy/requisitions?status=           requisitions
 * POST /api/v1/pharmacy/requisitions                   the OPD pharmacy asks the main store
 * POST /api/v1/pharmacy/requisitions/{id}/issue        issue FEFO (part-issue allowed)
 * GET  /api/v1/pharmacy/prescriptions                  issued prescriptions waiting at the counter
 * POST /api/v1/pharmacy/dispenses                      dispense FEFO against a prescription
 * GET  /api/v1/pharmacy/dispenses/{id}
 * POST /api/v1/pharmacy/write-offs                     expired or damaged stock out, with a reason
 * GET  /api/v1/pharmacy/registers/schedule-h1?from=&to=
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
public class PharmacyController {

    public record ReceiptLineRequest(@NotNull UUID drugId,
                                     @NotBlank @Pattern(regexp = "^[A-Za-z0-9./-]{1,30}$", message = "letters, digits, '.', '/' or '-'") String batchNumber,
                                     @NotNull LocalDate expiryDate,
                                     @NotNull @DecimalMin("0.01") @DecimalMax("100000") BigDecimal quantity,
                                     @DecimalMin("0") @DecimalMax("100000") BigDecimal freeQuantity,
                                     @NotNull @DecimalMin("0") BigDecimal purchaseRate,
                                     @NotNull @DecimalMin("0.01") BigDecimal mrp) {
    }

    public record ReceiptRequest(@NotBlank @Size(max = 150) String supplier, @NotBlank @Size(max = 40) String invoiceNumber,
                                 @NotNull LocalDate invoiceDate, @NotEmpty @Size(max = 100) List<@Valid ReceiptLineRequest> lines) {
    }

    public record QuantityLine(@NotNull UUID drugId, @NotNull @DecimalMin("0.01") @DecimalMax("100000") BigDecimal quantity) {
    }

    public record RequisitionRequest(@NotBlank String requestingStore, @NotBlank String issuingStore,
                                     @NotEmpty @Size(max = 100) List<@Valid QuantityLine> lines) {
    }

    public record DispenseLineRequest(UUID prescriptionItemId, @NotNull UUID drugId,
                                      @NotNull @DecimalMin("0.01") @DecimalMax("10000") BigDecimal quantity) {
    }

    public record DispenseRequest(@NotNull UUID prescriptionId, @NotEmpty @Size(max = 40) List<@Valid DispenseLineRequest> lines) {
    }

    public record WriteOffRequest(@NotBlank String store, @NotNull UUID batchId,
                                  @NotNull @DecimalMin("0.01") BigDecimal quantity, @NotBlank @Size(max = 200) String reason) {
    }

    private final PharmacyService pharmacy;

    public PharmacyController(PharmacyService pharmacy) {
        this.pharmacy = pharmacy;
    }

    @GetMapping("/drugs")
    @PreAuthorize(Roles.VIEW)
    public List<DrugView> drugs(@RequestParam(required = false) String q) {
        return pharmacy.drugs(q).stream().map(DrugView::of).toList();
    }

    @GetMapping("/batches")
    @PreAuthorize(Roles.VIEW)
    public List<BatchStock> batches(@RequestParam(required = false) String store, @RequestParam(required = false) UUID drugId) {
        return pharmacy.batches(store, drugId);
    }

    @PostMapping("/receipts")
    @PreAuthorize(Roles.PHARMACIST)
    @ResponseStatus(HttpStatus.CREATED)
    public Created receive(@Valid @RequestBody ReceiptRequest r) {
        return pharmacy.receive(r.supplier(), r.invoiceNumber(), r.invoiceDate(), r.lines().stream()
                .map(l -> new PharmacyService.ReceiptLine(l.drugId(), l.batchNumber(), l.expiryDate(), l.quantity(),
                        l.freeQuantity(), l.purchaseRate(), l.mrp())).toList());
    }

    @GetMapping("/requisitions")
    @PreAuthorize(Roles.VIEW)
    public List<Requisition> requisitions(@RequestParam(defaultValue = "REQUESTED,PARTIAL,ISSUED") List<String> status) {
        return pharmacy.requisitions(status);
    }

    @PostMapping("/requisitions")
    @PreAuthorize(Roles.PHARMACIST)
    @ResponseStatus(HttpStatus.CREATED)
    public Requisition requisition(@Valid @RequestBody RequisitionRequest r) {
        Map<UUID, BigDecimal> lines = new LinkedHashMap<>();
        for (QuantityLine line : r.lines()) {
            if (lines.put(line.drugId(), line.quantity()) != null) {
                throw ApiException.unprocessable("DUPLICATE_LINE", "A medicine appears twice in the requisition");
            }
        }
        return pharmacy.requisition(r.requestingStore(), r.issuingStore(), lines);
    }

    @PostMapping("/requisitions/{id}/issue")
    @PreAuthorize(Roles.PHARMACIST)
    public Requisition issue(@PathVariable UUID id) {
        return pharmacy.issue(id);
    }

    @GetMapping("/prescriptions")
    @PreAuthorize(Roles.VIEW)
    public List<PharmacyService.PendingPrescription> pending() {
        return pharmacy.pending();
    }

    @PostMapping("/dispenses")
    @PreAuthorize(Roles.PHARMACIST)
    @ResponseStatus(HttpStatus.CREATED)
    public Dispense dispense(@Valid @RequestBody DispenseRequest r) {
        return pharmacy.dispense(r.prescriptionId(), r.lines().stream()
                .map(l -> new PharmacyService.DispenseInput(l.prescriptionItemId(), l.drugId(), l.quantity())).toList());
    }

    @GetMapping("/dispenses/{id}")
    @PreAuthorize(Roles.VIEW)
    public Dispense dispense(@PathVariable UUID id) {
        return pharmacy.dispense(id);
    }

    @PostMapping("/write-offs")
    @PreAuthorize(Roles.PHARMACIST)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void writeOff(@Valid @RequestBody WriteOffRequest r) {
        pharmacy.writeOff(r.store(), r.batchId(), r.quantity(), r.reason());
    }

    @GetMapping("/registers/schedule-h1")
    @PreAuthorize(Roles.VIEW)
    public List<RegisterEntry> scheduleH1(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from) || from.plusYears(1).isBefore(to)) {
            throw ApiException.unprocessable("BAD_PERIOD", "Choose a period of up to one year");
        }
        return pharmacy.scheduleH1(from, to);
    }

    /** A medicine as the desk shows it, with its alerts. */
    public record DrugView(UUID id, String code, String brandName, String strength, String dosageForm, String schedule,
                           boolean highAlert, BigDecimal gstRate, String unit, int reorderLevel, String generics,
                           BigDecimal mainStore, BigDecimal dispensary, BigDecimal nearExpiry, BigDecimal expired,
                           LocalDate earliestExpiry, boolean belowReorder) {
        static DrugView of(DrugStock d) {
            return new DrugView(d.id(), d.code(), d.brandName(), d.strength(), d.dosageForm(), d.schedule(), d.highAlert(),
                    d.gstRate(), d.unit(), d.reorderLevel(), d.generics(), d.mainStore(), d.dispensary(), d.nearExpiry(),
                    d.expired(), d.earliestExpiry(), d.belowReorder());
        }
    }
}
