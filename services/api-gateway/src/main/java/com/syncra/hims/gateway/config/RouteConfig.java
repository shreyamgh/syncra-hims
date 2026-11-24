package com.syncra.hims.gateway.config;

import static org.springframework.cloud.gateway.support.RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

/**
 * Routes that need programmatic configuration, plus the rate-limiter key resolver.
 * Plain service routes live in {@code application.yml} so operators can retarget
 * them through environment variables.
 */
@Configuration(proxyBeanMethods = false)
public class RouteConfig {

    public static final String ORTHANC_ROUTE_ID = "orthanc-dicomweb";
    public static final String ORTHANC_VIEWER_ROUTE_ID = "orthanc-ohif";

    /** Large multi-frame CT/MR retrievals need more than the 30 s global timeout. */
    private static final int PACS_RESPONSE_TIMEOUT_MILLIS = 120_000;

    /**
     * {@code /pacs/dicom-web/**} → Orthanc {@code /dicom-web/**} (QIDO-RS, WADO-RS, STOW-RS).
     *
     * <p>The caller's JWT has already been validated; the gateway swaps it for
     * Orthanc's service credentials, so Orthanc's HTTP port never needs to be
     * exposed. Not rate limited: a single CT series can legitimately issue
     * hundreds of frame requests per second from the viewer.
     */
    @Bean
    RouteLocator pacsRoutes(RouteLocatorBuilder builder, GatewayProperties properties) {
        GatewayProperties.Orthanc orthanc = properties.orthanc();
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                (orthanc.username() + ":" + orthanc.password()).getBytes(StandardCharsets.UTF_8));

        // A lambda (not SetRequestHeader) so the credential never appears in route toString()/logs.
        GatewayFilter orthancCredentials = (exchange, chain) -> chain.filter(exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.set(HttpHeaders.AUTHORIZATION, basicAuth);
                    headers.remove(HttpHeaders.COOKIE);
                }))
                .build());

        return builder.routes()
                // The OHIF viewer served by Orthanc's plugin: static pages, read-only.
                .route(ORTHANC_VIEWER_ROUTE_ID, route -> route
                        .path("/pacs/ohif/**").and().method(org.springframework.http.HttpMethod.GET,
                                org.springframework.http.HttpMethod.HEAD)
                        .filters(filters -> filters
                                .stripPrefix(1)
                                .filter(orthancCredentials))
                        .uri(orthanc.uri()))
                .route(ORTHANC_ROUTE_ID, route -> route
                        .path("/pacs/dicom-web/**")
                        .filters(filters -> filters
                                .stripPrefix(1)
                                .filter(orthancCredentials))
                        .metadata(RESPONSE_TIMEOUT_ATTR, PACS_RESPONSE_TIMEOUT_MILLIS)
                        .uri(orthanc.uri()))
                .build();
    }

    /**
     * Rate-limit bucket per authenticated user ({@code sub} claim). Falls back
     * to the TCP peer address; {@code X-Forwarded-For} is deliberately ignored
     * because it is client-controlled when the gateway is the edge.
     */
    @Bean
    KeyResolver principalOrIpKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .map(name -> "user:" + name)
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
                    return "ip:" + (remote != null && remote.getAddress() != null
                            ? remote.getAddress().getHostAddress()
                            : "unknown");
                }));
    }
}
