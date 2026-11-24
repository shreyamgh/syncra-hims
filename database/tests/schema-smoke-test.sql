-- =============================================================================
--  Syncra HIMS — schema smoke test
--
--  Exercises the rules encoded in init-schema.sql as the APPLICATION role, inside
--  a transaction that is rolled back, so it leaves no data behind.
--  Every check prints PASS; the first failure aborts with a non-zero exit.
--
--  Run (stack up):  make test-db
--  Development / staging databases only.
-- =============================================================================
\set ON_ERROR_STOP on

BEGIN;
SET LOCAL client_min_messages = notice;

DO $test$
DECLARE
    v_gmed     uuid;
    v_paed     uuid;
    v_doctor   uuid;
    v_plan     uuid;
    v_p1       patient.patient%ROWTYPE;
    v_p2       patient.patient%ROWTYPE;
    v_e1       uuid;
    v_e2       uuid;
    v_e3       uuid;
    v_e4       uuid;
    v_tmp      uuid;
    v_t1       integer;
    v_t2       integer;
    v_a1       ipd.admission%ROWTYPE;
    v_a2       uuid;
    v_bed_gm1  uuid;
    v_bed_gm2  uuid;
    v_bed_pvt1 uuid;
    v_ba1      uuid;
    v_ba2      uuid;
    v_transfer timestamptz := now() - interval '1 day';
    v_bill     billing.bill%ROWTYPE;
    v_pay      billing.payment%ROWTYPE;
    v_note     uuid;
    v_rx       uuid;
    v_svc_reg  uuid;
    v_svc_con  uuid;
    v_svc_bed  uuid;
    v_svc_cbc  uuid;
    v_audit    bigint;
    v_count    integer;
    v_amount   numeric;
    v_text     text;
