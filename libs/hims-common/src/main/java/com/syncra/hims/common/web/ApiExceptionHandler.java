package com.syncra.hims.common.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 7807 problem responses for every failure. Each problem carries a stable
 * {@code code} the front end can switch on; messages never contain PHI.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final DatabaseErrorTranslator databaseErrors;

    public ApiExceptionHandler(DatabaseErrorTranslator databaseErrors) {
        this.databaseErrors = databaseErrors;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        return problem(ex);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return problem(ApiException.conflict("CONCURRENT_MODIFICATION",
                "The record was changed by someone else. Reload it and try again."));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> handleDatabase(DataAccessException ex) {
        return databaseErrors.translate(ex)
                .map(ApiExceptionHandler::problem)
                .orElseGet(() -> unexpected(ex));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return problem(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Your role is not permitted to perform this action"));
    }

    /** Last resort; still recognises database errors wrapped in other exceptions (e.g. JPA flush). */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        return databaseErrors.translate(ex).map(ApiExceptionHandler::problem).orElseGet(() -> unexpected(ex));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> fields.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));

        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request has invalid fields");
        body.setTitle("Validation failed");
        body.setProperty("code", "VALIDATION_FAILED");
        body.setProperty("fields", fields);
        return ResponseEntity.badRequest().body(body);
    }

    private static ResponseEntity<ProblemDetail> problem(ApiException ex) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        body.setProperty("code", ex.code());
        ex.properties().forEach(body::setProperty);
        return ResponseEntity.status(ex.status()).body(body);
    }

    private static ResponseEntity<ProblemDetail> unexpected(Exception ex) {
        log.error("Unhandled error", ex);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Quote the X-Request-Id when reporting it.");
        body.setProperty("code", "INTERNAL_ERROR");
        return ResponseEntity.internalServerError().body(body);
    }
}
