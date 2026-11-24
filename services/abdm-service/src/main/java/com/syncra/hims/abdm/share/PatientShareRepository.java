package com.syncra.hims.abdm.share;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code abdm.patient_share}: the Scan &amp; Share queue at the registration desk. */
@Repository
public class PatientShareRepository {

    public record Share(UUID id, UUID gatewayRequestId, LocalDate shareDate, String context, int tokenNumber,
                        String abhaNumber, String abhaAddress, String name, String gender, Integer yearOfBirth,
                        Integer monthOfBirth, Integer dayOfBirth, String mobile, String addressLine,
                        String districtName, String stateName, String pincode, UUID matchedPatientId, String status,
                        Instant expiresAt, UUID registeredPatientId, Instant receivedAt) {
    }

    public record NewShare(UUID gatewayRequestId, LocalDate shareDate, String context, String abhaNumber,
                           String abhaAddress, String name, String gender, Integer yearOfBirth, Integer monthOfBirth,
                           Integer dayOfBirth, String mobile, String addressLine, String districtName, String stateName,
                           String pincode, UUID matchedPatientId, Instant expiresAt) {
    }

    private static final String COLUMNS = """
            id, gateway_request_id, share_date, context, token_number, abha_number, abha_address, name, gender,
            year_of_birth, month_of_birth, day_of_birth, mobile, address_line, district_name, state_name, pincode,
            matched_patient_id, status, expires_at, registered_patient_id, received_at
            """;

    private final JdbcClient jdbc;

    public PatientShareRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A still-valid share by the same ABHA at the same counter today: re-shares keep their number. */
    public Optional<Share> livePending(LocalDate day, String context, String abhaAddress, Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM abdm.patient_share
                WHERE share_date = :day AND context = :context AND lower(abha_address) = lower(:address)
                  AND status = 'PENDING' AND expires_at > :now
                ORDER BY received_at DESC LIMIT 1
                """).param("day", day).param("context", context).param("address", abhaAddress)
                .param("now", Timestamp.from(now)).query(PatientShareRepository::map).optional();
    }

    public Optional<Share> byGatewayRequest(UUID requestId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM abdm.patient_share WHERE gateway_request_id = :id")
                .param("id", requestId).query(PatientShareRepository::map).optional();
    }

    /** Inserts the share with the next queue number of its counter for the day. */
    public Share insert(NewShare s) {
        int token = jdbc.sql("""
                INSERT INTO abdm.share_token_counter (share_date, context, last_value) VALUES (:day, :context, 1)
                ON CONFLICT (share_date, context) DO UPDATE SET last_value = abdm.share_token_counter.last_value + 1
                RETURNING last_value
                """).param("day", s.shareDate()).param("context", s.context()).query(Integer.class).single();
        UUID id = jdbc.sql("""
                INSERT INTO abdm.patient_share
                    (gateway_request_id, share_date, context, token_number, abha_number, abha_address, name, gender,
                     year_of_birth, month_of_birth, day_of_birth, mobile, address_line, district_name, state_name,
                     pincode, matched_patient_id, expires_at)
                VALUES (:request, :day, :context, :token, :number, :address, :name, :gender, :year, :month, :dayOfBirth,
                        :mobile, :line, :district, :state, :pincode, :matched, :expires)
                RETURNING id
                """)
                .param("request", s.gatewayRequestId()).param("day", s.shareDate()).param("context", s.context())
                .param("token", token).param("number", s.abhaNumber()).param("address", s.abhaAddress())
                .param("name", s.name()).param("gender", s.gender()).param("year", s.yearOfBirth())
                .param("month", s.monthOfBirth()).param("dayOfBirth", s.dayOfBirth()).param("mobile", s.mobile())
                .param("line", s.addressLine()).param("district", s.districtName()).param("state", s.stateName())
                .param("pincode", s.pincode()).param("matched", s.matchedPatientId())
                .param("expires", Timestamp.from(s.expiresAt()))
                .query(UUID.class).single();
        return find(id).orElseThrow();
    }

    public Optional<Share> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM abdm.patient_share WHERE id = :id")
                .param("id", id).query(PatientShareRepository::map).optional();
    }

    public Optional<Share> lock(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM abdm.patient_share WHERE id = :id FOR UPDATE")
                .param("id", id).query(PatientShareRepository::map).optional();
    }

    public List<Share> pending(LocalDate day, String context, Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM abdm.patient_share
                WHERE share_date = :day AND status = 'PENDING' AND expires_at > :now
                  AND (CAST(:context AS text) IS NULL OR context = :context)
                ORDER BY context, token_number
                """).param("day", day).param("context", context).param("now", Timestamp.from(now))
                .query(PatientShareRepository::map).list();
    }

    public void markRegistered(UUID id, UUID patientId, String actor, Instant at) {
        jdbc.sql("""
                UPDATE abdm.patient_share
                SET status = 'REGISTERED', registered_patient_id = :patient, completed_by = :actor, completed_at = :at
                WHERE id = :id
                """).param("patient", patientId).param("actor", actor).param("at", Timestamp.from(at)).param("id", id)
                .update();
    }

    /** Expires overdue shares and deletes those past retention (the profiles are personal data). */
    public int purge(Instant now, Instant retainAfter) {
        jdbc.sql("UPDATE abdm.patient_share SET status = 'EXPIRED' WHERE status = 'PENDING' AND expires_at <= :now")
                .param("now", Timestamp.from(now)).update();
        int deleted = jdbc.sql("DELETE FROM abdm.patient_share WHERE received_at < :before")
                .param("before", Timestamp.from(retainAfter)).update();
        jdbc.sql("DELETE FROM abdm.share_token_counter WHERE share_date < CAST(:before AS date) - 1")
                .param("before", Timestamp.from(retainAfter)).update();
        return deleted;
    }

    private static Share map(ResultSet rs, int row) throws SQLException {
        return new Share(rs.getObject("id", UUID.class), rs.getObject("gateway_request_id", UUID.class),
                rs.getDate("share_date").toLocalDate(), rs.getString("context"), rs.getInt("token_number"),
                rs.getString("abha_number"), rs.getString("abha_address"), rs.getString("name"), rs.getString("gender"),
                (Integer) rs.getObject("year_of_birth"), shortToInt(rs.getObject("month_of_birth")),
                shortToInt(rs.getObject("day_of_birth")), rs.getString("mobile"), rs.getString("address_line"),
                rs.getString("district_name"), rs.getString("state_name"), rs.getString("pincode"),
                rs.getObject("matched_patient_id", UUID.class), rs.getString("status"),
                rs.getTimestamp("expires_at").toInstant(), rs.getObject("registered_patient_id", UUID.class),
                rs.getTimestamp("received_at").toInstant());
    }

    private static Integer shortToInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }
}