BEGIN
    ---------------------------------------------------------------- reference data
    SELECT count(*) INTO v_count FROM ipd.bed WHERE is_active;
    ASSERT v_count = 50, format('expected 50 beds, found %s', v_count);
    SELECT count(*) INTO v_count FROM core.department;
    ASSERT v_count = 13, format('expected 13 departments, found %s', v_count);
    SELECT id INTO v_plan FROM billing.tariff_plan WHERE is_default;
    ASSERT v_plan IS NOT NULL, 'no default tariff plan';
    SELECT id INTO v_gmed FROM core.department WHERE code = 'GMED';
    SELECT id INTO v_paed FROM core.department WHERE code = 'PAED';
    SELECT id INTO v_svc_reg FROM billing.service WHERE code = 'REG-OPD';
    SELECT id INTO v_svc_con FROM billing.service WHERE code = 'CON-NEW';
    SELECT id INTO v_svc_bed FROM billing.service WHERE code = 'BED-DAY';
    SELECT id INTO v_svc_cbc FROM billing.service WHERE code = 'LAB-CBC';
    RAISE NOTICE 'PASS  reference data: 50 beds, 13 departments, default tariff plan';

    ---------------------------------------------------------------- staff
    INSERT INTO core.staff (employee_code, full_name, staff_role, department_id, council_registration)
    VALUES ('SMOKE-D1', 'Dr Smoke Test', 'DOCTOR', v_gmed, 'TEST/12345')
    RETURNING id INTO v_doctor;
    BEGIN
        INSERT INTO core.staff (employee_code, full_name, staff_role) VALUES ('SMOKE-D2', 'Dr No Reg', 'DOCTOR');
        RAISE EXCEPTION 'FAIL: doctor without council registration accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS  doctors require a council registration';
    END;

    ---------------------------------------------------------------- patient / UHID
    INSERT INTO patient.patient (first_name, last_name, gender, date_of_birth, mobile)
    VALUES ('Ramesh', 'Kumar', 'M', DATE '1980-05-01', '9876543210') RETURNING * INTO v_p1;
    INSERT INTO patient.patient (first_name, last_name, gender, date_of_birth, mobile)
    VALUES ('Sita', 'Devi', 'F', DATE '1990-07-15', '9876501234') RETURNING * INTO v_p2;
    ASSERT v_p1.uhid ~ ('^SYNC-' || to_char(core.ist_today(), 'YYYYMMDD') || '-[0-9]{4}$'),
        'bad UHID ' || v_p1.uhid;
    ASSERT right(v_p2.uhid, 4)::int = right(v_p1.uhid, 4)::int + 1, 'UHIDs are not sequential';
    ASSERT v_p1.full_name = 'Ramesh Kumar', 'full_name not generated';
    RAISE NOTICE 'PASS  UHIDs % and % issued in sequence', v_p1.uhid, v_p2.uhid;

    BEGIN
        UPDATE patient.patient SET uhid = 'SYNC-20000101-0001' WHERE id = v_p1.id;
        RAISE EXCEPTION 'FAIL: UHID change accepted';
    EXCEPTION WHEN SQLSTATE 'SYN01' THEN
        RAISE NOTICE 'PASS  UHID is permanent';
    END;
    BEGIN
        INSERT INTO patient.patient (first_name, gender, mobile) VALUES ('Bad', 'M', '12345');
        RAISE EXCEPTION 'FAIL: invalid mobile accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS  mobile numbers validated';
    END;
    BEGIN
        INSERT INTO patient.patient_identifier (patient_id, id_type, id_value)
        VALUES (v_p1.id, 'AADHAAR', '123412341234');
        RAISE EXCEPTION 'FAIL: Aadhaar number stored';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS  Aadhaar numbers cannot be stored';
    END;

    ---------------------------------------------------------------- OPD tokens
    INSERT INTO clinical.encounter (patient_id, encounter_type, department_id, attending_doctor_id)
    VALUES (v_p1.id, 'OPD', v_gmed, v_doctor) RETURNING id INTO v_e1;
    INSERT INTO opd.visit (encounter_id, patient_id, department_id, doctor_id)
    VALUES (v_e1, v_p1.id, v_gmed, v_doctor) RETURNING token_number INTO v_t1;
    INSERT INTO clinical.encounter (patient_id, encounter_type, department_id)
    VALUES (v_p2.id, 'OPD', v_gmed) RETURNING id INTO v_e2;
    INSERT INTO opd.visit (encounter_id, patient_id, department_id, priority)
    VALUES (v_e2, v_p2.id, v_gmed, 'SENIOR_CITIZEN') RETURNING token_number INTO v_t2;
    ASSERT v_t2 = v_t1 + 1, format('tokens %s, %s not sequential', v_t1, v_t2);
    RAISE NOTICE 'PASS  OPD tokens % and % auto-assigned', v_t1, v_t2;

    BEGIN
        INSERT INTO clinical.encounter (patient_id, encounter_type, department_id)
        VALUES (v_p1.id, 'OPD', v_gmed) RETURNING id INTO v_tmp;
        INSERT INTO opd.visit (encounter_id, patient_id, department_id) VALUES (v_tmp, v_p1.id, v_gmed);
        RAISE EXCEPTION 'FAIL: second live token for the same patient/department/day';
    EXCEPTION WHEN unique_violation THEN
        RAISE NOTICE 'PASS  one live token per patient per department per day';
    END;
    BEGIN
        INSERT INTO clinical.encounter (patient_id, encounter_type, department_id)
        VALUES (v_p1.id, 'OPD', v_paed) RETURNING id INTO v_tmp;
        INSERT INTO opd.visit (encounter_id, patient_id, department_id) VALUES (v_tmp, v_p2.id, v_paed);
        RAISE EXCEPTION 'FAIL: visit accepted for an encounter of another patient';
    EXCEPTION WHEN foreign_key_violation THEN
        RAISE NOTICE 'PASS  visit/encounter patient consistency enforced';
    END;
    SELECT count(*) INTO v_count FROM opd.v_queue_today WHERE visit_id IS NOT NULL;
    ASSERT v_count >= 2, 'queue view missing visits';

    ---------------------------------------------------------------- vitals
    INSERT INTO clinical.vital_sign (encounter_id, recorded_by, weight_kg, height_cm, bp_systolic, bp_diastolic)
    VALUES (v_e1, 'smoke', 70, 175, 120, 80);
    SELECT bmi INTO v_amount FROM clinical.vital_sign WHERE encounter_id = v_e1;
    ASSERT v_amount = 22.86, format('BMI %s', v_amount);
    BEGIN
        INSERT INTO clinical.vital_sign (encounter_id, recorded_by, bp_systolic, bp_diastolic)
        VALUES (v_e1, 'smoke', 80, 120);
        RAISE EXCEPTION 'FAIL: systolic below diastolic accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS  vitals: BMI computed, implausible BP rejected';
    END;
    BEGIN
        INSERT INTO clinical.vital_sign (encounter_id, recorded_by, pulse_bpm, source)
        VALUES (v_e1, 'smoke', 72, 'DICTATION');
        RAISE EXCEPTION 'FAIL: dictation-sourced vital without a session accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS  dictation provenance required';
    END;

    ---------------------------------------------------------------- IPD / beds
    SELECT id INTO v_bed_gm1 FROM ipd.bed WHERE bed_code = 'GMW-01';
    SELECT id INTO v_bed_gm2 FROM ipd.bed WHERE bed_code = 'GMW-02';
    SELECT id INTO v_bed_pvt1 FROM ipd.bed WHERE bed_code = 'PVT-01';

    INSERT INTO clinical.encounter (patient_id, encounter_type, department_id, attending_doctor_id)
    VALUES (v_p1.id, 'IPD', v_gmed, v_doctor) RETURNING id INTO v_e3;
    INSERT INTO ipd.admission (encounter_id, patient_id, department_id, admitting_doctor_id,
                               admission_type, tariff_plan_id, admitted_at)
    VALUES (v_e3, v_p1.id, v_gmed, v_doctor, 'EMERGENCY', v_plan, now() - interval '2 days')
    RETURNING * INTO v_a1;
    ASSERT v_a1.admission_number ~ '^ADM/[0-9]{2}-[0-9]{2}/[0-9]{6}$', 'admission number ' || v_a1.admission_number;

    INSERT INTO ipd.bed_assignment (admission_id, bed_id, assigned_at)
    VALUES (v_a1.id, v_bed_gm1, now() - interval '2 days') RETURNING id INTO v_ba1;
    SELECT status INTO v_text FROM ipd.bed WHERE id = v_bed_gm1;
    ASSERT v_text = 'OCCUPIED', 'bed not marked occupied';
    SELECT bed_category INTO v_text FROM ipd.bed_assignment WHERE id = v_ba1;
    ASSERT v_text = 'GENERAL', 'bed category not captured';
    RAISE NOTICE 'PASS  admission % placed in GMW-01 (bed now OCCUPIED)', v_a1.admission_number;

    INSERT INTO clinical.encounter (patient_id, encounter_type, department_id)
    VALUES (v_p2.id, 'IPD', v_gmed) RETURNING id INTO v_e4;
    INSERT INTO ipd.admission (encounter_id, patient_id, department_id, admitting_doctor_id, admission_type, tariff_plan_id)
    VALUES (v_e4, v_p2.id, v_gmed, v_doctor, 'ELECTIVE', v_plan) RETURNING id INTO v_a2;
    BEGIN
        INSERT INTO ipd.bed_assignment (admission_id, bed_id) VALUES (v_a2, v_bed_gm2);
        RAISE EXCEPTION 'FAIL: female patient placed in the male ward';
    EXCEPTION WHEN SQLSTATE 'SYN03' THEN
        RAISE NOTICE 'PASS  ward gender restriction enforced';
    END;
    BEGIN
        INSERT INTO ipd.bed_assignment (admission_id, bed_id) VALUES (v_a2, v_bed_gm1);
        RAISE EXCEPTION 'FAIL: occupied bed assigned again';
    EXCEPTION WHEN SQLSTATE 'SYN03' THEN
        RAISE NOTICE 'PASS  occupied bed cannot be assigned';
    END;
    BEGIN
        INSERT INTO clinical.encounter (patient_id, encounter_type, department_id)
        VALUES (v_p1.id, 'IPD', v_gmed) RETURNING id INTO v_tmp;
        INSERT INTO ipd.admission (encounter_id, patient_id, department_id, admitting_doctor_id, admission_type, tariff_plan_id)
        VALUES (v_tmp, v_p1.id, v_gmed, v_doctor, 'ELECTIVE', v_plan);
        RAISE EXCEPTION 'FAIL: second active admission for one patient';
    EXCEPTION WHEN unique_violation THEN
        RAISE NOTICE 'PASS  one active admission per patient';
    END;
    BEGIN
        UPDATE ipd.admission SET status = 'DISCHARGED', discharged_at = now() WHERE id = v_a1.id;
        RAISE EXCEPTION 'FAIL: discharged while still holding a bed';
    EXCEPTION WHEN SQLSTATE 'SYN04' THEN
        RAISE NOTICE 'PASS  discharge blocked until the bed is released';
    END;

    -- Transfer GMW-01 -> PVT-01 at the same instant
    UPDATE ipd.bed_assignment SET released_at = v_transfer, released_by = 'smoke' WHERE id = v_ba1;
    INSERT INTO ipd.bed_assignment (admission_id, bed_id, assigned_at, reason)
    VALUES (v_a1.id, v_bed_pvt1, v_transfer, 'TRANSFER') RETURNING id INTO v_ba2;
    SELECT status INTO v_text FROM ipd.bed WHERE id = v_bed_gm1;
    ASSERT v_text = 'CLEANING', 'released bed not sent to CLEANING';
    RAISE NOTICE 'PASS  transfer to PVT-01; GMW-01 moved to CLEANING';

    BEGIN
        -- A back-dated stay overlapping the open PVT-01 assignment (bypasses the status check)
        INSERT INTO ipd.bed_assignment (admission_id, bed_id, assigned_at, released_at)
        VALUES (v_a2, v_bed_pvt1, now() - interval '12 hours', now() - interval '6 hours');
        RAISE EXCEPTION 'FAIL: overlapping occupancy accepted';
    EXCEPTION WHEN exclusion_violation THEN
        RAISE NOTICE 'PASS  exclusion constraint prevents double-booking';
    END;
    BEGIN
        UPDATE ipd.bed_assignment SET bed_id = v_bed_gm2 WHERE id = v_ba1;
        RAISE EXCEPTION 'FAIL: bed history rewritten';
    EXCEPTION WHEN SQLSTATE 'SYN01' THEN
        RAISE NOTICE 'PASS  bed assignment history is immutable';
    END;
    BEGIN
        DELETE FROM ipd.bed_assignment WHERE id = v_ba1;
        RAISE EXCEPTION 'FAIL: bed assignment deleted';
    EXCEPTION WHEN SQLSTATE 'SYN01' THEN
        NULL;
    END;
    SELECT count(*) INTO v_count FROM ipd.v_bed_board;
    ASSERT v_count = 50, format('bed board shows %s rows', v_count);

    ---------------------------------------------------------------- tariff
    SELECT amount INTO v_amount FROM billing.resolve_rate(v_plan, v_svc_bed, 'PRIVATE');
    ASSERT v_amount = 3000, format('private bed rate %s', v_amount);
    SELECT amount INTO v_amount FROM billing.resolve_rate(v_plan, v_svc_con, 'PRIVATE');
    ASSERT v_amount = 300, format('consultation fallback rate %s', v_amount);
    RAISE NOTICE 'PASS  tariff resolution (ward-class rate, ALL fallback)';
    BEGIN
        INSERT INTO billing.tariff_rate (tariff_plan_id, service_id, amount, valid_from)
        VALUES (v_plan, v_svc_con, 350, DATE '2026-06-01');
        RAISE EXCEPTION 'FAIL: overlapping tariff accepted';
    EXCEPTION WHEN exclusion_violation THEN
        RAISE NOTICE 'PASS  overlapping tariff validity rejected';
    END;

    ---------------------------------------------------------------- OPD bill
    INSERT INTO billing.bill (bill_type, patient_id, encounter_id, tariff_plan_id)
    VALUES ('OPD', v_p1.id, v_e1, v_plan) RETURNING * INTO v_bill;
    BEGIN
        UPDATE billing.bill SET status = 'FINAL' WHERE id = v_bill.id;
        RAISE EXCEPTION 'FAIL: empty bill finalised';
    EXCEPTION WHEN SQLSTATE 'SYN04' THEN
        RAISE NOTICE 'PASS  empty bill cannot be finalised';
    END;

    INSERT INTO billing.bill_item (bill_id, service_id, description, quantity, unit_price)
    VALUES (v_bill.id, v_svc_reg, 'OPD Registration', 1, 50),
           (v_bill.id, v_svc_con, 'Consultation - New Visit', 1, 300);
    INSERT INTO billing.bill_item (bill_id, service_id, description, quantity, unit_price,
                                   discount_amount, discount_approved_by, gst_rate)
    VALUES (v_bill.id, v_svc_cbc, 'Complete Blood Count', 2, 100, 10, 'smoke-supervisor', 5);
    BEGIN
        INSERT INTO billing.bill_item (bill_id, service_id, description, quantity, unit_price, discount_amount)
        VALUES (v_bill.id, v_svc_cbc, 'Unapproved discount', 1, 100, 20);
        RAISE EXCEPTION 'FAIL: unapproved discount accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS  discounts need an approver';
    END;

    UPDATE billing.bill SET round_off = 0.50 WHERE id = v_bill.id;
    UPDATE billing.bill SET status = 'FINAL', finalized_by = 'smoke' WHERE id = v_bill.id;
    SELECT * INTO v_bill FROM billing.bill WHERE id = v_bill.id;
    -- 50 + 300 + (200 - 10) + 5% GST on 190 (9.50) = 549.50, +0.50 round-off
    ASSERT v_bill.gross_amount = 550 AND v_bill.discount_amount = 10 AND v_bill.tax_amount = 9.50
           AND v_bill.net_amount = 549.50 AND v_bill.payable_amount = 550,
        format('totals gross=%s disc=%s tax=%s net=%s payable=%s', v_bill.gross_amount,
               v_bill.discount_amount, v_bill.tax_amount, v_bill.net_amount, v_bill.payable_amount);
    ASSERT v_bill.bill_number ~ '^OPB/[0-9]{2}-[0-9]{2}/[0-9]{6}$', 'bill number ' || v_bill.bill_number;
    RAISE NOTICE 'PASS  bill % finalised, payable Rs %', v_bill.bill_number, v_bill.payable_amount;

    BEGIN
        INSERT INTO billing.bill_item (bill_id, service_id, description, quantity, unit_price)
        VALUES (v_bill.id, v_svc_reg, 'Late charge', 1, 50);
        RAISE EXCEPTION 'FAIL: item added to a final bill';
    EXCEPTION WHEN SQLSTATE 'SYN01' THEN
        RAISE NOTICE 'PASS  final bill items are locked';
    END;
    BEGIN
        UPDATE billing.bill SET remarks = 'edited' WHERE id = v_bill.id;
        RAISE EXCEPTION 'FAIL: final bill edited';
    EXCEPTION WHEN SQLSTATE 'SYN01' THEN
        RAISE NOTICE 'PASS  final bill header is locked';
    END;
    BEGIN
        INSERT INTO billing.bill (bill_type, patient_id, encounter_id, tariff_plan_id, status)
        VALUES ('OPD', v_p2.id, v_e2, v_plan, 'FINAL');
        RAISE EXCEPTION 'FAIL: bill created directly as FINAL';
    EXCEPTION WHEN SQLSTATE 'SYN04' THEN
        NULL;
    END;

    ---------------------------------------------------------------- payments
    INSERT INTO billing.payment (patient_id, bill_id, payment_type, payment_mode, amount, received_by)
    VALUES (v_p1.id, v_bill.id, 'SETTLEMENT', 'CASH', 550, 'smoke') RETURNING * INTO v_pay;
    ASSERT v_pay.receipt_number ~ '^RCT/[0-9]{2}-[0-9]{2}/[0-9]{6}$', 'receipt ' || v_pay.receipt_number;
    SELECT balance_due INTO v_amount FROM billing.v_bill_balance WHERE bill_id = v_bill.id;
    ASSERT v_amount = 0, format('balance due %s', v_amount);
    RAISE NOTICE 'PASS  receipt % settles the bill (balance 0)', v_pay.receipt_number;

    BEGIN
        INSERT INTO billing.payment (patient_id, bill_id, payment_type, payment_mode, amount, received_by)
        VALUES (v_p1.id, v_bill.id, 'SETTLEMENT', 'UPI', 10, 'smoke');
        RAISE EXCEPTION 'FAIL: UPI payment without reference accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS  non-cash payments need a reference';
    END;
    BEGIN
        UPDATE billing.payment SET amount = 500 WHERE id = v_pay.id;
        RAISE EXCEPTION 'FAIL: receipt amount edited';
    EXCEPTION WHEN SQLSTATE 'SYN01' THEN
        NULL;
    END;
    BEGIN
        DELETE FROM billing.payment WHERE id = v_pay.id;
        RAISE EXCEPTION 'FAIL: receipt deleted';
    EXCEPTION WHEN SQLSTATE 'SYN01' THEN
        RAISE NOTICE 'PASS  receipts are immutable (void only)';
    END;

    UPDATE billing.bill
    SET status = 'CANCELLED', cancelled_at = now(), cancelled_by = 'smoke', cancellation_reason = 'smoke test'
    WHERE id = v_bill.id;
    RAISE NOTICE 'PASS  final bill can be cancelled with a reason';

    INSERT INTO billing.payment (patient_id, admission_id, payment_type, payment_mode, amount,
                                 reference_number, received_by)
    VALUES (v_p1.id, v_a1.id, 'ADVANCE', 'UPI', 5000, 'UPI-RRN-0001', 'smoke');
    SELECT advance_balance INTO v_amount FROM billing.v_unallocated_advance WHERE admission_id = v_a1.id;
    ASSERT v_amount = 5000, format('advance balance %s', v_amount);
    RAISE NOTICE 'PASS  IPD advance tracked as unallocated deposit';

    -- Discharge after releasing the bed
    UPDATE ipd.bed_assignment SET released_at = now(), released_by = 'smoke' WHERE id = v_ba2;
    UPDATE ipd.admission SET status = 'DISCHARGED', discharged_at = now() WHERE id = v_a1.id;
    RAISE NOTICE 'PASS  discharge after bed release';

    ---------------------------------------------------------------- clinical notes
    INSERT INTO clinical.clinical_note (encounter_id, note_type, content, author_id)
    VALUES (v_e1, 'HISTORY', 'Fever for 3 days', v_doctor) RETURNING id INTO v_note;
    UPDATE clinical.clinical_note SET content = 'Fever for 3 days with chills' WHERE id = v_note;
    UPDATE clinical.clinical_note SET signed_at = now() WHERE id = v_note;
    BEGIN
        UPDATE clinical.clinical_note SET content = 'tampered' WHERE id = v_note;
        RAISE EXCEPTION 'FAIL: signed note edited';
    EXCEPTION WHEN SQLSTATE 'SYN01' THEN
        RAISE NOTICE 'PASS  signed notes are immutable';
    END;
    INSERT INTO clinical.clinical_note (encounter_id, note_type, content, author_id, amends_note_id)
    VALUES (v_e1, 'HISTORY', 'Addendum: chills since day 2', v_doctor, v_note);
    UPDATE clinical.clinical_note SET is_entered_in_error = true WHERE id = v_note;
    RAISE NOTICE 'PASS  amendments and entered-in-error allowed';

    ---------------------------------------------------------------- prescriptions
    INSERT INTO clinical.prescription (encounter_id, patient_id, prescribed_by)
    VALUES (v_e1, v_p1.id, v_doctor) RETURNING id INTO v_rx;
    INSERT INTO clinical.prescription_item (prescription_id, line_no, drug_text, dose, route, frequency, duration_days)
    VALUES (v_rx, 1, 'Paracetamol 500 mg tablet', '1 tab', 'ORAL', 'TDS', 3);
    UPDATE clinical.prescription SET status = 'ISSUED', issued_at = now() WHERE id = v_rx;
    BEGIN
        INSERT INTO clinical.prescription_item (prescription_id, line_no, drug_text)
        VALUES (v_rx, 2, 'Added later');
        RAISE EXCEPTION 'FAIL: line added to an issued prescription';
    EXCEPTION WHEN SQLSTATE 'SYN01' THEN
        RAISE NOTICE 'PASS  issued prescriptions are locked';
    END;
    UPDATE clinical.prescription SET status = 'CANCELLED', cancelled_reason = 'smoke test' WHERE id = v_rx;

    ---------------------------------------------------------------- CDSS alerts
    BEGIN
        INSERT INTO clinical.cdss_alert (encounter_id, alert_type, severity, message, rule_code, kb_version,
                                         resolution, resolved_by, resolved_at)
        VALUES (v_e1, 'DRUG_DRUG', 'MAJOR', 'Smoke test', 'DDI-SMOKE', 'test', 'OVERRIDDEN', 'smoke', now());
        RAISE EXCEPTION 'FAIL: CDSS alert overridden without a reason';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS  overriding a CDSS alert needs a recorded reason';
    END;

    ---------------------------------------------------------------- lab parameters
    BEGIN
        INSERT INTO clinical.lab_analyte (service_id, code, name, ref_low, ref_high, critical_high)
        SELECT id, 'SMOKE', 'Smoke test', 3.5, 5.1, 4.0 FROM billing.service WHERE code = 'LAB-RFT';
        RAISE EXCEPTION 'FAIL: critical limit inside the reference interval';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS  a critical limit lies outside the reference interval';
    END;
    IF NOT EXISTS (SELECT 1 FROM clinical.lab_analyte a JOIN billing.service s ON s.id = a.service_id
                   WHERE s.code = 'LAB-RFT' AND a.code = 'CREAT' AND a.unit = 'mg/dL') THEN
        RAISE EXCEPTION 'FAIL: the renal function test has no CREAT (mg/dL) parameter for the renal guard';
    END IF;
    RAISE NOTICE 'PASS  the renal function test reports CREAT in mg/dL (read by the CDSS renal guard)';

    ---------------------------------------------------------------- immunizations
    BEGIN
        INSERT INTO clinical.immunization (encounter_id, patient_id, vaccine_code, dose_number, administered_at,
                                           lot_number, expiry_date, recorded_by)
        VALUES (v_e1, v_p1.id, 'TD', 1, now(), 'LOT1', current_date - 1, 'smoke');
        RAISE EXCEPTION 'FAIL: expired vaccine accepted';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
    BEGIN
        INSERT INTO clinical.immunization (encounter_id, patient_id, vaccine_code, dose_number, administered_at, recorded_by)
        VALUES (v_e1, v_p1.id, 'TD', 1, now(), 'smoke');
        RAISE EXCEPTION 'FAIL: dose given here without batch and expiry accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS  vaccinations given here need an in-date, traceable vial';
    END;
    INSERT INTO clinical.immunization (encounter_id, patient_id, vaccine_code, dose_number, administered_at,
                                       lot_number, expiry_date, recorded_by)
    VALUES (v_e1, v_p1.id, 'TD', 1, now(), 'LOT1', current_date + 180, 'smoke') RETURNING id INTO v_tmp;
    BEGIN
        INSERT INTO clinical.immunization (encounter_id, patient_id, vaccine_code, dose_number, administered_at,
                                           lot_number, expiry_date, recorded_by)
        VALUES (v_e1, v_p1.id, 'TD', 1, now(), 'LOT2', current_date + 180, 'smoke');
        RAISE EXCEPTION 'FAIL: same dose recorded twice';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
    BEGIN
        UPDATE clinical.immunization SET lot_number = 'LOT9' WHERE id = v_tmp;
        RAISE EXCEPTION 'FAIL: immunization edited';
    EXCEPTION WHEN SQLSTATE 'SYN01' THEN
        NULL;
    END;
    UPDATE clinical.immunization SET is_entered_in_error = true, entered_in_error_reason = 'smoke' WHERE id = v_tmp;
    INSERT INTO clinical.immunization (encounter_id, patient_id, vaccine_code, dose_number, administered_at,
                                       lot_number, expiry_date, recorded_by)
    VALUES (v_e1, v_p1.id, 'TD', 1, now(), 'LOT2', current_date + 180, 'smoke');
    RAISE NOTICE 'PASS  one record per dose; rows are immutable except marking entered in error';

    ---------------------------------------------------------------- ABDM
    INSERT INTO abdm.care_context (patient_id, encounter_id, reference_number, display, hi_types)
    VALUES (v_p1.id, v_e1, v_e1::text, 'OPD Consultation - General Medicine',
            ARRAY['OPConsultation', 'Prescription']::varchar[]);
    BEGIN
        INSERT INTO abdm.care_context (patient_id, encounter_id, reference_number, display, hi_types)
        VALUES (v_p2.id, v_e2, v_e2::text, 'Bad', ARRAY['XRayImages']::varchar[]);
        RAISE EXCEPTION 'FAIL: unknown HI type accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS  care contexts restricted to ABDM HI types';
    END;
    INSERT INTO abdm.gateway_message (request_id, direction, api_path, outcome)
    VALUES ('7b1e1d1c-0000-4000-8000-000000000001', 'INBOUND', '/v0.5/consents/hip/notify', 'RECEIVED');
    BEGIN
        INSERT INTO abdm.gateway_message (request_id, direction, api_path, outcome)
        VALUES ('7b1e1d1c-0000-4000-8000-000000000001', 'INBOUND', '/v0.5/consents/hip/notify', 'RECEIVED');
        RAISE EXCEPTION 'FAIL: duplicate gateway callback accepted';
    EXCEPTION WHEN unique_violation THEN
        RAISE NOTICE 'PASS  gateway callbacks are idempotent';
    END;
    BEGIN
        UPDATE abdm.care_context SET link_status = 'LINKED' WHERE encounter_id = v_e1;
        RAISE EXCEPTION 'FAIL: care context linked without ABHA address and time';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS  a linked care context records the ABHA address and time';
    END;
    INSERT INTO abdm.patient_share (gateway_request_id, share_date, context, token_number, abha_address, name,
                                    gender, expires_at)
    VALUES (gen_random_uuid(), core.ist_today(), 'SMOKE', 1, 'smoke.test@sbx', 'Smoke Test', 'M', now() + interval '30 minutes');
    BEGIN
        INSERT INTO abdm.patient_share (gateway_request_id, share_date, context, token_number, abha_address, name,
                                        gender, expires_at)
        VALUES (gen_random_uuid(), core.ist_today(), 'SMOKE', 1, 'other.test@sbx', 'Other', 'F', now() + interval '30 minutes');
        RAISE EXCEPTION 'FAIL: duplicate Scan & Share token accepted';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
    BEGIN
        UPDATE abdm.patient_share SET status = 'REGISTERED' WHERE context = 'SMOKE';
        RAISE EXCEPTION 'FAIL: Scan & Share entry registered without a patient';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS  Scan & Share tokens are unique per counter and day; registration names the patient';
    END;
    BEGIN
        INSERT INTO abdm.discovery_request (transaction_id, gateway_request_id, abha_address, outcome, expires_at)
        VALUES (gen_random_uuid(), gen_random_uuid(), 'smoke.test@sbx', 'MATCHED', now() + interval '30 minutes');
        RAISE EXCEPTION 'FAIL: discovery MATCHED without a patient';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
    INSERT INTO abdm.discovery_request (transaction_id, gateway_request_id, abha_address, patient_id, outcome, expires_at)
    VALUES ('7b1e1d1c-0000-4000-8000-0000000000d1', gen_random_uuid(), 'smoke.test@sbx', v_p1.id, 'MATCHED',
            now() + interval '30 minutes');
    BEGIN
        INSERT INTO abdm.link_session (link_reference, transaction_id, gateway_request_id, abha_address, patient_id,
                                       care_context_ids, otp_salt, otp_hash, expires_at)
        VALUES ('smoke-link', '7b1e1d1c-0000-4000-8000-0000000000d1', gen_random_uuid(), 'smoke.test@sbx', v_p1.id,
                ARRAY[gen_random_uuid()], repeat('a', 32), '123456', now() + interval '10 minutes');
        RAISE EXCEPTION 'FAIL: link session stored something other than an OTP hash';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS  discoveries name the matched patient; link sessions store only an OTP hash';
    END;
    BEGIN
        INSERT INTO abdm.abha_enrolment (id, abha_number, outcome, consent_version, performed_by)
        VALUES (gen_random_uuid(), '91111122223333', 'FAILED', '1.4', 'smoke');
        RAISE EXCEPTION 'FAIL: failed ABHA enrolment recorded an ABHA number';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS  ABHA enrolments record an ABHA (and its verification) only when one was created';
    END;

    ---------------------------------------------------------------- audit
    INSERT INTO audit.event_log (actor, action, entity_type, entity_id, patient_id, service)
    VALUES ('smoke', 'READ', 'patient', v_p1.id::text, v_p1.id, 'smoke-test') RETURNING id INTO v_audit;
    BEGIN
        UPDATE audit.event_log SET actor = 'someone-else' WHERE id = v_audit;
        RAISE EXCEPTION 'FAIL: audit row updated';
    EXCEPTION WHEN SQLSTATE 'SYN01' THEN
        NULL;
    END;
    BEGIN
        DELETE FROM audit.event_log WHERE id = v_audit;
        RAISE EXCEPTION 'FAIL: audit row deleted';
    EXCEPTION WHEN SQLSTATE 'SYN01' THEN
        RAISE NOTICE 'PASS  audit log is append-only';
    END;

    ---------------------------------------------------------------- terminology
    IF EXISTS (SELECT 1 FROM clinical.lab_analyte WHERE loinc_code IS NULL)
       OR EXISTS (SELECT 1 FROM billing.service WHERE category = 'LABORATORY' AND loinc_code IS NULL) THEN
        RAISE EXCEPTION 'FAIL: a seeded lab test or parameter has no LOINC code';
    END IF;
    RAISE NOTICE 'PASS  seeded lab tests and parameters carry LOINC codes';

    ---------------------------------------------------------------- privileges
    BEGIN
        EXECUTE 'CREATE TABLE core.smoke_ddl (x int)';
        RAISE EXCEPTION 'FAIL: application role can run DDL (connected as %)', current_user;
    EXCEPTION WHEN insufficient_privilege THEN
        RAISE NOTICE 'PASS  application role (%) cannot run DDL', current_user;
    END;

    RAISE NOTICE '';
    RAISE NOTICE 'ALL SCHEMA CHECKS PASSED';
END
$test$;

ROLLBACK;
