package com.syncra.hims.patient.emr.orders;

import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.patient.emr.orders.OrderRepository.Order;
import com.syncra.hims.patient.emr.orders.OrderRepository.Orderable;
import com.syncra.hims.patient.emr.orders.OrderRepository.ResultValue;
import com.syncra.hims.patient.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
 * POST /api/v1/emr/encounters/{id}/orders            doctor: order an investigation or procedure
 * GET  /api/v1/emr/encounters/{id}/orders            clinical staff
 * GET  /api/v1/emr/orderables?q=&type=              clinical staff: search the orderable catalogue
 * POST /api/v1/emr/orders/{id}/cancel                doctor
 * POST /api/v1/emr/orders/{id}/sample                nurse, lab technician
 * POST /api/v1/emr/orders/{id}/result                lab technician, doctor (structured values: CREAT feeds the renal guard)
 * GET  /api/v1/emr/worklist?type=LABORATORY|RADIOLOGY|PROCEDURE&status=ORDERED,SAMPLE_COLLECTED&since=
 * GET  /api/v1/emr/orders/{id}/analytes             result entry: the test's parameters with this patient's ranges
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/emr")
public class OrderController {

    public record OrderRequest(
            @NotBlank @Size(max = 16) String serviceCode,
            @Pattern(regexp = "^(ROUTINE|URGENT|STAT)$") String priority,
            @Size(max = 500) String clinicalIndication) {
    }

    public record CancelRequest(@NotBlank @Size(max = 300) String reason) {
    }

    public record SampleRequest(Instant collectedAt) {
    }

    public record ValueRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{2,16}$") String code,
            @NotBlank @Size(max = 100) String name,
            @DecimalMin("-1000000") @DecimalMax("1000000") BigDecimal value,
            @Size(max = 100) String text,
            @Size(max = 20) String unit,
            @Size(max = 40) String referenceRange,
            @Pattern(regexp = "^[HLNAC]$") String flag) {
    }

    public record ResultRequest(@Size(max = 4000) String resultSummary, @Valid @Size(max = 50) List<ValueRequest> values) {
    }

    private final OrderService orders;
    private final OrderRepository repository;

    public OrderController(OrderService orders, OrderRepository repository) {
        this.orders = orders;
        this.repository = repository;
    }

    @GetMapping("/orderables")
    @PreAuthorize(Roles.CLINICAL)
    public List<Orderable> orderables(@RequestParam("q") String query,
                                      @RequestParam(value = "type", required = false) String type) {
        String q = query.trim();
        if (q.length() < 2 || q.length() > 60) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_QUERY", "q must be 2 to 60 characters");
        }
        if (type != null && !OrderService.ORDER_TYPES.containsKey(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_TYPE", "type must be LABORATORY, RADIOLOGY or PROCEDURE");
        }
        return repository.searchOrderables(q, type, 15);
    }

    @PostMapping("/encounters/{encounterId}/orders")
    @PreAuthorize(Roles.CONSULTATION)
    @ResponseStatus(HttpStatus.CREATED)
    public Order place(@PathVariable UUID encounterId, @Valid @RequestBody OrderRequest r) {
        return orders.place(encounterId, r.serviceCode(), r.priority(), r.clinicalIndication());
    }

    @GetMapping("/encounters/{encounterId}/orders")
    @PreAuthorize(Roles.CLINICAL)
    public List<Order> forEncounter(@PathVariable UUID encounterId) {
        return orders.forEncounter(encounterId);
    }

    @PostMapping("/orders/{orderId}/cancel")
    @PreAuthorize(Roles.CONSULTATION)
    public Order cancel(@PathVariable UUID orderId, @Valid @RequestBody CancelRequest r) {
        return orders.cancel(orderId, r.reason());
    }

    @PostMapping("/orders/{orderId}/sample")
    @PreAuthorize(Roles.SAMPLE_COLLECTION)
    public Order sample(@PathVariable UUID orderId, @RequestBody(required = false) SampleRequest r) {
        return orders.sampleCollected(orderId, r == null ? null : r.collectedAt());
    }

    @PostMapping("/orders/{orderId}/result")
    @PreAuthorize(Roles.RESULT_ENTRY)
    public Order result(@PathVariable UUID orderId, @Valid @RequestBody ResultRequest r) {
        List<ResultValue> values = r.values() == null ? List.of() : r.values().stream()
                .map(v -> {
                    if (v.value() == null && (v.text() == null || v.text().isBlank())) {
                        throw ApiException.unprocessable("VALUE_REQUIRED", v.name() + " needs a value");
                    }
                    return new ResultValue(v.code().toUpperCase(), v.name(), v.value(), v.text(), v.unit(),
                            v.referenceRange(), v.flag());
                }).toList();
        return orders.report(orderId, r.resultSummary(), values);
    }

    @GetMapping("/worklist")
    @PreAuthorize(Roles.WORKLIST)
    public List<Order> worklist(@RequestParam("type") String type,
                                @RequestParam(value = "status", defaultValue = "ORDERED,SCHEDULED,SAMPLE_COLLECTED,IN_PROGRESS")
                                List<String> statuses,
                                @RequestParam(value = "since", required = false)
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        if (!OrderService.ORDER_TYPES.containsKey(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_TYPE", "type must be LABORATORY, RADIOLOGY or PROCEDURE");
        }
        return orders.worklist(type, statuses, since);
    }

    @GetMapping("/orders/{orderId}/analytes")
    @PreAuthorize(Roles.WORKLIST)
    public OrderService.ResultTemplate analytes(@PathVariable UUID orderId) {
        return orders.template(orderId);
    }
}
