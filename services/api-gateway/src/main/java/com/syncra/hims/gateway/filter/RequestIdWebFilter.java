package com.syncra.hims.gateway.filter;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Guarantees every request carries an {@code X-Request-Id}, forwards it to the
 * upstream service and echoes it on the response (including 401/403/429
 * responses produced by the gateway itself), so a single id ties together
 * gateway, service and audit logs.
 *
 * <p>Runs ahead of Spring Security. Client-supplied ids are accepted only if
 * they match a conservative character set, preventing log/header injection.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String CONTEXT_KEY = "syncra.requestId";

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String requestId = incoming != null && SAFE_REQUEST_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER, requestId))
                .build();

        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, requestId);
            return Mono.empty();
        });

        return chain.filter(exchange.mutate().request(request).build())
                .contextWrite(context -> context.put(CONTEXT_KEY, requestId));
    }
}
