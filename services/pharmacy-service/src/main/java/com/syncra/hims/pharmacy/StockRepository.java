package com.syncra.hims.pharmacy;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Formulary, stores, batches, stock on hand and the stock ledger. */
@Repository
public class StockRepository {

    public record Store(UUID id, String code, String name, String kind) {
    }

    /** A medicine with its stock (usable stock excludes expired batches). */
    public record DrugStock(UUID id, String code, String brandName, String strength, String dosageForm, String schedule,
                            boolean highAlert, BigDecimal gstRate, String unit, int reorderLevel, String generics,
                            BigDecimal mainStore, BigDecimal dispensary, BigDecimal nearExpiry, BigDecimal expired,
                            LocalDate earliestExpiry) {

        public BigDecimal usable() {
            return mainStore.add(dispensary);
        }

        public boolean belowReorder() {
            return reorderLevel > 0 && usable().compareTo(BigDecimal.valueOf(reorderLevel)) < 0;
        }
    }

    public record BatchStock(UUID batchId, UUID drugId, String drugCode, String brandName, String batchNumber,
                             LocalDate expiryDate, BigDecimal mrp, String storeCode, BigDecimal quantity) {
    }

    public record Batch(UUID id, UUID drugId, String batchNumber, LocalDate expiryDate, BigDecimal mrp, BigDecimal purchaseRate) {
    }

    /** What FEFO took from one batch. */
    public record Allocation(UUID batchId, String batchNumber, LocalDate expiryDate, BigDecimal mrp, BigDecimal quantity) {
    }

    private final JdbcClient jdbc;

    public StockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Store> store(String code) {
        return jdbc.sql("SELECT id, code, name, kind FROM pharmacy.store WHERE code = :c").param("c", code)
                .query((rs, n) -> new Store(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("kind"))).optional();
    }

    public List<DrugStock> drugs(String query, LocalDate today, LocalDate nearUntil) {
        String like = query == null || query.isBlank() ? null : "%" + query.trim().replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("""
                SELECT d.id, d.code, d.brand_name, d.strength, d.dosage_form, d.schedule, d.is_high_alert, d.gst_rate,
                       d.dispensing_unit, d.reorder_level,
                       (SELECT string_agg(g.name, ' + ' ORDER BY g.name) FROM pharmacy.drug_component c
                        JOIN pharmacy.generic g ON g.id = c.generic_id WHERE c.drug_id = d.id) AS generics,
                       COALESCE(SUM(s.quantity) FILTER (WHERE st.kind = 'MAIN' AND b.expiry_date >= :today), 0) AS main_store,
                       COALESCE(SUM(s.quantity) FILTER (WHERE st.kind = 'DISPENSARY' AND b.expiry_date >= :today), 0) AS dispensary,
                       COALESCE(SUM(s.quantity) FILTER (WHERE b.expiry_date >= :today AND b.expiry_date < :near), 0) AS near_expiry,
                       COALESCE(SUM(s.quantity) FILTER (WHERE b.expiry_date < :today), 0) AS expired,
                       MIN(b.expiry_date) FILTER (WHERE s.quantity > 0 AND b.expiry_date >= :today) AS earliest
                FROM pharmacy.drug d
                LEFT JOIN pharmacy.batch b ON b.drug_id = d.id
                LEFT JOIN pharmacy.stock s ON s.batch_id = b.id AND s.quantity > 0
                LEFT JOIN pharmacy.store st ON st.id = s.store_id
                WHERE d.is_active AND (CAST(:like AS text) IS NULL OR d.code ILIKE :like OR d.brand_name ILIKE :like
                       OR EXISTS (SELECT 1 FROM pharmacy.drug_component c JOIN pharmacy.generic g ON g.id = c.generic_id
                                  WHERE c.drug_id = d.id AND g.name ILIKE :like))
                GROUP BY d.id
                ORDER BY d.brand_name
                LIMIT 200
                """).param("like", like).param("today", today).param("near", nearUntil)
                .query(StockRepository::drugStock).list();
    }

    public Optional<DrugStock> drug(UUID id, LocalDate today, LocalDate nearUntil) {
        return drugs(null, today, nearUntil).stream().filter(d -> d.id().equals(id)).findFirst();
    }

    public List<BatchStock> batches(String storeCode, UUID drugId) {
        return jdbc.sql("""
                SELECT b.id, b.drug_id, d.code, d.brand_name, b.batch_number, b.expiry_date, b.mrp, st.code AS store, s.quantity
                FROM pharmacy.stock s JOIN pharmacy.batch b ON b.id = s.batch_id JOIN pharmacy.drug d ON d.id = b.drug_id
                JOIN pharmacy.store st ON st.id = s.store_id
                WHERE s.quantity > 0 AND (CAST(:store AS text) IS NULL OR st.code = :store)
                  AND (CAST(:drug AS uuid) IS NULL OR b.drug_id = :drug)
                ORDER BY b.expiry_date, d.brand_name
                LIMIT 500
                """).param("store", storeCode).param("drug", drugId)
                .query((rs, n) -> new BatchStock(rs.getObject("id", UUID.class), rs.getObject("drug_id", UUID.class),
                        rs.getString("code"), rs.getString("brand_name"), rs.getString("batch_number"),
                        rs.getDate("expiry_date").toLocalDate(), rs.getBigDecimal("mrp"), rs.getString("store"),
                        rs.getBigDecimal("quantity"))).list();
    }

