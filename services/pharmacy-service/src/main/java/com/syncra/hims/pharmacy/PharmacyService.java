package com.syncra.hims.pharmacy;

import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.security.CurrentUser;
import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.pharmacy.DocumentRepository.Created;
import com.syncra.hims.pharmacy.DocumentRepository.Dispense;
import com.syncra.hims.pharmacy.DocumentRepository.Prescription;
import com.syncra.hims.pharmacy.DocumentRepository.PrescriptionItem;
import com.syncra.hims.pharmacy.DocumentRepository.Requisition;
import com.syncra.hims.pharmacy.DocumentRepository.RequisitionLine;
import com.syncra.hims.pharmacy.StockRepository.Allocation;
import com.syncra.hims.pharmacy.StockRepository.DrugStock;
import com.syncra.hims.pharmacy.StockRepository.Store;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pharmacy rules: receive, transfer, dispense and write off stock, always by batch and expiry. */
@Service
public class PharmacyService {

    public static final String MAIN = "MAIN";
    public static final String DISPENSARY = "DISPENSARY";

    public record ReceiptLine(UUID drugId, String batchNumber, LocalDate expiryDate, BigDecimal quantity, BigDecimal freeQuantity,
                              BigDecimal purchaseRate, BigDecimal mrp) {
    }

    public record DispenseInput(UUID prescriptionItemId, UUID drugId, BigDecimal quantity) {
    }

    /** A prescription waiting at the counter, with a suggested medicine and quantity for each line. */
    public record PendingPrescription(Prescription prescription, Integer ageYears, List<PendingItem> items) {
    }

    public record PendingItem(PrescriptionItem item, DrugStock suggested, BigDecimal suggestedQuantity) {
    }

