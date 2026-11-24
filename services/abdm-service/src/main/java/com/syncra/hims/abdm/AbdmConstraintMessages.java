package com.syncra.hims.abdm;

import com.syncra.hims.common.web.ConstraintMessages;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AbdmConstraintMessages implements ConstraintMessages {

    @Override
    public Map<String, String> messages() {
        return Map.of(
                "patient_abha_number_uq", "This ABHA number is already linked to another patient",
                "patient_abha_address_uq", "This ABHA address is already linked to another patient",
                "care_context_encounter_id_key", "This encounter already has a care context",
                "patient_share_token_uq", "Scan & Share numbering conflict, please retry");
    }
}
