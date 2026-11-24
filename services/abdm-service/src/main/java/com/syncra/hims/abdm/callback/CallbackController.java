package com.syncra.hims.abdm.callback;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.abdm.carecontext.CareContextService;
import com.syncra.hims.abdm.config.AbdmProperties;
import com.syncra.hims.abdm.consent.ConsentService;
import com.syncra.hims.abdm.dataflow.HealthInformationService;
import com.syncra.hims.abdm.gateway.AbdmHeaders;
import com.syncra.hims.abdm.gateway.AbdmPaths;
import com.syncra.hims.abdm.gateway.GatewayMessageLog;
import com.syncra.hims.abdm.hiu.HiuService;
import com.syncra.hims.abdm.linking.UserLinkingService;
import com.syncra.hims.abdm.share.ScanShareService;
import com.syncra.hims.common.web.ApiException;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints ABDM calls on this HIP (authenticated by {@code AbdmSecurityConfig} with the
 * gateway's signed JWT). Each answers 202 at once; the real reply is the asynchronous
 * {@code on-*} call made after the callback's data has been committed.
 *
 * <p>A REQUEST-ID seen and answered before is a retry and is not processed twice; one seen but
 * not yet answered is processed again (the handlers are idempotent).
 */
@RestController
public class CallbackController {

    private final GatewayMessageLog messages;
    private final CareContextService careContexts;
    private final ConsentService consents;
    private final HealthInformationService healthInformation;
    private final ScanShareService scanShare;
    private final UserLinkingService userLinking;
    private final HiuService hiu;
    private final AbdmProperties properties;

    public CallbackController(GatewayMessageLog messages, CareContextService careContexts, ConsentService consents,
                              HealthInformationService healthInformation, ScanShareService scanShare,
                              UserLinkingService userLinking, HiuService hiu, AbdmProperties properties) {
        this.messages = messages;
        this.careContexts = careContexts;
        this.consents = consents;
        this.healthInformation = healthInformation;
        this.scanShare = scanShare;
        this.userLinking = userLinking;
        this.hiu = hiu;
        this.properties = properties;
    }

    @PostMapping(AbdmPaths.ON_GENERATE_TOKEN)
    public ResponseEntity<Void> onGenerateToken(@RequestHeader(AbdmHeaders.REQUEST_ID) String requestId,
                                                @RequestHeader(value = AbdmHeaders.HIP_ID, required = false) String hipId,
                                                @RequestBody JsonNode body) {
        return handle(AbdmPaths.ON_GENERATE_TOKEN, requestId, hipId, body, null, true,
                (json, id) -> careContexts.onLinkToken(json));
    }

    @PostMapping(AbdmPaths.ON_LINK_CARE_CONTEXT)
    public ResponseEntity<Void> onLinkCareContext(@RequestHeader(AbdmHeaders.REQUEST_ID) String requestId,
                                                  @RequestHeader(value = AbdmHeaders.HIP_ID, required = false) String hipId,
                                                  @RequestBody JsonNode body) {
        return handle(AbdmPaths.ON_LINK_CARE_CONTEXT, requestId, hipId, body, null, true,
                (json, id) -> careContexts.onLinkResult(json));
    }

    @PostMapping(AbdmPaths.CONSENT_NOTIFY)
    public ResponseEntity<Void> consentNotify(@RequestHeader(AbdmHeaders.REQUEST_ID) String requestId,
                                              @RequestHeader(value = AbdmHeaders.HIP_ID, required = false) String hipId,
                                              @RequestBody JsonNode body) {
        return handle(AbdmPaths.CONSENT_NOTIFY, requestId, hipId, body, null, false, consents::onNotify);
    }

    @PostMapping(AbdmPaths.HEALTH_INFORMATION_REQUEST)
    public ResponseEntity<Void> healthInformationRequest(@RequestHeader(AbdmHeaders.REQUEST_ID) String requestId,
                                                         @RequestHeader(value = AbdmHeaders.HIP_ID, required = false) String hipId,
                                                         @RequestBody JsonNode body) {
        return handle(AbdmPaths.HEALTH_INFORMATION_REQUEST, requestId, hipId, body, Json.uuid(body, "transactionId"),
                false, healthInformation::onRequest);
    }

    @PostMapping(AbdmPaths.PATIENT_SHARE)
    public ResponseEntity<Void> patientShare(@RequestHeader(AbdmHeaders.REQUEST_ID) String requestId,
                                             @RequestHeader(value = AbdmHeaders.HIP_ID, required = false) String hipId,
                                             @RequestBody JsonNode body) {
        return handle(AbdmPaths.PATIENT_SHARE, requestId, hipId, body, null, false, scanShare::onShare);
    }

    @PostMapping(AbdmPaths.DISCOVER)
    public ResponseEntity<Void> discover(@RequestHeader(AbdmHeaders.REQUEST_ID) String requestId,
                                         @RequestHeader(value = AbdmHeaders.HIP_ID, required = false) String hipId,
                                         @RequestBody JsonNode body) {
        return handle(AbdmPaths.DISCOVER, requestId, hipId, body, Json.uuid(body, "transactionId"), false,
                userLinking::onDiscover);
    }

    @PostMapping(AbdmPaths.LINK_INIT)
    public ResponseEntity<Void> linkInit(@RequestHeader(AbdmHeaders.REQUEST_ID) String requestId,
                                         @RequestHeader(value = AbdmHeaders.HIP_ID, required = false) String hipId,
                                         @RequestBody JsonNode body) {
        return handle(AbdmPaths.LINK_INIT, requestId, hipId, body, Json.uuid(body, "transactionId"), false,
                userLinking::onLinkInit);
    }

    @PostMapping(AbdmPaths.LINK_CONFIRM)
    public ResponseEntity<Void> linkConfirm(@RequestHeader(AbdmHeaders.REQUEST_ID) String requestId,
                                            @RequestHeader(value = AbdmHeaders.HIP_ID, required = false) String hipId,
                                            @RequestBody JsonNode body) {
        return handle(AbdmPaths.LINK_CONFIRM, requestId, hipId, body, null, false, userLinking::onLinkConfirm);
    }

    // ------------------------------------------------------------------ as HIU (Milestone 3)

    @PostMapping(AbdmPaths.HIU_CONSENT_ON_INIT)
    public ResponseEntity<Void> hiuConsentOnInit(@RequestHeader(AbdmHeaders.REQUEST_ID) String requestId,
                                                 @RequestBody JsonNode body) {
        return handle(AbdmPaths.HIU_CONSENT_ON_INIT, requestId, null, body, null, true, (json, id) -> hiu.onConsentInit(json));
    }

    @PostMapping(AbdmPaths.HIU_CONSENT_NOTIFY)
    public ResponseEntity<Void> hiuConsentNotify(@RequestHeader(AbdmHeaders.REQUEST_ID) String requestId,
                                                 @RequestBody JsonNode body) {
        return handle(AbdmPaths.HIU_CONSENT_NOTIFY, requestId, null, body, null, false, hiu::onConsentNotify);
    }

    @PostMapping(AbdmPaths.HIU_CONSENT_ON_FETCH)
    public ResponseEntity<Void> hiuConsentOnFetch(@RequestHeader(AbdmHeaders.REQUEST_ID) String requestId,
                                                  @RequestBody JsonNode body) {
        return handle(AbdmPaths.HIU_CONSENT_ON_FETCH, requestId, null, body, null, true, (json, id) -> hiu.onConsentFetch(json));
    }

    @PostMapping(AbdmPaths.HIU_HEALTH_INFORMATION_ON_REQUEST)
    public ResponseEntity<Void> hiuHealthInformationOnRequest(@RequestHeader(AbdmHeaders.REQUEST_ID) String requestId,
                                                              @RequestBody JsonNode body) {
        return handle(AbdmPaths.HIU_HEALTH_INFORMATION_ON_REQUEST, requestId, null, body,
                Json.uuid(body, "hiRequest.transactionId"), true, (json, id) -> hiu.onHealthInformationRequest(json));
    }

    /**
     * @param isReply the callback is itself ABDM's reply to one of our calls (nothing to answer):
     *                it counts as answered once processed
     */
    private ResponseEntity<Void> handle(String path, String requestIdHeader, String hipId, JsonNode body,
                                        UUID transactionId, boolean isReply, BiConsumer<JsonNode, UUID> handler) {
        UUID requestId;
        try {
            requestId = UUID.fromString(requestIdHeader.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_ID", "REQUEST-ID must be a UUID");
        }
        if (hipId != null && properties.hipId() != null && !hipId.equals(properties.hipId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WRONG_HIP", "This bridge serves another HIP");
        }
        if (body == null || !body.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY", "A JSON object body is required");
        }
        GatewayMessageLog.Inbound inbound = messages.inbound(requestId, path, transactionId);
        if (!inbound.alreadyAnswered()) {
            handler.accept(body, requestId);
            if (isReply) {
                messages.inboundOutcome(requestId, GatewayMessageLog.Outcome.ACKED, null, null);
            }
        }
        return ResponseEntity.accepted().build();
    }
}
