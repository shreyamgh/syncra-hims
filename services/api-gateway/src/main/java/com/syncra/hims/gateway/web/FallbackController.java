package com.syncra.hims.gateway.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Circuit-breaker fallback target. Returns an RFC 7807 problem document so
 * front-end clients can show a consistent "service temporarily unavailable"
 * banner instead of a raw 500/504.
 */
@RestController
public class FallbackController {

    @RequestMapping("/fallback/{service:[a-z0-9-]+}")
    public Mono<ResponseEntity<ProblemDetail>> serviceUnavailable(@PathVariable String service) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The upstream service is temporarily unavailable. Please retry shortly.");
        problem.setTitle("Service Unavailable");
        problem.setProperty("service", service);
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "15")
                .body(problem));
    }
}