    public Optional<Batch> batch(UUID drugId, String batchNumber) {
        return jdbc.sql("""
                SELECT id, drug_id, batch_number, expiry_date, mrp, purchase_rate FROM pharmacy.batch
                WHERE drug_id = :d AND batch_number = :n
                """).param("d", drugId).param("n", batchNumber).query(StockRepository::batch).optional();
    }

    public Optional<Batch> batch(UUID id) {
        return jdbc.sql("SELECT id, drug_id, batch_number, expiry_date, mrp, purchase_rate FROM pharmacy.batch WHERE id = :id")
                .param("id", id).query(StockRepository::batch).optional();
    }

    public UUID insertBatch(UUID drugId, String batchNumber, LocalDate expiry, BigDecimal mrp, BigDecimal purchaseRate) {
        return jdbc.sql("""
                INSERT INTO pharmacy.batch (drug_id, batch_number, expiry_date, mrp, purchase_rate)
                VALUES (:d, :n, :e, :m, :p) RETURNING id
                """).param("d", drugId).param("n", batchNumber).param("e", expiry).param("m", mrp).param("p", purchaseRate)
                .query(UUID.class).single();
    }

    /**
     * First-expiry-first-out: the store's usable batches of a drug (not expired), earliest expiry
     * first, locked, taken until the quantity is met. Returns what was found, which may be short.
     */
    public List<Allocation> allocate(UUID storeId, UUID drugId, BigDecimal quantity, LocalDate today) {
        List<Allocation> rows = jdbc.sql("""
                SELECT s.batch_id, b.batch_number, b.expiry_date, b.mrp, s.quantity
                FROM pharmacy.stock s JOIN pharmacy.batch b ON b.id = s.batch_id
                WHERE s.store_id = :store AND b.drug_id = :drug AND s.quantity > 0 AND b.expiry_date >= :today
                ORDER BY b.expiry_date, b.created_at
                FOR UPDATE OF s
                """).param("store", storeId).param("drug", drugId).param("today", today)
                .query((rs, n) -> new Allocation(rs.getObject("batch_id", UUID.class), rs.getString("batch_number"),
                        rs.getDate("expiry_date").toLocalDate(), rs.getBigDecimal("mrp"), rs.getBigDecimal("quantity")))
                .list();
        List<Allocation> taken = new ArrayList<>();
        BigDecimal remaining = quantity;
        for (Allocation row : rows) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal take = row.quantity().min(remaining);
            taken.add(new Allocation(row.batchId(), row.batchNumber(), row.expiryDate(), row.mrp(), take));
            remaining = remaining.subtract(take);
        }
        return taken;
    }

    /**
     * Adds (or, negative, takes) stock; the database refuses to go below zero. Taking is a plain
     * UPDATE: an upsert would check the proposed (negative) row before finding the existing one.
     */
    public void change(UUID storeId, UUID batchId, BigDecimal delta) {
        if (delta.signum() < 0) {
            int updated = jdbc.sql("""
                    UPDATE pharmacy.stock SET quantity = quantity + :q, updated_at = now()
                    WHERE store_id = :store AND batch_id = :batch
                    """).param("store", storeId).param("batch", batchId).param("q", delta).update();
            if (updated != 1) {
                throw new IllegalStateException("No stock row to take from");
            }
            return;
        }
        jdbc.sql("""
                INSERT INTO pharmacy.stock AS s (store_id, batch_id, quantity) VALUES (:store, :batch, :q)
                ON CONFLICT (store_id, batch_id) DO UPDATE SET quantity = s.quantity + EXCLUDED.quantity, updated_at = now()
                """).param("store", storeId).param("batch", batchId).param("q", delta).update();
    }

    public BigDecimal onHand(UUID storeId, UUID batchId) {
        return jdbc.sql("SELECT quantity FROM pharmacy.stock WHERE store_id = :s AND batch_id = :b FOR UPDATE")
                .param("s", storeId).param("b", batchId).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    public void movement(UUID storeId, UUID batchId, BigDecimal quantity, String kind, UUID referenceId, String reason,
                         UUID staffId) {
        jdbc.sql("""
                INSERT INTO pharmacy.stock_movement (store_id, batch_id, quantity, kind, reference_id, reason, staff_id)
                VALUES (:store, :batch, :q, :kind, :ref, :reason, :staff)
                """).param("store", storeId).param("batch", batchId).param("q", quantity).param("kind", kind)
                .param("ref", referenceId).param("reason", reason).param("staff", staffId).update();
    }

    private static DrugStock drugStock(ResultSet rs, int row) throws SQLException {
        return new DrugStock(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("brand_name"),
                rs.getString("strength"), rs.getString("dosage_form"), rs.getString("schedule"), rs.getBoolean("is_high_alert"),
                rs.getBigDecimal("gst_rate"), rs.getString("dispensing_unit"), rs.getInt("reorder_level"), rs.getString("generics"),
                rs.getBigDecimal("main_store"), rs.getBigDecimal("dispensary"), rs.getBigDecimal("near_expiry"),
                rs.getBigDecimal("expired"), rs.getDate("earliest") == null ? null : rs.getDate("earliest").toLocalDate());
    }

    private static Batch batch(ResultSet rs, int row) throws SQLException {
        return new Batch(rs.getObject("id", UUID.class), rs.getObject("drug_id", UUID.class), rs.getString("batch_number"),
                rs.getDate("expiry_date").toLocalDate(), rs.getBigDecimal("mrp"), rs.getBigDecimal("purchase_rate"));
    }
}
