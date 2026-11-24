package com.syncra.hims.common.web;

import java.util.Map;

/**
 * Staff-facing messages for a service's named database constraints (unique,
 * exclusion), used by {@link DatabaseErrorTranslator} instead of raw database
 * text, which can echo patient data. Declare one bean per service.
 */
@FunctionalInterface
public interface ConstraintMessages {

    Map<String, String> messages();
}
