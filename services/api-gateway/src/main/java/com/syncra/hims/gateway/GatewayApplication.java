package com.syncra.hims.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Syncra HIMS edge gateway.
 *
 * <p>The only component exposed to hospital LAN clients. Responsibilities:
 * <ul>
 *   <li>Stateless JWT bearer authentication for every API and WebSocket route</li>
 *   <li>Routing to internal services (patient, AI/dictation, PACS DICOMweb)</li>
 *   <li>Per-user rate limiting backed by Redis</li>
 *   <li>Circuit breaking with RFC 7807 fallbacks</li>
 *   <li>{@code X-Request-Id} correlation across services</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
