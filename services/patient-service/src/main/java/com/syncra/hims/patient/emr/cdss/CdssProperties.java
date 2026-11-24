package com.syncra.hims.patient.emr.cdss;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The AI service's CDSS, re-run when a prescription is signed ({@code syncra.cdss.*}).
 *
 * @param url      AI service root, e.g. {@code http://ai-service:8000}
 * @param required when true, a prescription cannot be signed while the CDSS is unreachable;
 *                 when false (default) it is signed and the missed check is audited
 */
@ConfigurationProperties(prefix = "syncra.cdss")
public record CdssProperties(URI url, @DefaultValue("3s") Duration timeout, @DefaultValue("false") boolean required) {

    public boolean configured() {
        return url != null && !url.toString().isBlank();
    }
}
