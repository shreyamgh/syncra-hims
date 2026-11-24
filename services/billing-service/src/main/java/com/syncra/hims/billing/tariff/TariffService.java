package com.syncra.hims.billing.tariff;

import com.syncra.hims.common.web.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Tariff look-ups. Prices come from {@code billing.resolve_rate()}: the rate for the
 * patient's ward class wins over the plan's "ALL" rate, and the rate valid on the
 * service date applies (for a bed stay: the date the stay began).
 */
@Service
public class TariffService {

    /** Ward-class value meaning "the same price everywhere" (OPD, labs, imaging). */
    public static final String ALL = "ALL";

    private final JdbcClient jdbc;

    public TariffService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Plan> plans() {
        return jdbc.sql("SELECT id, code, name, payer_type, is_default FROM billing.tariff_plan WHERE is_active ORDER BY name")
                .query(TariffService::plan).list();
    }

    public Plan plan(UUID planId) {
        return jdbc.sql("SELECT id, code, name, payer_type, is_default FROM billing.tariff_plan WHERE id = :id")
                .param("id", planId).query(TariffService::plan).optional()
                .orElseThrow(() -> ApiException.notFound("Tariff plan", planId));
    }

    /** Plan by code, or the hospital's default plan when no code is given. */
    public Plan requirePlan(String code) {
        Optional<Plan> plan = code == null || code.isBlank()
                ? jdbc.sql("SELECT id, code, name, payer_type, is_default FROM billing.tariff_plan WHERE is_default AND is_active")
                        .query(TariffService::plan).optional()
                : jdbc.sql("SELECT id, code, name, payer_type, is_default FROM billing.tariff_plan WHERE code = :code AND is_active")
                        .param("code", code.trim().toUpperCase()).query(TariffService::plan).optional();
        return plan.orElseThrow(() -> ApiException.notFound("Tariff plan", code == null ? "(default)" : code));
    }

    public Optional<Rate> rate(UUID planId, String serviceCode, String bedCategory, LocalDate on) {
        return jdbc.sql("""
                SELECT s.id, s.code, s.name, s.category, s.sac_code, s.charge_unit, r.tariff_rate_id, r.amount, r.gst_rate
                FROM billing.service AS s
                CROSS JOIN LATERAL billing.resolve_rate(:plan, s.id, :category, :on) AS r
                WHERE s.code = :code AND s.is_active
                """)
                .param("plan", planId).param("code", serviceCode).param("category", bedCategory).param("on", on)
                .query(TariffService::rate).optional();
    }

    /** Charge category of an active service (e.g. LABORATORY, BED), regardless of price. */
    public Optional<String> category(String serviceCode) {
        return jdbc.sql("SELECT category FROM billing.service WHERE code = :code AND is_active")
                .param("code", serviceCode).query(String.class).optional();
    }

    /** As {@link #rate} but a missing price is a 422 the billing desk can act on. */
    public Rate requireRate(UUID planId, String serviceCode, String bedCategory, LocalDate on) {
        return rate(planId, serviceCode, bedCategory, on).orElseThrow(() -> ApiException.unprocessable("NO_RATE",
                "No rate for service " + serviceCode + (ALL.equals(bedCategory) ? "" : " (" + bedCategory + ")")
                        + " in tariff " + plan(planId).code() + " on " + on));
    }

    /** Rate card of a plan for one ward class (class-specific rates override ALL). */
    public List<Rate> rateCard(UUID planId, String bedCategory, LocalDate on) {
        return jdbc.sql("""
                SELECT s.id, s.code, s.name, s.category, s.sac_code, s.charge_unit, r.tariff_rate_id, r.amount, r.gst_rate
                FROM billing.service AS s
                CROSS JOIN LATERAL billing.resolve_rate(:plan, s.id, :category, :on) AS r
                WHERE s.is_active
                ORDER BY s.category, s.name
                """)
                .param("plan", planId).param("category", bedCategory).param("on", on)
                .query(TariffService::rate).list();
    }

    private static Plan plan(ResultSet rs, int n) throws SQLException {
        return new Plan(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("payer_type"), rs.getBoolean("is_default"));
    }

    private static Rate rate(ResultSet rs, int n) throws SQLException {
        return new Rate(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("category"), rs.getString("sac_code"), rs.getString("charge_unit"),
                rs.getObject("tariff_rate_id", UUID.class), rs.getBigDecimal("amount"), rs.getBigDecimal("gst_rate"));
    }
}