    private static final Map<String, Integer> PER_DAY = Map.of("OD", 1, "HS", 1, "BD", 2, "TDS", 3, "QID", 4,
            "Q8H", 3, "Q6H", 4, "Q4H", 6);
    private static final Pattern COUNT = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*(tab|tabs|tablet|tablets|cap|caps|capsule|capsules)?\\b",
            Pattern.CASE_INSENSITIVE);

    private final StockRepository stock;
    private final DocumentRepository documents;
    private final PharmacyProperties properties;
    private final HospitalTime time;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final JdbcClient jdbc;

    public PharmacyService(StockRepository stock, DocumentRepository documents, PharmacyProperties properties, HospitalTime time,
                           CurrentUser currentUser, AuditService audit, JdbcClient jdbc) {
        this.stock = stock;
        this.documents = documents;
        this.properties = properties;
        this.time = time;
        this.currentUser = currentUser;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    public List<DrugStock> drugs(String query) {
        LocalDate today = time.today();
        return stock.drugs(query, today, today.plusDays(properties.nearExpiryDays()));
    }

    public List<StockRepository.BatchStock> batches(String storeCode, UUID drugId) {
        return stock.batches(storeCode, drugId);
    }

    // ------------------------------------------------------------------ goods receipt

    @Transactional
    public Created receive(String supplier, String invoiceNumber, LocalDate invoiceDate, List<ReceiptLine> lines) {
        LocalDate today = time.today();
        if (invoiceDate.isAfter(today)) {
            throw ApiException.unprocessable("FUTURE_INVOICE", "The invoice date is in the future");
        }
        Store main = store(MAIN);
        UUID staff = staff();
        Created receipt = documents.receipt(main.id(), supplier.trim(), invoiceNumber.trim(), invoiceDate, staff);
        for (ReceiptLine line : lines) {
            DrugStock drug = requireDrug(line.drugId());
            if (!line.expiryDate().isAfter(today)) {
                throw ApiException.unprocessable("EXPIRED_STOCK", drug.brandName() + " batch " + line.batchNumber()
                        + " has expired: return it to the supplier");
            }
            if (line.purchaseRate().compareTo(line.mrp()) > 0) {
                throw ApiException.unprocessable("RATE_ABOVE_MRP", drug.brandName() + ": the purchase rate is above the MRP");
            }
            String batchNumber = line.batchNumber().trim().toUpperCase(Locale.ROOT);
            UUID batchId = stock.batch(drug.id(), batchNumber).map(existing -> {
                if (!existing.expiryDate().equals(line.expiryDate()) || existing.mrp().compareTo(line.mrp()) != 0) {
                    throw ApiException.conflict("BATCH_MISMATCH", drug.brandName() + " batch " + batchNumber
                            + " is already recorded with expiry " + existing.expiryDate() + " and MRP " + existing.mrp());
                }
                return existing.id();
            }).orElseGet(() -> stock.insertBatch(drug.id(), batchNumber, line.expiryDate(), line.mrp(), line.purchaseRate()));
            BigDecimal free = line.freeQuantity() == null ? BigDecimal.ZERO : line.freeQuantity();
            documents.receiptLine(receipt.id(), batchId, line.quantity(), free);
            BigDecimal total = line.quantity().add(free);
            stock.change(main.id(), batchId, total);
            stock.movement(main.id(), batchId, total, "RECEIPT", receipt.id(), null, staff);
        }
        audit.record(Action.CREATE, "goods_receipt", receipt.id(), null, Map.of("lines", lines.size(), "number", receipt.number()));
        return receipt;
    }

    // ------------------------------------------------------------------ requisitions

    @Transactional
    public Requisition requisition(String requestingStore, String issuingStore, Map<UUID, BigDecimal> lines) {
        Store from = store(requestingStore);
        Store to = store(issuingStore);
        if (from.id().equals(to.id())) {
            throw ApiException.unprocessable("SAME_STORE", "A store cannot ask itself for stock");
        }
        Created created = documents.requisition(from.id(), to.id(), staff());
        lines.forEach((drugId, quantity) -> {
            requireDrug(drugId);
            documents.requisitionLine(created.id(), drugId, quantity);
        });
        return documents.requisition(created.id()).orElseThrow();
    }

    public List<Requisition> requisitions(List<String> statuses) {
        return documents.requisitions(statuses);
    }

    /** Issues a requisition from the issuing store, earliest expiry first; what cannot be found stays unissued. */
    @Transactional
    public Requisition issue(UUID requisitionId) {
        Requisition requisition = documents.lockRequisition(requisitionId)
                .orElseThrow(() -> ApiException.notFound("Requisition", requisitionId));
        if (!"REQUESTED".equals(requisition.status())) {
            throw ApiException.conflict("REQUISITION_CLOSED", "This requisition is " + requisition.status().toLowerCase(Locale.ROOT));
        }
        Store issuing = store(requisition.issuingStore());
        Store requesting = store(requisition.requestingStore());
        UUID staff = staff();
        LocalDate today = time.today();
        boolean complete = true;
        BigDecimal anything = BigDecimal.ZERO;
        for (RequisitionLine line : requisition.lines()) {
            BigDecimal given = BigDecimal.ZERO;
            for (Allocation a : stock.allocate(issuing.id(), line.drugId(), line.requested(), today)) {
                stock.change(issuing.id(), a.batchId(), a.quantity().negate());
                stock.movement(issuing.id(), a.batchId(), a.quantity().negate(), "TRANSFER_OUT", requisitionId, null, staff);
                stock.change(requesting.id(), a.batchId(), a.quantity());
                stock.movement(requesting.id(), a.batchId(), a.quantity(), "TRANSFER_IN", requisitionId, null, staff);
                given = given.add(a.quantity());
            }
            documents.issued(line.id(), given);
            complete &= given.compareTo(line.requested()) >= 0;
            anything = anything.add(given);
        }
        if (anything.signum() == 0) {
            throw ApiException.conflict("NOTHING_TO_ISSUE", "The " + issuing.name().toLowerCase(Locale.ROOT)
                    + " has none of these medicines in date");
        }
        documents.requisitionStatus(requisitionId, complete ? "ISSUED" : "PARTIAL", staff, time.now());
        return documents.requisition(requisitionId).orElseThrow();
    }

    // ------------------------------------------------------------------ dispensing

    public List<PendingPrescription> pending() {
        LocalDate today = time.today();
        List<DrugStock> formulary = stock.drugs(null, today, today.plusDays(properties.nearExpiryDays()));
        return documents.pending(time.now().minus(Duration.ofDays(properties.pendingDays()))).stream()
                .map(rx -> new PendingPrescription(rx, rx.dateOfBirth() == null ? null : time.ageInYears(rx.dateOfBirth()),
                        documents.items(rx.id()).stream().map(item -> pendingItem(item, formulary)).toList()))
                .toList();
    }

    @Transactional
    public Dispense dispense(UUID prescriptionId, List<DispenseInput> lines) {
        Prescription rx = documents.lockPrescription(prescriptionId)
                .orElseThrow(() -> ApiException.notFound("Prescription", prescriptionId));
        if (!"ISSUED".equals(documents.prescriptionStatus(prescriptionId))) {
            throw ApiException.conflict("PRESCRIPTION_NOT_ISSUED", "Only an issued (signed) prescription can be dispensed");
        }
        if (documents.dispensed(prescriptionId)) {
            throw ApiException.conflict("ALREADY_DISPENSED", "This prescription has already been dispensed");
        }
        List<UUID> items = documents.items(prescriptionId).stream().map(PrescriptionItem::id).toList();
        Store dispensary = store(DISPENSARY);
        UUID staff = staff();
        LocalDate today = time.today();
        Created dispense = documents.dispense(prescriptionId, rx.patientId(), dispensary.id(), staff);
        for (DispenseInput line : lines) {
            DrugStock drug = requireDrug(line.drugId());
            if ("X".equals(drug.schedule())) {
                throw ApiException.unprocessable("SCHEDULE_X", drug.brandName()
                        + " is a Schedule X drug: it is dispensed only with the narcotics register, outside this system");
            }
            if (line.prescriptionItemId() != null && !items.contains(line.prescriptionItemId())) {
                throw ApiException.unprocessable("NOT_ON_PRESCRIPTION", "A line does not belong to this prescription");
            }
            List<Allocation> taken = stock.allocate(dispensary.id(), drug.id(), line.quantity(), today);
            BigDecimal available = taken.stream().map(Allocation::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (available.compareTo(line.quantity()) < 0) {
                throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", drug.brandName() + ": only "
                        + available.stripTrailingZeros().toPlainString() + " " + drug.unit() + " in date at the OPD pharmacy",
                        Map.of("drugId", drug.id(), "available", available));
            }
            for (Allocation a : taken) {
                stock.change(dispensary.id(), a.batchId(), a.quantity().negate());
                stock.movement(dispensary.id(), a.batchId(), a.quantity().negate(), "DISPENSE", dispense.id(), null, staff);
                documents.dispenseLine(dispense.id(), line.prescriptionItemId(), drug.id(), a.batchId(), a.quantity(), a.mrp(),
                        drug.gstRate(), drug.schedule());
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("number", dispense.number());
        details.put("lines", lines.size());
        audit.record(Action.CREATE, "dispense", dispense.id(), rx.patientId(), details);
        return documents.dispense(dispense.id()).orElseThrow();
    }

    public Dispense dispense(UUID id) {
        return documents.dispense(id).orElseThrow(() -> ApiException.notFound("Dispense", id));
    }

    // ------------------------------------------------------------------ write-off and register

    @Transactional
    public void writeOff(String storeCode, UUID batchId, BigDecimal quantity, String reason) {
        Store store = store(storeCode);
        stock.batch(batchId).orElseThrow(() -> ApiException.notFound("Batch", batchId));
        BigDecimal onHand = stock.onHand(store.id(), batchId);
        if (onHand.compareTo(quantity) < 0) {
            throw ApiException.unprocessable("MORE_THAN_ON_HAND", "Only " + onHand.stripTrailingZeros().toPlainString()
                    + " of this batch are in the " + store.name().toLowerCase(Locale.ROOT));
        }
        UUID staff = staff();
        stock.change(store.id(), batchId, quantity.negate());
        stock.movement(store.id(), batchId, quantity.negate(), "WRITE_OFF", null, reason.trim(), staff);
        audit.record(Action.UPDATE, "stock", batchId, null, Map.of("action", "write_off", "store", store.code()));
    }

    public List<DocumentRepository.RegisterEntry> scheduleH1(LocalDate from, LocalDate to) {
        return documents.scheduleH1(from.atStartOfDay(time.zone()).toInstant(), to.plusDays(1).atStartOfDay(time.zone()).toInstant());
    }

    // ------------------------------------------------------------------ helpers

    /** The formulary item a written line most likely means, and how many units the course needs. */
    static PendingItem pendingItem(PrescriptionItem item, List<DrugStock> formulary) {
        String text = item.drugText().toLowerCase(Locale.ROOT);
        Optional<DrugStock> match = item.drugId() == null ? Optional.empty()
                : formulary.stream().filter(d -> d.id().equals(item.drugId())).findFirst();
        if (match.isEmpty()) {
            match = formulary.stream().filter(d -> text.contains(d.brandName().toLowerCase(Locale.ROOT)))
                    .max(Comparator.comparingInt(d -> d.brandName().length()));
        }
        if (match.isEmpty()) {
            match = formulary.stream().filter(d -> d.generics() != null && text.contains(d.generics().toLowerCase(Locale.ROOT)))
                    .max(Comparator.comparing(DrugStock::usable));
        }
        return new PendingItem(item, match.orElse(null), suggestedQuantity(item, match.orElse(null)));
    }

    /** Tablets/capsules: dose × times a day × days; other forms: one unit (a bottle, inhaler, vial). */
    static BigDecimal suggestedQuantity(PrescriptionItem item, DrugStock drug) {
        if (item.quantity() != null) {
            return item.quantity();
        }
        if (drug == null || !List.of("TABLET", "CAPSULE").contains(drug.dosageForm())) {
            return BigDecimal.ONE;
        }
        Integer perDay = item.frequency() == null ? null : PER_DAY.get(item.frequency());
        if (perDay == null || item.durationDays() == null) {
            return null;
        }
        BigDecimal dose = BigDecimal.ONE;
        if (item.dose() != null) {
            Matcher m = COUNT.matcher(item.dose());
            if (m.find()) {
                dose = new BigDecimal(m.group(1));
            }
        }
        return dose.multiply(BigDecimal.valueOf((long) perDay * item.durationDays()));
    }

    private DrugStock requireDrug(UUID id) {
        LocalDate today = time.today();
        return stock.drug(id, today, today.plusDays(properties.nearExpiryDays()))
                .orElseThrow(() -> ApiException.notFound("Medicine", id));
    }

    private Store store(String code) {
        return stock.store(code).orElseThrow(() -> ApiException.notFound("Store", code));
    }

    private UUID staff() {
        return jdbc.sql("SELECT id FROM core.staff WHERE iam_subject = :s AND is_active").param("s", currentUser.subject())
                .query(UUID.class).optional().orElseThrow(() -> ApiException.unprocessable("STAFF_RECORD_REQUIRED",
                        "Your login is not linked to a staff record; ask the administrator to link it"));
    }
}
