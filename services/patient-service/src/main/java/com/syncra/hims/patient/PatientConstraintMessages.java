package com.syncra.hims.patient;

import com.syncra.hims.common.web.ConstraintMessages;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Staff-facing messages for the patient, OPD and IPD constraints of the schema. */
@Component
public class PatientConstraintMessages implements ConstraintMessages {

    @Override
    public Map<String, String> messages() {
        return Map.ofEntries(
                Map.entry("visit_one_live_token", "Patient already holds an active token in this department today"),
                Map.entry("visit_token_uq", "Token numbering conflict, please retry"),
                Map.entry("admission_one_active_per_patient", "Patient already has an active admission"),
                Map.entry("bed_no_double_booking", "Bed is already occupied for that period"),
                Map.entry("bed_assignment_open_per_bed", "Bed is already occupied"),
                Map.entry("admission_one_bed_at_a_time", "Admission already holds a bed for that period"),
                Map.entry("patient_abha_number_uq", "This ABHA number is already linked to another patient"),
                Map.entry("patient_abha_address_uq", "This ABHA address is already linked to another patient"),
                Map.entry("staff_employee_code_key", "Employee code already exists"),
                Map.entry("immunization_one_dose", "This dose of the vaccine is already recorded for the patient"),
                Map.entry("immunization_in_date", "The vaccine was past its expiry date on the day it was given"),
                Map.entry("immunization_traceable", "Enter the batch (lot) number and expiry of the vial"),
                Map.entry("immunization_next_due", "The next due date must be after the vaccination"),
                Map.entry("diagnosis_one_primary_final", "This encounter already has a primary final diagnosis"),
                Map.entry("service_order_accession_number_key", "Accession number already used, please retry"));
    }
}
