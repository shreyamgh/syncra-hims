package com.syncra.hims.abdm.hiu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncra.hims.abdm.config.AbdmProperties;
import com.syncra.hims.abdm.gateway.AbdmPaths;
import com.syncra.hims.common.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where other hospitals (HIPs) push the encrypted records this hospital asked for. The path
 * carries a one-time secret issued in the data request; there is no other credential in ABDM's
 * push. The body is read with a size cap before it is parsed.
 */
@RestController
public class HiuPushController {

    private final HiuService hiu;
    private final AbdmProperties properties;
    private final ObjectMapper json;

    public HiuPushController(HiuService hiu, AbdmProperties properties, ObjectMapper json) {
        this.hiu = hiu;
        this.properties = properties;
        this.json = json;
    }

    @PostMapping(AbdmPaths.HIU_DATA_PUSH_ROOT + "/{token}")
    public ResponseEntity<Void> push(@PathVariable String token, HttpServletRequest request) throws IOException {
        if (!properties.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ABDM_DISABLED", "ABDM integration is not enabled");
        }
        int max = properties.hiu().maxPushBytes();
        if (request.getContentLengthLong() > max) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE", "At most " + max + " bytes per page");
        }
        byte[] bytes;
        try (InputStream in = request.getInputStream()) {
            bytes = in.readNBytes(max + 1);
        }
        if (bytes.length > max) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE", "At most " + max + " bytes per page");
        }
        JsonNode body;
        try {
            body = json.readTree(bytes);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY", "A JSON object body is required");
        }
        if (body == null || !body.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY", "A JSON object body is required");
        }
        hiu.receive(token, body);
        return ResponseEntity.accepted().build();
    }
}
