package com.syncra.hims.gateway.security;

import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Resolves the bearer token from the {@code Authorization} header for all
 * requests and — only for WebSocket handshakes under {@code /ws/} — also from
 * the {@code access_token} query parameter.
 *
 * <p>Browsers cannot set custom headers on the WebSocket handshake, so the
 * Doctor Desk dictation client must pass its token in the URL. Restricting the
 * query fallback to {@code /ws/**} keeps tokens out of URLs (and therefore out
 * of proxy/browser history) everywhere else. The gateway strips the parameter
 * before proxying so it never reaches upstream access logs.
 *
 * <p>The image viewer under {@code /pacs/} is opened by navigation, which cannot carry a header
 * either: for read-only requests (GET/HEAD) there, the token may come from the HttpOnly viewer
 * cookie set by {@code POST /pacs/session}. Nowhere else, and never for writes.
 */
public final class WebSocketAwareBearerTokenConverter implements ServerAuthenticationConverter {

    public static final String WEBSOCKET_PATH_PREFIX = "/ws/";
    public static final String PACS_PATH_PREFIX = "/pacs/";
    public static final String PACS_COOKIE = "SYNCRA_PACS";

    private final ServerBearerTokenAuthenticationConverter headerOnly =
            new ServerBearerTokenAuthenticationConverter();
    private final ServerBearerTokenAuthenticationConverter headerOrQuery =
            new ServerBearerTokenAuthenticationConverter();

    public WebSocketAwareBearerTokenConverter() {
        headerOrQuery.setAllowUriQueryParameter(true);
    }

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (path.startsWith(WEBSOCKET_PATH_PREFIX)) {
            return headerOrQuery.convert(exchange);
        }
        HttpMethod method = exchange.getRequest().getMethod();
        boolean readOnly = HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method);
        if (path.startsWith(PACS_PATH_PREFIX) && readOnly
                && !exchange.getRequest().getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
            HttpCookie cookie = exchange.getRequest().getCookies().getFirst(PACS_COOKIE);
            if (cookie != null && !cookie.getValue().isBlank()) {
                return Mono.just(new BearerTokenAuthenticationToken(cookie.getValue()));
            }
        }
        return headerOnly.convert(exchange);
    }
}
