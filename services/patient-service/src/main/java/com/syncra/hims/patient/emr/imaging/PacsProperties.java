package com.syncra.hims.patient.emr.imaging;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Orthanc PACS, polled for newly stable studies ({@code syncra.pacs.*}).
 *
 * @param url      Orthanc's REST root, e.g. {@code http://orthanc:8042}; empty disables indexing
 * @param username Orthanc service user
 * @param password its password
 */
@ConfigurationProperties(prefix = "syncra.pacs")
public record PacsProperties(URI url, String username, String password,
                             @DefaultValue("PT30S") Duration pollInterval) {

    public boolean enabled() {
        return url != null && !url.toString().isBlank();
    }

    @Override
    public String toString() {
        return "PacsProperties[url=" + url + ", username=" + username + ", password=****]";
    }
}
