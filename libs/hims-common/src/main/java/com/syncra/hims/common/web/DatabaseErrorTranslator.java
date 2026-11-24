package com.syncra.hims.common.web;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.http.HttpStatus;

/**
 * Turns PostgreSQL errors raised by the schema's constraints and triggers into
 * {@link ApiException}s. Raw database messages are never returned for
 * constraint violations because their DETAIL can echo patient data; the
 * schema's own {@code SYNxx} messages are written to be staff-facing.
 */
public class DatabaseErrorTranslator {

    private final Map<String, String> constraintMessages;

    /** Collects the friendly messages each service contributes for its own constraints. */
    public DatabaseErrorTranslator(List<ConstraintMessages> contributions) {
        Map<String, String> messages = new HashMap<>();
        contributions.forEach(c -> messages.putAll(c.messages()));
        this.constraintMessages = Map.copyOf(messages);
    }

    public Optional<ApiException> translate(Throwable failure) {
        return findSqlException(failure).map(this::toApiException);
    }

    private ApiException toApiException(SQLException sql) {
        String state = sql.getSQLState() == null ? "" : sql.getSQLState();
        ServerErrorMessage server = sql instanceof PSQLException psql ? psql.getServerErrorMessage() : null;
        String constraint = server != null ? server.getConstraint() : null;
        String serverMessage = server != null ? server.getMessage() : null;
        Map<String, Object> props = constraint != null ? Map.of("constraint", constraint) : Map.of();

        return switch (state) {
            case "SYN01" -> new ApiException(HttpStatus.CONFLICT, "RECORD_LOCKED", serverMessage);
            case "SYN02" -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NUMBERING_EXHAUSTED", serverMessage);
            case "SYN03" -> new ApiException(HttpStatus.CONFLICT, "BED_UNAVAILABLE", serverMessage);
            case "SYN04" -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WORKFLOW_PRECONDITION", serverMessage);
            case "23505" -> new ApiException(HttpStatus.CONFLICT, "DUPLICATE",
                    constraintMessages.getOrDefault(constraint, "A record with the same unique value already exists"), props);
            case "23P01" -> new ApiException(HttpStatus.CONFLICT, "OVERLAP",
                    constraintMessages.getOrDefault(constraint, "Overlaps an existing record"), props);
            case "23514" -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_VALUE",
                    "A value breaks the rule " + constraint, props);
            case "23503" -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REFERENCE",
                    "A referenced record does not exist or does not belong to this patient", props);
            case "23502" -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MISSING_VALUE", "A required value is missing");
            case "40001", "40P01" -> new ApiException(HttpStatus.CONFLICT, "RETRY",
                    "The record was busy; please retry");
            default -> null;
        };
    }

    private static Optional<SQLException> findSqlException(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                SQLException next = sql.getNextException();
                return Optional.of(next instanceof PSQLException ? next : sql);
            }
        }
        return Optional.empty();
    }
}
