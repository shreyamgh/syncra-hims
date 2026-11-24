package com.syncra.hims.abdm.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.Bundle;
import org.springframework.stereotype.Component;

/** FHIR R4 JSON (the context is expensive to create, so there is one per service). */
@Component
public class FhirJson {

    private final FhirContext context = FhirContext.forR4Cached();

    public FhirContext context() {
        return context;
    }

    public String encode(Bundle bundle) {
        IParser parser = context.newJsonParser();
        parser.setPrettyPrint(false);
        return parser.encodeResourceToString(bundle);
    }

    public Bundle decode(String json) {
        return context.newJsonParser().parseResource(Bundle.class, json);
    }
}
