-- =============================================================================
--  Syncra HIMS — database schema v1 (PostgreSQL 16)
--
--  Applied once, on first boot of an empty data volume, by
--  infra/postgres/initdb/10-apply-schema.sh (as the schema owner). Later changes
--  ship as Flyway migrations V2+, with this file as the V1 baseline.
--
--  Schemas (one per bounded context / owning service)
--    core      departments, staff, document numbering, shared helpers
--    patient   registration and UHID (SYNC-YYYYMMDD-XXXX)
--    clinical  encounters and the EMR: vitals, complaints, diagnoses, notes,
--              allergies, prescriptions, investigation orders, CDSS alerts
--    opd       outpatient token queue
--    ipd       wards, the 50 beds, admissions, bed assignments
--    pharmacy  drug formulary (generics, branded products)
--    billing   service catalogue, tariff plans, bills, payments
--    imaging   PACS (Orthanc) study index
--    abdm      ABHA verification, care contexts, consents, HIP data transfers
--    audit     append-only access/audit trail
--
--  Conventions
--    * uuid primary keys (gen_random_uuid), timestamptz everywhere.
--    * Business dates use the hospital's time zone via core.ist_today().
--    * Enumerations are varchar + CHECK (JPA-friendly, evolvable).
--    * Mutable aggregates carry `version` for JPA optimistic locking.
--    * Clinical rows are never deleted: they are marked entered-in-error,
--      signed/issued/finalised records are immutable (enforced by triggers).
--    * Custom SQLSTATEs raised by this schema, for services to map:
--        SYN01  record is immutable in its current state      (HTTP 409)
--        SYN02  numbering capacity exhausted                   (HTTP 503)
--        SYN03  bed or ward unavailable for this patient       (HTTP 409)
--        SYN04  workflow precondition not met                  (HTTP 422)
-- =============================================================================

SET client_min_messages = warning;

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;    -- digest() for hashing
CREATE EXTENSION IF NOT EXISTS pg_trgm;     -- fuzzy name search
CREATE EXTENSION IF NOT EXISTS btree_gist;  -- exclusion constraints (bed double-booking, tariff overlap)

CREATE SCHEMA core;
CREATE SCHEMA patient;
CREATE SCHEMA clinical;
CREATE SCHEMA opd;
CREATE SCHEMA ipd;
CREATE SCHEMA pharmacy;
CREATE SCHEMA billing;
CREATE SCHEMA imaging;
CREATE SCHEMA abdm;
CREATE SCHEMA audit;
CREATE SCHEMA iam;

COMMENT ON SCHEMA core     IS 'Shared reference data, staff and document numbering';
COMMENT ON SCHEMA patient  IS 'Patient registration and UHID';
COMMENT ON SCHEMA clinical IS 'Encounters and the electronic medical record';
COMMENT ON SCHEMA opd      IS 'Outpatient department token queue';
COMMENT ON SCHEMA ipd      IS 'Inpatient wards, beds, admissions';
COMMENT ON SCHEMA pharmacy IS 'Drug formulary';
COMMENT ON SCHEMA billing  IS 'Tariffs, bills and payments';
COMMENT ON SCHEMA imaging  IS 'PACS study index (Orthanc)';
COMMENT ON SCHEMA abdm     IS 'Ayushman Bharat Digital Mission integration (ABHA, HIP)';
COMMENT ON SCHEMA audit    IS 'Append-only audit trail';


-- =============================================================================
--  CORE
-- =============================================================================

CREATE FUNCTION core.ist_today() RETURNS date
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT (now() AT TIME ZONE 'Asia/Kolkata')::date
$$;
COMMENT ON FUNCTION core.ist_today() IS
    'Current business date in the hospital time zone, independent of server/session TimeZone';

CREATE FUNCTION core.set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END
$$;

CREATE FUNCTION core.forbid_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not permitted', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'SYN01';
END
$$;

-- Indian financial year (April-March) label, e.g. 2026-11-20 -> '26-27'
CREATE FUNCTION core.fiscal_year_label(p_day date) RETURNS varchar
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT lpad((fy % 100)::text, 2, '0') || '-' || lpad(((fy + 1) % 100)::text, 2, '0')
    FROM (SELECT CASE WHEN extract(month FROM p_day) >= 4
                      THEN extract(year FROM p_day)::int
                      ELSE extract(year FROM p_day)::int - 1 END AS fy) AS s
$$;

CREATE TABLE core.document_counter (
    doc_type    varchar(3) NOT NULL CHECK (doc_type ~ '^[A-Z]{3}$'),
    fiscal_year varchar(5) NOT NULL CHECK (fiscal_year ~ '^[0-9]{2}-[0-9]{2}$'),
    last_value  integer    NOT NULL CHECK (last_value >= 1),
    PRIMARY KEY (doc_type, fiscal_year)
);
COMMENT ON TABLE core.document_counter IS
    'Per-financial-year serials for bills, receipts, admissions, accession numbers';

-- Gap-free, concurrency-safe document numbers: '{TYPE}/{YY-YY}/{NNNNNN}' (16 chars,
-- the GST Rule 46/49 maximum). The counter row stays locked until the calling
-- transaction ends, so a rollback returns the number.
--   OPB OPD bill   IPB IPD bill   PHB pharmacy bill   ERB emergency bill
--   RCT receipt    RFD refund     ADM admission       RAD radiology accession
CREATE FUNCTION core.next_document_number(p_doc_type varchar, p_at timestamptz DEFAULT now())
RETURNS varchar
LANGUAGE plpgsql AS $$
DECLARE
    v_fy  varchar := core.fiscal_year_label((p_at AT TIME ZONE 'Asia/Kolkata')::date);
    v_seq integer;
BEGIN
    INSERT INTO core.document_counter AS c (doc_type, fiscal_year, last_value)
    VALUES (p_doc_type, v_fy, 1)
    ON CONFLICT (doc_type, fiscal_year) DO UPDATE SET last_value = c.last_value + 1
    RETURNING c.last_value INTO v_seq;

    IF v_seq > 999999 THEN
        RAISE EXCEPTION '% numbering exhausted for financial year %', p_doc_type, v_fy
            USING ERRCODE = 'SYN02';
    END IF;
    RETURN p_doc_type || '/' || v_fy || '/' || lpad(v_seq::text, 6, '0');
END
$$;

CREATE TABLE core.department (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    code        varchar(8)   NOT NULL UNIQUE CHECK (code ~ '^[A-Z]{2,8}$'),
    name        varchar(100) NOT NULL,
    is_clinical boolean      NOT NULL DEFAULT true,
    opd_enabled boolean      NOT NULL DEFAULT true,
    is_active   boolean      NOT NULL DEFAULT true,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE core.staff (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    iam_subject          varchar(64)  UNIQUE,
    employee_code        varchar(20)  NOT NULL UNIQUE,
    title                varchar(10),
    full_name            varchar(150) NOT NULL,
    staff_role           varchar(20)  NOT NULL CHECK (staff_role IN (
                             'DOCTOR', 'NURSE', 'RECEPTIONIST', 'BILLING_CLERK', 'PHARMACIST',
                             'LAB_TECHNICIAN', 'RADIOGRAPHER', 'RADIOLOGIST', 'MRD_OFFICER',
                             'ADMINISTRATOR')),
    department_id        uuid         REFERENCES core.department (id),
    qualification        varchar(150),
    council_registration varchar(40),
    hpr_id               varchar(32)  UNIQUE,
    mobile               varchar(10)  CHECK (mobile ~ '^[6-9][0-9]{9}$'),
    email                varchar(254),
    is_active            boolean      NOT NULL DEFAULT true,
    version              bigint       NOT NULL DEFAULT 0,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT staff_doctor_registration
        CHECK (staff_role NOT IN ('DOCTOR', 'RADIOLOGIST') OR council_registration IS NOT NULL)
);
COMMENT ON COLUMN core.staff.iam_subject IS 'JWT "sub" of this person''s login';
COMMENT ON COLUMN core.staff.council_registration IS 'NMC / State Medical Council registration number (printed on prescriptions)';
COMMENT ON COLUMN core.staff.hpr_id IS 'ABDM Healthcare Professionals Registry ID';
CREATE INDEX staff_department_idx ON core.staff (department_id);


-- =============================================================================
--  PATIENT
-- =============================================================================

CREATE TABLE patient.uhid_counter (
    counter_date date    PRIMARY KEY,
    last_value   integer NOT NULL CHECK (last_value >= 1)
);

-- UHID format SYNC-YYYYMMDD-XXXX: registration date (IST) + 4-digit daily serial.
CREATE FUNCTION patient.next_uhid(p_at timestamptz DEFAULT now()) RETURNS varchar
LANGUAGE plpgsql AS $$
DECLARE
    v_day date := (p_at AT TIME ZONE 'Asia/Kolkata')::date;
    v_seq integer;
BEGIN
    INSERT INTO patient.uhid_counter AS c (counter_date, last_value)
    VALUES (v_day, 1)
    ON CONFLICT (counter_date) DO UPDATE SET last_value = c.last_value + 1
    RETURNING c.last_value INTO v_seq;

    IF v_seq > 9999 THEN
        RAISE EXCEPTION 'UHID capacity exhausted for %: SYNC-YYYYMMDD-XXXX allows 9999 registrations per day', v_day
            USING ERRCODE = 'SYN02';
    END IF;
    RETURN 'SYNC-' || to_char(v_day, 'YYYYMMDD') || '-' || lpad(v_seq::text, 4, '0');
END
$$;

CREATE TABLE patient.patient (
    id                       uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    uhid                     varchar(18)  NOT NULL UNIQUE DEFAULT patient.next_uhid()
                                          CHECK (uhid ~ '^SYNC-[0-9]{8}-[0-9]{4}$'),
    title                    varchar(10)  CHECK (title IN ('Mr', 'Mrs', 'Ms', 'Miss', 'Master', 'Baby',
                                                           'Dr', 'Shri', 'Smt', 'Kumari')),
    first_name               varchar(60)  NOT NULL CHECK (btrim(first_name) <> ''),
    middle_name              varchar(60),
    last_name                varchar(60),
    full_name                text         GENERATED ALWAYS AS (
                                              first_name
                                              || COALESCE(' ' || middle_name, '')
                                              || COALESCE(' ' || last_name, '')) STORED,
    gender                   char(1)      NOT NULL CHECK (gender IN ('M', 'F', 'O', 'U')),
    date_of_birth            date         CHECK (date_of_birth >= DATE '1900-01-01'),
    dob_is_estimated         boolean      NOT NULL DEFAULT false,
    blood_group              varchar(3)   CHECK (blood_group IN ('A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-')),
    marital_status           varchar(10)  CHECK (marital_status IN ('SINGLE', 'MARRIED', 'WIDOWED', 'DIVORCED', 'SEPARATED')),
    relation_type            varchar(3)   CHECK (relation_type IN ('S/O', 'D/O', 'W/O', 'H/O', 'M/O', 'F/O', 'C/O')),
    relation_name            varchar(150),
    mobile                   varchar(10)  CHECK (mobile ~ '^[6-9][0-9]{9}$'),
    alternate_mobile         varchar(10)  CHECK (alternate_mobile ~ '^[6-9][0-9]{9}$'),
    email                    varchar(254),
    address_line             varchar(250),
    city_village             varchar(100),
    district_name            varchar(100),
    district_lgd_code        integer,
    state_name               varchar(60),
    state_lgd_code           smallint,
    pincode                  char(6)      CHECK (pincode ~ '^[1-9][0-9]{5}$'),
    abha_number              char(14)     CHECK (abha_number ~ '^[0-9]{14}$'),
    abha_address             varchar(100) CHECK (abha_address ~ '^[A-Za-z0-9._-]+@[a-z]+$'),
    abha_verified_at         timestamptz,
    emergency_contact_name   varchar(150),
    emergency_contact_mobile varchar(10)  CHECK (emergency_contact_mobile ~ '^[6-9][0-9]{9}$'),
    registration_source      varchar(20)  NOT NULL DEFAULT 'WALK_IN' CHECK (registration_source IN (
                                              'WALK_IN', 'ABHA_SCAN_SHARE', 'REFERRAL', 'EMERGENCY', 'TELECONSULT')),
    is_confidential          boolean      NOT NULL DEFAULT false,
    is_deceased              boolean      NOT NULL DEFAULT false,
    deceased_at              timestamptz,
    merged_into_id           uuid         REFERENCES patient.patient (id),
    is_active                boolean      NOT NULL DEFAULT true,
    version                  bigint       NOT NULL DEFAULT 0,
    created_at               timestamptz  NOT NULL DEFAULT now(),
    created_by               varchar(64)  NOT NULL DEFAULT 'system',
    updated_at               timestamptz  NOT NULL DEFAULT now(),
    updated_by               varchar(64),
    CONSTRAINT patient_deceased_consistent CHECK (is_deceased OR deceased_at IS NULL),
    CONSTRAINT patient_not_merged_into_self CHECK (merged_into_id IS DISTINCT FROM id),
    CONSTRAINT patient_merged_is_inactive CHECK (merged_into_id IS NULL OR NOT is_active)
);
COMMENT ON TABLE patient.patient IS 'Master patient index. UHID is assigned by default and is permanent';
COMMENT ON COLUMN patient.patient.dob_is_estimated IS 'True when only an age was given at registration';
COMMENT ON COLUMN patient.patient.relation_type IS 'Relation printed on Indian hospital records (S/O, W/O, ...)';
COMMENT ON COLUMN patient.patient.abha_number IS '14-digit ABHA number, stored without hyphens';
COMMENT ON COLUMN patient.patient.is_confidential IS 'Restricted record (VIP, sensitive diagnosis): services must apply break-glass access';

CREATE UNIQUE INDEX patient_abha_number_uq ON patient.patient (abha_number)
    WHERE abha_number IS NOT NULL AND merged_into_id IS NULL;
CREATE UNIQUE INDEX patient_abha_address_uq ON patient.patient (lower(abha_address))
    WHERE abha_address IS NOT NULL AND merged_into_id IS NULL;
CREATE INDEX patient_full_name_trgm ON patient.patient USING gin (full_name gin_trgm_ops);
CREATE INDEX patient_mobile_idx ON patient.patient (mobile) WHERE mobile IS NOT NULL;
CREATE INDEX patient_dob_gender_idx ON patient.patient (date_of_birth, gender);

CREATE FUNCTION patient.guard_uhid() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.uhid IS DISTINCT FROM OLD.uhid THEN
        RAISE EXCEPTION 'UHID % is permanent and cannot be changed', OLD.uhid USING ERRCODE = 'SYN01';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_patient_guard_uhid
    BEFORE UPDATE OF uhid ON patient.patient
    FOR EACH ROW EXECUTE FUNCTION patient.guard_uhid();

CREATE TABLE patient.patient_identifier (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id  uuid        NOT NULL REFERENCES patient.patient (id),
    id_type     varchar(20) NOT NULL CHECK (id_type IN (
                    'PMJAY', 'CGHS', 'ECHS', 'ESIC', 'INSURANCE_MEMBER', 'EMPLOYEE', 'PASSPORT',
                    'DRIVING_LICENCE', 'VOTER_ID', 'PAN', 'RATION_CARD', 'OTHER')),
    id_value    varchar(50) NOT NULL,
    issuer      varchar(100),
    valid_until date,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  varchar(64) NOT NULL DEFAULT 'system',
    CONSTRAINT patient_identifier_uq UNIQUE NULLS NOT DISTINCT (id_type, id_value, issuer)
);
COMMENT ON TABLE patient.patient_identifier IS
    'Scheme/insurance/government IDs. Aadhaar numbers are deliberately not storable (Aadhaar Act 2016 s.29); identity is established through ABHA';
CREATE INDEX patient_identifier_patient_idx ON patient.patient_identifier (patient_id);


-- =============================================================================
--  CLINICAL: encounter (anchor for EMR, billing and ABDM care contexts)
-- =============================================================================

CREATE TABLE clinical.encounter (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id          uuid        NOT NULL REFERENCES patient.patient (id),
    encounter_type      varchar(10) NOT NULL CHECK (encounter_type IN ('OPD', 'IPD', 'EMERGENCY', 'DAYCARE')),
    department_id       uuid        NOT NULL REFERENCES core.department (id),
    attending_doctor_id uuid        REFERENCES core.staff (id),
    status              varchar(12) NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN (
                            'PLANNED', 'ARRIVED', 'IN_PROGRESS', 'FINISHED', 'CANCELLED')),
    started_at          timestamptz NOT NULL DEFAULT now(),
    ended_at            timestamptz,
    is_mlc              boolean     NOT NULL DEFAULT false,
    mlc_number          varchar(30),
    referred_by         varchar(150),
    version             bigint      NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    created_by          varchar(64) NOT NULL DEFAULT 'system',
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          varchar(64),
    CONSTRAINT encounter_period CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT encounter_finished_has_end CHECK (status <> 'FINISHED' OR ended_at IS NOT NULL),
    -- Lets child tables prove (encounter, patient) consistency with a composite FK
    CONSTRAINT encounter_id_patient_uq UNIQUE (id, patient_id)
);
COMMENT ON TABLE clinical.encounter IS 'One OPD visit, IPD stay, emergency or day-care episode; FHIR Encounter; ABDM care context';
COMMENT ON COLUMN clinical.encounter.is_mlc IS 'Medico-legal case';
CREATE INDEX encounter_patient_idx ON clinical.encounter (patient_id, started_at DESC);
CREATE INDEX encounter_doctor_active_idx ON clinical.encounter (attending_doctor_id)
    WHERE status IN ('ARRIVED', 'IN_PROGRESS');


-- =============================================================================
--  BILLING catalogue: services, tariff plans, rates (needed by OPD/IPD/orders)
-- =============================================================================

CREATE TABLE billing.service (
    id             uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    code           varchar(16)  NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9-]{2,16}$'),
    name           varchar(150) NOT NULL,
    category       varchar(16)  NOT NULL CHECK (category IN (
                       'REGISTRATION', 'CONSULTATION', 'ADMISSION', 'BED', 'NURSING', 'DOCTOR_VISIT',
                       'PROCEDURE', 'LABORATORY', 'RADIOLOGY', 'PHARMACY', 'CONSUMABLE', 'OT',
                       'PACKAGE', 'MISC')),
    department_id  uuid         REFERENCES core.department (id),
    sac_code       varchar(8)   CHECK (sac_code ~ '^[0-9]{4,8}$'),
    charge_unit    varchar(10)  NOT NULL DEFAULT 'PER_UNIT' CHECK (charge_unit IN (
                       'PER_UNIT', 'PER_VISIT', 'PER_DAY', 'PER_HOUR', 'PER_TEST', 'PER_STUDY')),
    dicom_modality varchar(4)   CHECK (dicom_modality IN ('CR', 'DX', 'CT', 'MR', 'US', 'MG', 'XA', 'RF', 'NM', 'PT', 'OT')),
    -- LOINC code of a laboratory test or panel (the code of its ABDM DiagnosticReport)
    loinc_code     varchar(10)  CHECK (loinc_code ~ '^[0-9]{1,7}-[0-9]$'),
    loinc_display  varchar(150),
    is_active      boolean      NOT NULL DEFAULT true,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT service_modality_is_radiology CHECK (dicom_modality IS NULL OR category = 'RADIOLOGY'),
    CONSTRAINT service_loinc_whole CHECK ((loinc_code IS NULL) = (loinc_display IS NULL))
);
COMMENT ON COLUMN billing.service.sac_code IS 'GST Services Accounting Code (9993xx for human health services)';
COMMENT ON COLUMN billing.service.dicom_modality IS 'Modality for radiology orders (drives accession numbers / worklists)';
CREATE INDEX service_name_trgm ON billing.service USING gin (name gin_trgm_ops);

CREATE TABLE billing.tariff_plan (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    code        varchar(16)  NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9_]{2,16}$'),
    name        varchar(100) NOT NULL,
    payer_type  varchar(16)  NOT NULL CHECK (payer_type IN ('SELF', 'GOVT_SCHEME', 'INSURANCE', 'CORPORATE', 'STAFF')),
    description text,
    is_default  boolean      NOT NULL DEFAULT false,
    is_active   boolean      NOT NULL DEFAULT true,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX tariff_plan_single_default ON billing.tariff_plan (is_default) WHERE is_default;

CREATE TABLE billing.tariff_rate (
    id             uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    tariff_plan_id uuid          NOT NULL REFERENCES billing.tariff_plan (id),
    service_id     uuid          NOT NULL REFERENCES billing.service (id),
    bed_category   varchar(12)   NOT NULL DEFAULT 'ALL' CHECK (bed_category IN (
                                     'ALL', 'GENERAL', 'SEMI_PRIVATE', 'PRIVATE', 'ICU')),
    amount         numeric(12,2) NOT NULL CHECK (amount >= 0),
    gst_rate       numeric(5,2)  NOT NULL DEFAULT 0 CHECK (gst_rate BETWEEN 0 AND 40),
    valid_from     date          NOT NULL,
    valid_to       date,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     varchar(64)   NOT NULL DEFAULT 'system',
    CONSTRAINT tariff_rate_validity CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT tariff_rate_no_overlap EXCLUDE USING gist (
        tariff_plan_id WITH =, service_id WITH =, bed_category WITH =,
        daterange(valid_from, valid_to, '[)') WITH &&)
);
COMMENT ON COLUMN billing.tariff_rate.bed_category IS
    'Rate applicable to a ward class; ALL = same rate everywhere. A class-specific rate overrides ALL';
COMMENT ON COLUMN billing.tariff_rate.gst_rate IS
    'Most clinical services are GST-exempt. Non-ICU room rent above Rs 5,000/day attracts 5% GST: set it on that rate row';
COMMENT ON COLUMN billing.tariff_rate.valid_to IS 'Exclusive end date; NULL = open-ended';

CREATE FUNCTION billing.resolve_rate(
    p_tariff_plan_id uuid,
    p_service_id     uuid,
    p_bed_category   varchar DEFAULT 'ALL',
    p_on             date    DEFAULT core.ist_today())
RETURNS TABLE (tariff_rate_id uuid, amount numeric, gst_rate numeric)
LANGUAGE sql STABLE AS $$
    SELECT r.id, r.amount, r.gst_rate
    FROM billing.tariff_rate AS r
    WHERE r.tariff_plan_id = p_tariff_plan_id
      AND r.service_id = p_service_id
      AND r.bed_category IN (p_bed_category, 'ALL')
      AND daterange(r.valid_from, r.valid_to, '[)') @> p_on
    ORDER BY (r.bed_category = p_bed_category) DESC
    LIMIT 1
$$;
COMMENT ON FUNCTION billing.resolve_rate(uuid, uuid, varchar, date) IS
    'Effective rate for a service under a plan on a date; ward-class-specific rate wins over ALL';


-- =============================================================================
--  OPD: token queue
-- =============================================================================

CREATE TABLE opd.token_counter (
    queue_date    date    NOT NULL,
    department_id uuid    NOT NULL REFERENCES core.department (id),
    last_value    integer NOT NULL CHECK (last_value >= 1),
    PRIMARY KEY (queue_date, department_id)
);

CREATE FUNCTION opd.next_token(p_department_id uuid, p_queue_date date DEFAULT core.ist_today())
RETURNS integer
LANGUAGE plpgsql AS $$
DECLARE
    v_token integer;
BEGIN
    INSERT INTO opd.token_counter AS c (queue_date, department_id, last_value)
    VALUES (p_queue_date, p_department_id, 1)
    ON CONFLICT (queue_date, department_id) DO UPDATE SET last_value = c.last_value + 1
    RETURNING c.last_value INTO v_token;
    RETURN v_token;
END
$$;

CREATE TABLE opd.visit (
    id                      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    encounter_id            uuid        NOT NULL UNIQUE,
    patient_id              uuid        NOT NULL,
    department_id           uuid        NOT NULL REFERENCES core.department (id),
    doctor_id               uuid        REFERENCES core.staff (id),
    visit_date              date        NOT NULL DEFAULT core.ist_today(),
    token_number            integer     NOT NULL CHECK (token_number > 0),
    visit_type              varchar(10) NOT NULL DEFAULT 'NEW' CHECK (visit_type IN ('NEW', 'FOLLOW_UP', 'REFERRAL', 'EMERGENCY')),
    priority                varchar(20) NOT NULL DEFAULT 'NORMAL' CHECK (priority IN (
                                'NORMAL', 'SENIOR_CITIZEN', 'PREGNANT', 'DIFFERENTLY_ABLED', 'CHILD_UNDER_5', 'EMERGENCY')),
    queue_status            varchar(16) NOT NULL DEFAULT 'WAITING' CHECK (queue_status IN (
                                'WAITING', 'CALLED', 'IN_CONSULTATION', 'ON_HOLD', 'COMPLETED', 'NO_SHOW', 'CANCELLED')),
    registered_at           timestamptz NOT NULL DEFAULT now(),
    called_at               timestamptz,
    consultation_started_at timestamptz,
    completed_at            timestamptz,
    follow_up_of_visit_id   uuid        REFERENCES opd.visit (id),
    version                 bigint      NOT NULL DEFAULT 0,
    created_at              timestamptz NOT NULL DEFAULT now(),
    created_by              varchar(64) NOT NULL DEFAULT 'system',
    updated_at              timestamptz NOT NULL DEFAULT now(),
    updated_by              varchar(64),
    CONSTRAINT visit_encounter_fk FOREIGN KEY (encounter_id, patient_id)
        REFERENCES clinical.encounter (id, patient_id),
    CONSTRAINT visit_token_uq UNIQUE (visit_date, department_id, token_number),
    CONSTRAINT visit_timeline CHECK (
        (consultation_started_at IS NULL OR consultation_started_at >= registered_at)
        AND (completed_at IS NULL OR completed_at >= registered_at))
);
COMMENT ON TABLE opd.visit IS 'OPD registration with a per-department daily token; token is auto-assigned when omitted';
CREATE INDEX visit_queue_idx ON opd.visit (visit_date, department_id, queue_status, token_number);
CREATE INDEX visit_doctor_queue_idx ON opd.visit (visit_date, doctor_id, queue_status) WHERE doctor_id IS NOT NULL;
CREATE INDEX visit_patient_idx ON opd.visit (patient_id, visit_date DESC);
-- A patient cannot hold two live tokens in the same department on the same day
CREATE UNIQUE INDEX visit_one_live_token ON opd.visit (patient_id, department_id, visit_date)
    WHERE queue_status IN ('WAITING', 'CALLED', 'IN_CONSULTATION', 'ON_HOLD');

CREATE FUNCTION opd.assign_token() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.token_number IS NULL THEN
        NEW.token_number := opd.next_token(NEW.department_id, NEW.visit_date);
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_visit_assign_token
    BEFORE INSERT ON opd.visit
    FOR EACH ROW EXECUTE FUNCTION opd.assign_token();


-- =============================================================================
--  IPD: wards, beds, admissions, bed assignments
-- =============================================================================

CREATE TABLE ipd.ward (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    code                 varchar(8)   NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9]{2,8}$'),
    name                 varchar(100) NOT NULL,
    ward_type            varchar(16)  NOT NULL CHECK (ward_type IN (
                             'GENERAL', 'PAEDIATRIC', 'MATERNITY', 'ICU', 'NICU', 'HDU',
                             'SEMI_PRIVATE', 'PRIVATE', 'ISOLATION', 'DAYCARE')),
    gender_restriction   varchar(3)   NOT NULL DEFAULT 'ANY' CHECK (gender_restriction IN ('M', 'F', 'ANY')),
    default_bed_category varchar(12)  NOT NULL CHECK (default_bed_category IN ('GENERAL', 'SEMI_PRIVATE', 'PRIVATE', 'ICU')),
    department_id        uuid         REFERENCES core.department (id),
    floor_label          varchar(20),
    is_active            boolean      NOT NULL DEFAULT true,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE ipd.bed (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    ward_id        uuid        NOT NULL REFERENCES ipd.ward (id),
    bed_code       varchar(12) NOT NULL UNIQUE,
    bed_category   varchar(12) NOT NULL CHECK (bed_category IN ('GENERAL', 'SEMI_PRIVATE', 'PRIVATE', 'ICU')),
    status         varchar(12) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN (
                       'AVAILABLE', 'RESERVED', 'OCCUPIED', 'CLEANING', 'MAINTENANCE', 'BLOCKED')),
    has_oxygen     boolean     NOT NULL DEFAULT false,
    has_monitor    boolean     NOT NULL DEFAULT false,
    has_ventilator boolean     NOT NULL DEFAULT false,
    is_active      boolean     NOT NULL DEFAULT true,
    version        bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
COMMENT ON COLUMN ipd.bed.status IS
    'Operational state. OCCUPIED/CLEANING are maintained by bed_assignment triggers; housekeeping sets AVAILABLE';
CREATE INDEX bed_ward_status_idx ON ipd.bed (ward_id, status);

CREATE TABLE ipd.admission (
    id                      uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    admission_number        varchar(16)   NOT NULL UNIQUE DEFAULT core.next_document_number('ADM'),
    encounter_id            uuid          NOT NULL UNIQUE,
    patient_id              uuid          NOT NULL,
    department_id           uuid          NOT NULL REFERENCES core.department (id),
    admitting_doctor_id     uuid          NOT NULL REFERENCES core.staff (id),
    admission_type          varchar(12)   NOT NULL CHECK (admission_type IN (
                                'ELECTIVE', 'EMERGENCY', 'MATERNITY', 'TRANSFER_IN', 'DAYCARE')),
    admitted_at             timestamptz   NOT NULL DEFAULT now(),
    provisional_diagnosis   varchar(500),
    expected_discharge_date date,
    tariff_plan_id          uuid          NOT NULL REFERENCES billing.tariff_plan (id),
    insurer_or_scheme       varchar(150),
    policy_number           varchar(50),
    preauth_number          varchar(50),
    preauth_amount          numeric(12,2) CHECK (preauth_amount >= 0),
    attendant_name          varchar(150),
    attendant_relation      varchar(30),
    attendant_mobile        varchar(10)   CHECK (attendant_mobile ~ '^[6-9][0-9]{9}$'),
    status                  varchar(20)   NOT NULL DEFAULT 'ADMITTED' CHECK (status IN (
                                'ADMITTED', 'DISCHARGE_INITIATED', 'DISCHARGED', 'LAMA', 'ABSCONDED',
                                'EXPIRED', 'TRANSFERRED_OUT', 'CANCELLED')),
    discharge_initiated_at  timestamptz,
    discharged_at           timestamptz,
    version                 bigint        NOT NULL DEFAULT 0,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    created_by              varchar(64)   NOT NULL DEFAULT 'system',
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    updated_by              varchar(64),
    CONSTRAINT admission_encounter_fk FOREIGN KEY (encounter_id, patient_id)
        REFERENCES clinical.encounter (id, patient_id),
    CONSTRAINT admission_discharge_after_admit CHECK (discharged_at IS NULL OR discharged_at >= admitted_at),
    CONSTRAINT admission_terminal_has_discharge_time CHECK (
        (status IN ('ADMITTED', 'DISCHARGE_INITIATED', 'CANCELLED')) = (discharged_at IS NULL))
);
COMMENT ON COLUMN ipd.admission.status IS 'LAMA = left against medical advice';
CREATE UNIQUE INDEX admission_one_active_per_patient ON ipd.admission (patient_id)
    WHERE status IN ('ADMITTED', 'DISCHARGE_INITIATED');
CREATE INDEX admission_admitted_at_idx ON ipd.admission (admitted_at DESC);

CREATE TABLE ipd.bed_assignment (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    admission_id uuid        NOT NULL REFERENCES ipd.admission (id),
    bed_id       uuid        NOT NULL REFERENCES ipd.bed (id),
    bed_category varchar(12) NOT NULL CHECK (bed_category IN ('GENERAL', 'SEMI_PRIVATE', 'PRIVATE', 'ICU')),
    assigned_at  timestamptz NOT NULL DEFAULT now(),
    released_at  timestamptz,
    reason       varchar(12) NOT NULL DEFAULT 'ADMISSION' CHECK (reason IN ('ADMISSION', 'TRANSFER', 'UPGRADE', 'DOWNGRADE')),
    assigned_by  varchar(64) NOT NULL DEFAULT 'system',
    released_by  varchar(64),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT bed_assignment_period CHECK (released_at IS NULL OR released_at > assigned_at),
    -- The core guarantees: no bed is ever double-booked, no patient holds two beds
    CONSTRAINT bed_no_double_booking EXCLUDE USING gist (
        bed_id WITH =, tstzrange(assigned_at, released_at, '[)') WITH &&),
    CONSTRAINT admission_one_bed_at_a_time EXCLUDE USING gist (
        admission_id WITH =, tstzrange(assigned_at, released_at, '[)') WITH &&)
);
COMMENT ON TABLE ipd.bed_assignment IS
    'Bed occupancy history. A transfer releases the current row and inserts a new one at the same instant';
COMMENT ON COLUMN ipd.bed_assignment.bed_category IS 'Ward class at assignment time (bed-charge billing); filled from the bed when omitted';
CREATE INDEX bed_assignment_admission_idx ON ipd.bed_assignment (admission_id, assigned_at);
CREATE UNIQUE INDEX bed_assignment_open_per_bed ON ipd.bed_assignment (bed_id) WHERE released_at IS NULL;

CREATE FUNCTION ipd.on_bed_assignment() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_bed    ipd.bed%ROWTYPE;
    v_ward   ipd.ward%ROWTYPE;
    v_gender char(1);
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO v_bed FROM ipd.bed WHERE id = NEW.bed_id FOR UPDATE;
        NEW.bed_category := COALESCE(NEW.bed_category, v_bed.bed_category);

        IF NEW.released_at IS NULL THEN
            IF NOT v_bed.is_active OR v_bed.status NOT IN ('AVAILABLE', 'RESERVED') THEN
                RAISE EXCEPTION 'Bed % is not available (status %)', v_bed.bed_code, v_bed.status
                    USING ERRCODE = 'SYN03';
            END IF;

            SELECT * INTO v_ward FROM ipd.ward WHERE id = v_bed.ward_id;
            SELECT p.gender INTO v_gender
            FROM ipd.admission AS a JOIN patient.patient AS p ON p.id = a.patient_id
            WHERE a.id = NEW.admission_id;
            IF v_ward.gender_restriction <> 'ANY' AND v_gender IS DISTINCT FROM v_ward.gender_restriction THEN
                RAISE EXCEPTION 'Ward % is restricted to gender %', v_ward.name, v_ward.gender_restriction
                    USING ERRCODE = 'SYN03';
            END IF;

            UPDATE ipd.bed SET status = 'OCCUPIED' WHERE id = NEW.bed_id;
        END IF;
        RETURN NEW;
    END IF;

    -- UPDATE: only the release of an open assignment is allowed
    IF (NEW.bed_id, NEW.admission_id, NEW.assigned_at, NEW.bed_category)
       IS DISTINCT FROM (OLD.bed_id, OLD.admission_id, OLD.assigned_at, OLD.bed_category) THEN
        RAISE EXCEPTION 'Bed assignments are history: record a transfer as a new assignment'
            USING ERRCODE = 'SYN01';
    END IF;
    IF OLD.released_at IS NOT NULL AND NEW.released_at IS DISTINCT FROM OLD.released_at THEN
        RAISE EXCEPTION 'Released bed assignment % cannot be changed', OLD.id USING ERRCODE = 'SYN01';
    END IF;
    IF OLD.released_at IS NULL AND NEW.released_at IS NOT NULL THEN
        UPDATE ipd.bed SET status = 'CLEANING' WHERE id = NEW.bed_id;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_bed_assignment
    BEFORE INSERT OR UPDATE ON ipd.bed_assignment
    FOR EACH ROW EXECUTE FUNCTION ipd.on_bed_assignment();
CREATE TRIGGER trg_bed_assignment_no_delete
    BEFORE DELETE ON ipd.bed_assignment
    FOR EACH ROW EXECUTE FUNCTION core.forbid_change();

CREATE FUNCTION ipd.guard_admission_exit() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status NOT IN ('ADMITTED', 'DISCHARGE_INITIATED')
       AND OLD.status IN ('ADMITTED', 'DISCHARGE_INITIATED')
       AND EXISTS (SELECT 1 FROM ipd.bed_assignment
                   WHERE admission_id = NEW.id AND released_at IS NULL) THEN
        RAISE EXCEPTION 'Admission % still occupies a bed: release it before setting status %',
            NEW.admission_number, NEW.status USING ERRCODE = 'SYN04';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_admission_guard_exit
    BEFORE UPDATE OF status ON ipd.admission
    FOR EACH ROW EXECUTE FUNCTION ipd.guard_admission_exit();


-- =============================================================================
--  PHARMACY formulary and CDSS knowledge base
-- =============================================================================

CREATE TABLE pharmacy.generic (
    id         uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       varchar(150) NOT NULL,
    atc_code   varchar(7)   CHECK (atc_code ~ '^[A-Z][0-9]{2}[A-Z]{2}[0-9]{2}$'),
    drug_class varchar(100),
    is_active  boolean      NOT NULL DEFAULT true,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now()
);
COMMENT ON TABLE pharmacy.generic IS 'Active ingredients (molecules); CDSS checks operate at this level';
COMMENT ON COLUMN pharmacy.generic.atc_code IS 'WHO ATC level-5 code';
COMMENT ON COLUMN pharmacy.generic.drug_class IS 'Pharmacological class used for class-level allergy checks (e.g. Penicillins)';
CREATE UNIQUE INDEX generic_name_uq ON pharmacy.generic (lower(name));
CREATE INDEX generic_name_trgm ON pharmacy.generic USING gin (name gin_trgm_ops);

CREATE TABLE pharmacy.drug (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    code          varchar(16)  UNIQUE CHECK (code ~ '^[A-Z0-9-]{2,16}$'),
    brand_name    varchar(150) NOT NULL,
    manufacturer  varchar(150),
    dosage_form   varchar(20)  NOT NULL CHECK (dosage_form IN (
                      'TABLET', 'CAPSULE', 'SYRUP', 'SUSPENSION', 'INJECTION', 'INFUSION', 'DROPS',
                      'OINTMENT', 'CREAM', 'GEL', 'LOTION', 'INHALER', 'NEBULE', 'SUPPOSITORY',
                      'PATCH', 'POWDER', 'SPRAY', 'OTHER')),
    strength      varchar(60),
    default_route varchar(12)  CHECK (default_route IN (
                      'ORAL', 'IV', 'IM', 'SC', 'TOPICAL', 'INHALATION', 'NASAL', 'OPHTHALMIC',
                      'OTIC', 'RECTAL', 'VAGINAL', 'SUBLINGUAL', 'TRANSDERMAL', 'OTHER')),
    schedule      varchar(4)   CHECK (schedule IN ('OTC', 'G', 'H', 'H1', 'X')),
    is_high_alert boolean      NOT NULL DEFAULT false,
    is_nlem       boolean      NOT NULL DEFAULT false,
    -- Sale: HSN code and GST (medicines are taxable, unlike clinical services); MRP is per batch.
    hsn_code      varchar(8)   NOT NULL DEFAULT '3004' CHECK (hsn_code ~ '^[0-9]{4,8}$'),
    gst_rate      numeric(5,2) NOT NULL DEFAULT 12 CHECK (gst_rate IN (0, 5, 12, 18, 28)),
    dispensing_unit varchar(12) NOT NULL DEFAULT 'unit',
    -- Reorder when the stock in all stores falls below this (in dispensing units).
    reorder_level integer      NOT NULL DEFAULT 0 CHECK (reorder_level >= 0),
    is_active     boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);
COMMENT ON COLUMN pharmacy.drug.strength IS 'Display strength, e.g. "500 mg" or "250 mg/5 ml"';
COMMENT ON COLUMN pharmacy.drug.schedule IS 'Schedule under the Drugs and Cosmetics Rules, 1945';
COMMENT ON COLUMN pharmacy.drug.is_high_alert IS 'High-alert medication: requires double-check workflows';
COMMENT ON COLUMN pharmacy.drug.is_nlem IS 'Listed in the National List of Essential Medicines';
CREATE INDEX drug_brand_trgm ON pharmacy.drug USING gin (brand_name gin_trgm_ops);

CREATE TABLE pharmacy.drug_component (
    drug_id        uuid          NOT NULL REFERENCES pharmacy.drug (id) ON DELETE CASCADE,
    generic_id     uuid          NOT NULL REFERENCES pharmacy.generic (id),
    strength_value numeric(12,4) CHECK (strength_value > 0),
    strength_unit  varchar(12),
    PRIMARY KEY (drug_id, generic_id)
);
CREATE INDEX drug_component_generic_idx ON pharmacy.drug_component (generic_id);

-- Stores: the main store receives from suppliers; the dispensary (OPD pharmacy) dispenses.
CREATE TABLE pharmacy.store (
    id         uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    code       varchar(12)  NOT NULL UNIQUE CHECK (code ~ '^[A-Z][A-Z0-9-]{1,11}$'),
    name       varchar(100) NOT NULL,
    kind       varchar(12)  NOT NULL CHECK (kind IN ('MAIN', 'DISPENSARY', 'WARD')),
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now()
);

-- A manufacturer's batch: MRP (per dispensing unit, GST included) and expiry travel with it.
CREATE TABLE pharmacy.batch (
    id            uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    drug_id       uuid          NOT NULL REFERENCES pharmacy.drug (id),
    batch_number  varchar(30)   NOT NULL CHECK (batch_number ~ '^[A-Za-z0-9./-]{1,30}$'),
    expiry_date   date          NOT NULL,
    mrp           numeric(10,2) NOT NULL CHECK (mrp > 0),
    purchase_rate numeric(10,2) NOT NULL CHECK (purchase_rate >= 0),
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT batch_number_per_drug UNIQUE (drug_id, batch_number),
    CONSTRAINT batch_cost_within_mrp CHECK (purchase_rate <= mrp)
);

-- Quantity on hand per store and batch, in dispensing units; never negative.
CREATE TABLE pharmacy.stock (
    store_id   uuid          NOT NULL REFERENCES pharmacy.store (id),
    batch_id   uuid          NOT NULL REFERENCES pharmacy.batch (id),
    quantity   numeric(10,2) NOT NULL CHECK (quantity >= 0),
    updated_at timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, batch_id)
);

-- Every change of stock (append-only): the ledger behind the expiry logs and audits.
CREATE TABLE pharmacy.stock_movement (
    id           bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id     uuid          NOT NULL REFERENCES pharmacy.store (id),
    batch_id     uuid          NOT NULL REFERENCES pharmacy.batch (id),
    quantity     numeric(10,2) NOT NULL CHECK (quantity <> 0),
    kind         varchar(12)   NOT NULL CHECK (kind IN (
                     'RECEIPT', 'TRANSFER_OUT', 'TRANSFER_IN', 'DISPENSE', 'RETURN', 'WRITE_OFF')),
    reference_id uuid,
    reason       varchar(200),
    staff_id     uuid          NOT NULL REFERENCES core.staff (id),
    occurred_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT movement_sign CHECK ((kind IN ('RECEIPT', 'TRANSFER_IN', 'RETURN')) = (quantity > 0)),
    CONSTRAINT movement_write_off_reason CHECK (kind <> 'WRITE_OFF' OR reason IS NOT NULL)
);
CREATE INDEX stock_movement_batch_idx ON pharmacy.stock_movement (batch_id, occurred_at);
CREATE TRIGGER trg_stock_movement_append_only
    BEFORE UPDATE OR DELETE ON pharmacy.stock_movement
    FOR EACH ROW EXECUTE FUNCTION core.forbid_change();

CREATE TABLE pharmacy.goods_receipt (
    id             uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    receipt_number varchar(16)  NOT NULL UNIQUE DEFAULT core.next_document_number('GRN'),
    store_id       uuid         NOT NULL REFERENCES pharmacy.store (id),
    supplier       varchar(150) NOT NULL,
    invoice_number varchar(40)  NOT NULL,
    invoice_date   date         NOT NULL,
    received_by    uuid         NOT NULL REFERENCES core.staff (id),
    received_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT goods_receipt_invoice_once UNIQUE (supplier, invoice_number)
);

CREATE TABLE pharmacy.goods_receipt_line (
    id            uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    receipt_id    uuid          NOT NULL REFERENCES pharmacy.goods_receipt (id),
    batch_id      uuid          NOT NULL REFERENCES pharmacy.batch (id),
    quantity      numeric(10,2) NOT NULL CHECK (quantity > 0),
    free_quantity numeric(10,2) NOT NULL DEFAULT 0 CHECK (free_quantity >= 0)
);

-- A store asks another for stock (the dispensary from the main store); issued FEFO.
CREATE TABLE pharmacy.requisition (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    requisition_number varchar(16) NOT NULL UNIQUE DEFAULT core.next_document_number('REQ'),
    from_store_id      uuid        NOT NULL REFERENCES pharmacy.store (id),
    to_store_id        uuid        NOT NULL REFERENCES pharmacy.store (id),
    status             varchar(10) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'ISSUED', 'PARTIAL', 'CANCELLED')),
    requested_by       uuid        NOT NULL REFERENCES core.staff (id),
    requested_at       timestamptz NOT NULL DEFAULT now(),
    issued_by          uuid        REFERENCES core.staff (id),
    issued_at          timestamptz,
    CONSTRAINT requisition_between_stores CHECK (from_store_id <> to_store_id),
    CONSTRAINT requisition_issue_recorded CHECK ((status IN ('ISSUED', 'PARTIAL')) = (issued_at IS NOT NULL))
);

CREATE TABLE pharmacy.requisition_line (
    id                 uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    requisition_id     uuid          NOT NULL REFERENCES pharmacy.requisition (id),
    drug_id            uuid          NOT NULL REFERENCES pharmacy.drug (id),
    quantity_requested numeric(10,2) NOT NULL CHECK (quantity_requested > 0),
    quantity_issued    numeric(10,2) NOT NULL DEFAULT 0 CHECK (quantity_issued >= 0 AND quantity_issued <= quantity_requested),
    CONSTRAINT requisition_drug_once UNIQUE (requisition_id, drug_id)
);





-- =============================================================================
--  CLINICAL: EMR
-- =============================================================================

CREATE TABLE clinical.dictation_session (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    ai_session_id varchar(64)  NOT NULL UNIQUE,
    encounter_id  uuid         NOT NULL REFERENCES clinical.encounter (id),
    doctor_id     uuid         NOT NULL REFERENCES core.staff (id),
    language      varchar(8)   NOT NULL CHECK (language IN ('en-IN', 'hi-IN')),
    engine        varchar(40)  NOT NULL,
    audio_seconds numeric(8,3) NOT NULL DEFAULT 0 CHECK (audio_seconds >= 0),
    outcome       varchar(24)  NOT NULL,
    started_at    timestamptz  NOT NULL,
    ended_at      timestamptz,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT dictation_period CHECK (ended_at IS NULL OR ended_at >= started_at)
);
COMMENT ON TABLE clinical.dictation_session IS
    'Provenance of Shrutlekhan dictation. Audio is never stored; confirmed text lands in notes/vitals/prescriptions';
CREATE INDEX dictation_session_encounter_idx ON clinical.dictation_session (encounter_id);

CREATE TABLE clinical.vital_sign (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    encounter_id         uuid         NOT NULL REFERENCES clinical.encounter (id),
    recorded_at          timestamptz  NOT NULL DEFAULT now(),
    recorded_by          varchar(64)  NOT NULL,
    temperature_c        numeric(4,1) CHECK (temperature_c BETWEEN 25 AND 45),
    pulse_bpm            smallint     CHECK (pulse_bpm BETWEEN 20 AND 250),
    respiratory_rate     smallint     CHECK (respiratory_rate BETWEEN 4 AND 80),
    bp_systolic          smallint     CHECK (bp_systolic BETWEEN 40 AND 300),
    bp_diastolic         smallint     CHECK (bp_diastolic BETWEEN 20 AND 200),
    spo2_percent         smallint     CHECK (spo2_percent BETWEEN 30 AND 100),
    weight_kg            numeric(5,2) CHECK (weight_kg BETWEEN 0.3 AND 400),
    height_cm            numeric(5,1) CHECK (height_cm BETWEEN 20 AND 250),
    bmi                  numeric      GENERATED ALWAYS AS (
                                          round(weight_kg / ((height_cm / 100) * (height_cm / 100)), 2)) STORED,
    blood_glucose_mg_dl  smallint     CHECK (blood_glucose_mg_dl BETWEEN 10 AND 1500),
    pain_score           smallint     CHECK (pain_score BETWEEN 0 AND 10),
    source               varchar(10)  NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'DICTATION', 'DEVICE', 'IMPORTED')),
    dictation_session_id uuid         REFERENCES clinical.dictation_session (id),
    is_entered_in_error  boolean      NOT NULL DEFAULT false,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT vital_bp_pair CHECK (bp_systolic IS NULL OR bp_diastolic IS NULL OR bp_systolic > bp_diastolic),
    CONSTRAINT vital_dictation_provenance CHECK ((source = 'DICTATION') = (dictation_session_id IS NOT NULL)),
    CONSTRAINT vital_not_empty CHECK (num_nonnulls(
        temperature_c, pulse_bpm, respiratory_rate, bp_systolic, bp_diastolic, spo2_percent,
        weight_kg, height_cm, blood_glucose_mg_dl, pain_score) > 0)
);
CREATE INDEX vital_sign_encounter_idx ON clinical.vital_sign (encounter_id, recorded_at DESC);

CREATE TABLE clinical.complaint (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    encounter_id         uuid         NOT NULL REFERENCES clinical.encounter (id),
    description          varchar(500) NOT NULL,
    duration_value       smallint     CHECK (duration_value > 0),
    duration_unit        varchar(6)   CHECK (duration_unit IN ('HOURS', 'DAYS', 'WEEKS', 'MONTHS', 'YEARS')),
    severity             varchar(10)  CHECK (severity IN ('MILD', 'MODERATE', 'SEVERE')),
    snomed_code          varchar(18)  CHECK (snomed_code ~ '^[0-9]{6,18}$'),
    is_chief             boolean      NOT NULL DEFAULT false,
    source               varchar(10)  NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'DICTATION', 'IMPORTED')),
    dictation_session_id uuid         REFERENCES clinical.dictation_session (id),
    is_entered_in_error  boolean      NOT NULL DEFAULT false,
    recorded_by          varchar(64)  NOT NULL,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT complaint_duration_pair CHECK ((duration_value IS NULL) = (duration_unit IS NULL)),
    CONSTRAINT complaint_dictation_provenance CHECK ((source = 'DICTATION') = (dictation_session_id IS NOT NULL))
);
CREATE INDEX complaint_encounter_idx ON clinical.complaint (encounter_id);

CREATE TABLE clinical.diagnosis (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    encounter_id         uuid         NOT NULL REFERENCES clinical.encounter (id),
    icd10_code           varchar(8)   CHECK (icd10_code ~ '^[A-Z][0-9]{2}(\.[0-9A-Z]{1,4})?$'),
    snomed_code          varchar(18)  CHECK (snomed_code ~ '^[0-9]{6,18}$'),
    description          varchar(500) NOT NULL,
    diagnosis_type       varchar(12)  NOT NULL DEFAULT 'PROVISIONAL' CHECK (diagnosis_type IN ('PROVISIONAL', 'DIFFERENTIAL', 'FINAL')),
    is_primary           boolean      NOT NULL DEFAULT false,
    source               varchar(10)  NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'DICTATION', 'IMPORTED')),
    dictation_session_id uuid         REFERENCES clinical.dictation_session (id),
    is_entered_in_error  boolean      NOT NULL DEFAULT false,
    diagnosed_by         varchar(64)  NOT NULL,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT diagnosis_dictation_provenance CHECK ((source = 'DICTATION') = (dictation_session_id IS NOT NULL))
);
CREATE INDEX diagnosis_encounter_idx ON clinical.diagnosis (encounter_id);
CREATE INDEX diagnosis_icd10_idx ON clinical.diagnosis (icd10_code) WHERE icd10_code IS NOT NULL;
CREATE UNIQUE INDEX diagnosis_one_primary_final ON clinical.diagnosis (encounter_id)
    WHERE is_primary AND diagnosis_type = 'FINAL' AND NOT is_entered_in_error;

CREATE TABLE clinical.clinical_note (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    encounter_id         uuid        NOT NULL REFERENCES clinical.encounter (id),
    note_type            varchar(20) NOT NULL CHECK (note_type IN (
                             'HISTORY', 'EXAMINATION', 'PROGRESS', 'PROCEDURE', 'NURSING', 'ADVICE',
                             'DISCHARGE_SUMMARY', 'RADIOLOGY_REPORT', 'OTHER')),
    content              text        NOT NULL CHECK (length(content) BETWEEN 1 AND 100000),
    author_id            uuid        NOT NULL REFERENCES core.staff (id),
    source               varchar(10) NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'DICTATION', 'IMPORTED')),
    dictation_session_id uuid        REFERENCES clinical.dictation_session (id),
    signed_at            timestamptz,
    amends_note_id       uuid        REFERENCES clinical.clinical_note (id),
    is_entered_in_error  boolean     NOT NULL DEFAULT false,
    version              bigint      NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT note_dictation_provenance CHECK ((source = 'DICTATION') = (dictation_session_id IS NOT NULL))
);
COMMENT ON TABLE clinical.clinical_note IS
    'Free-text notes. Once signed a note is immutable; corrections are new notes with amends_note_id';
CREATE INDEX clinical_note_encounter_idx ON clinical.clinical_note (encounter_id, created_at);

CREATE FUNCTION clinical.guard_signed_note() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.signed_at IS NOT NULL THEN
            RAISE EXCEPTION 'Signed clinical note % cannot be deleted', OLD.id USING ERRCODE = 'SYN01';
        END IF;
        RETURN OLD;
    END IF;
    -- Signed notes: only the entered-in-error flag may change
    IF OLD.signed_at IS NOT NULL
       AND (NEW.encounter_id, NEW.note_type, NEW.content, NEW.author_id, NEW.source,
            NEW.dictation_session_id, NEW.signed_at, NEW.amends_note_id)
           IS DISTINCT FROM
           (OLD.encounter_id, OLD.note_type, OLD.content, OLD.author_id, OLD.source,
            OLD.dictation_session_id, OLD.signed_at, OLD.amends_note_id) THEN
        RAISE EXCEPTION 'Signed clinical note % is immutable; record an amendment instead', OLD.id
            USING ERRCODE = 'SYN01';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_clinical_note_guard
    BEFORE UPDATE OR DELETE ON clinical.clinical_note
    FOR EACH ROW EXECUTE FUNCTION clinical.guard_signed_note();

CREATE TABLE clinical.allergy (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id    uuid         NOT NULL REFERENCES patient.patient (id),
    allergen_type varchar(12)  NOT NULL CHECK (allergen_type IN ('DRUG', 'DRUG_CLASS', 'FOOD', 'ENVIRONMENT', 'OTHER')),
    generic_id    uuid         REFERENCES pharmacy.generic (id),
    drug_class    varchar(100),
    allergen_text varchar(200) NOT NULL,
    reaction      varchar(300),
    severity      varchar(16)  CHECK (severity IN ('MILD', 'MODERATE', 'SEVERE', 'LIFE_THREATENING')),
    status        varchar(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'RESOLVED', 'REFUTED')),
    recorded_by   varchar(64)  NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT allergy_drug_class_named CHECK (allergen_type <> 'DRUG_CLASS' OR drug_class IS NOT NULL)
);
CREATE INDEX allergy_active_patient_idx ON clinical.allergy (patient_id) WHERE status = 'ACTIVE';

CREATE TABLE clinical.prescription (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    encounter_id    uuid        NOT NULL,
    patient_id      uuid        NOT NULL,
    prescribed_by   uuid        NOT NULL REFERENCES core.staff (id),
    status          varchar(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ISSUED', 'CANCELLED')),
    issued_at       timestamptz,
    advice          text,
    follow_up_date  date,
    cancelled_reason varchar(300),
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT prescription_encounter_fk FOREIGN KEY (encounter_id, patient_id)
        REFERENCES clinical.encounter (id, patient_id),
    CONSTRAINT prescription_issued_has_time CHECK (status <> 'ISSUED' OR issued_at IS NOT NULL),
    CONSTRAINT prescription_cancel_reason CHECK (status <> 'CANCELLED' OR cancelled_reason IS NOT NULL)
);
CREATE INDEX prescription_encounter_idx ON clinical.prescription (encounter_id);
CREATE INDEX prescription_patient_idx ON clinical.prescription (patient_id, created_at DESC);

CREATE TABLE clinical.prescription_item (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    prescription_id      uuid         NOT NULL REFERENCES clinical.prescription (id),
    line_no              smallint     NOT NULL CHECK (line_no > 0),
    drug_id              uuid         REFERENCES pharmacy.drug (id),
    drug_text            varchar(200) NOT NULL,
    dose                 varchar(40),
    route                varchar(12)  CHECK (route IN (
                             'ORAL', 'IV', 'IM', 'SC', 'TOPICAL', 'INHALATION', 'NASAL', 'OPHTHALMIC',
                             'OTIC', 'RECTAL', 'VAGINAL', 'SUBLINGUAL', 'TRANSDERMAL', 'OTHER')),
    frequency            varchar(12)  CHECK (frequency IN (
                             'OD', 'BD', 'TDS', 'QID', 'HS', 'SOS', 'STAT', 'Q4H', 'Q6H', 'Q8H',
                             'WEEKLY', 'ALT_DAYS', 'OTHER')),
    timing               varchar(16)  CHECK (timing IN (
                             'BEFORE_FOOD', 'AFTER_FOOD', 'WITH_FOOD', 'EMPTY_STOMACH', 'BEDTIME', 'ANY')),
    duration_days        smallint     CHECK (duration_days BETWEEN 1 AND 365),
    quantity             numeric(8,2) CHECK (quantity > 0),
    instructions         varchar(300),
    source               varchar(10)  NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'DICTATION', 'IMPORTED')),
    dictation_session_id uuid         REFERENCES clinical.dictation_session (id),
    created_at           timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT prescription_item_line_uq UNIQUE (prescription_id, line_no),
    CONSTRAINT prescription_item_dictation_provenance CHECK ((source = 'DICTATION') = (dictation_session_id IS NOT NULL))
);
COMMENT ON COLUMN clinical.prescription_item.drug_text IS 'As printed (brand + strength); also covers non-formulary drugs';
CREATE INDEX prescription_item_drug_idx ON clinical.prescription_item (drug_id) WHERE drug_id IS NOT NULL;

CREATE FUNCTION clinical.guard_prescription() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Prescription % is %: cancel it instead', OLD.id, OLD.status USING ERRCODE = 'SYN01';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.status = 'CANCELLED'
       OR (OLD.status = 'ISSUED'
           AND (NEW.status <> 'CANCELLED'
                OR (NEW.encounter_id, NEW.patient_id, NEW.prescribed_by, NEW.issued_at, NEW.advice, NEW.follow_up_date)
                   IS DISTINCT FROM
                   (OLD.encounter_id, OLD.patient_id, OLD.prescribed_by, OLD.issued_at, OLD.advice, OLD.follow_up_date))) THEN
        RAISE EXCEPTION 'Prescription % is %: it can only be cancelled', OLD.id, OLD.status USING ERRCODE = 'SYN01';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_prescription_guard
    BEFORE UPDATE OR DELETE ON clinical.prescription
    FOR EACH ROW EXECUTE FUNCTION clinical.guard_prescription();

CREATE FUNCTION clinical.guard_prescription_item() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_prescription_id uuid;
    v_status          varchar;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_prescription_id := OLD.prescription_id;
    ELSE
        v_prescription_id := NEW.prescription_id;
        IF TG_OP = 'UPDATE' AND NEW.prescription_id <> OLD.prescription_id THEN
            RAISE EXCEPTION 'Prescription lines cannot move between prescriptions' USING ERRCODE = 'SYN01';
        END IF;
    END IF;

    SELECT status INTO v_status FROM clinical.prescription WHERE id = v_prescription_id FOR UPDATE;
    IF v_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'Prescription % is %: its lines can no longer change', v_prescription_id, v_status
            USING ERRCODE = 'SYN01';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;
CREATE TRIGGER trg_prescription_item_guard
    BEFORE INSERT OR UPDATE OR DELETE ON clinical.prescription_item
    FOR EACH ROW EXECUTE FUNCTION clinical.guard_prescription_item();

CREATE TABLE clinical.vaccine (
    code            varchar(16)  PRIMARY KEY CHECK (code ~ '^[A-Z0-9-]{2,16}$'),
    name            varchar(150) NOT NULL,
    target_disease  varchar(200) NOT NULL,
    default_route   varchar(12)  NOT NULL CHECK (default_route IN ('IM', 'SC', 'ORAL', 'ID', 'INTRANASAL')),
    default_dose_ml numeric(4,2) CHECK (default_dose_ml > 0),
    series_doses    smallint     CHECK (series_doses BETWEEN 1 AND 10),
    programme       varchar(10)  NOT NULL CHECK (programme IN ('UIP', 'ADULT', 'OTHER')),
    is_active       boolean      NOT NULL DEFAULT true
);
COMMENT ON TABLE clinical.vaccine IS
    'Vaccine catalogue (Universal Immunization Programme and others); edit to match the hospital''s stock';

CREATE TABLE clinical.immunization (
    id                      uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    encounter_id            uuid         NOT NULL,
    patient_id              uuid         NOT NULL,
    vaccine_code            varchar(16)  NOT NULL REFERENCES clinical.vaccine (code),
    status                  varchar(10)  NOT NULL DEFAULT 'COMPLETED' CHECK (status IN ('COMPLETED', 'NOT_DONE')),
    not_done_reason         varchar(200),
    dose_number             smallint     CHECK (dose_number BETWEEN 1 AND 10),
    administered_at         timestamptz  NOT NULL,
    -- false: an earlier dose transcribed from a vaccination card or certificate
    primary_source          boolean      NOT NULL DEFAULT true,
    report_origin           varchar(100),
    lot_number              varchar(40),
    manufacturer            varchar(100),
    expiry_date             date,
    route                   varchar(12)  CHECK (route IN ('IM', 'SC', 'ORAL', 'ID', 'INTRANASAL')),
    site                    varchar(16)  CHECK (site IN ('LEFT_ARM', 'RIGHT_ARM', 'LEFT_DELTOID', 'RIGHT_DELTOID',
                                                         'LEFT_THIGH', 'RIGHT_THIGH', 'ORAL', 'NASAL')),
    dose_ml                 numeric(4,2) CHECK (dose_ml > 0),
    administered_by         uuid         REFERENCES core.staff (id),
    next_due_date           date,
    notes                   varchar(500),
    recorded_by             varchar(64)  NOT NULL,
    is_entered_in_error     boolean      NOT NULL DEFAULT false,
    entered_in_error_reason varchar(300),
    created_at              timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT immunization_encounter_fk FOREIGN KEY (encounter_id, patient_id)
        REFERENCES clinical.encounter (id, patient_id),
    CONSTRAINT immunization_not_done_reason CHECK ((status = 'NOT_DONE') = (not_done_reason IS NOT NULL)),
    CONSTRAINT immunization_report_origin CHECK (primary_source = (report_origin IS NULL)),
    -- A dose given here is traceable to its vial: batch and expiry, and it was in date
    CONSTRAINT immunization_traceable CHECK (status <> 'COMPLETED' OR NOT primary_source
                                             OR (lot_number IS NOT NULL AND expiry_date IS NOT NULL)),
    CONSTRAINT immunization_in_date CHECK (expiry_date IS NULL
                                           OR expiry_date >= (administered_at AT TIME ZONE 'Asia/Kolkata')::date),
    CONSTRAINT immunization_next_due CHECK (next_due_date IS NULL
                                            OR next_due_date > (administered_at AT TIME ZONE 'Asia/Kolkata')::date),
    CONSTRAINT immunization_error_reason CHECK (is_entered_in_error = (entered_in_error_reason IS NOT NULL))
);
COMMENT ON TABLE clinical.immunization IS
    'Vaccinations given (or transcribed from a card, primary_source = false). Rows are never edited: mark entered-in-error';
CREATE INDEX immunization_patient_idx ON clinical.immunization (patient_id, administered_at DESC);
CREATE INDEX immunization_encounter_idx ON clinical.immunization (encounter_id);
-- One valid record per dose of a vaccine
CREATE UNIQUE INDEX immunization_one_dose ON clinical.immunization (patient_id, vaccine_code, dose_number)
    WHERE status = 'COMPLETED' AND dose_number IS NOT NULL AND NOT is_entered_in_error;

CREATE FUNCTION clinical.guard_immunization() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Immunization % cannot be deleted; mark it entered in error', OLD.id USING ERRCODE = 'SYN01';
    END IF;
    IF OLD.is_entered_in_error
       OR (to_jsonb(NEW) - 'is_entered_in_error' - 'entered_in_error_reason')
          IS DISTINCT FROM (to_jsonb(OLD) - 'is_entered_in_error' - 'entered_in_error_reason') THEN
        RAISE EXCEPTION 'Immunization % is immutable; only marking it entered in error is allowed', OLD.id
            USING ERRCODE = 'SYN01';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_immunization_guard
    BEFORE UPDATE OR DELETE ON clinical.immunization
    FOR EACH ROW EXECUTE FUNCTION clinical.guard_immunization();

CREATE TABLE clinical.cdss_alert (
    id                  uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    encounter_id        uuid          NOT NULL REFERENCES clinical.encounter (id),
    prescription_id     uuid          REFERENCES clinical.prescription (id),
    alert_type          varchar(20)   NOT NULL CHECK (alert_type IN (
                            'DRUG_DRUG', 'DRUG_ALLERGY', 'DUPLICATE_THERAPY', 'DOSE_RANGE', 'CONTRAINDICATION')),
    severity            varchar(16)   NOT NULL CHECK (severity IN ('CONTRAINDICATED', 'MAJOR', 'MODERATE', 'MINOR')),
    message             varchar(1000) NOT NULL,
    -- Rule in the AI service's CDSS knowledge base, and the knowledge-base version that raised it
    rule_code           varchar(64)   NOT NULL,
    kb_version          varchar(20)   NOT NULL,
    allergy_id          uuid          REFERENCES clinical.allergy (id),
    raised_at           timestamptz   NOT NULL DEFAULT now(),
    resolution          varchar(12)   NOT NULL DEFAULT 'PENDING' CHECK (resolution IN (
                            'PENDING', 'ACCEPTED', 'OVERRIDDEN', 'ACKNOWLEDGED')),
    resolved_by         varchar(64),
    resolved_at         timestamptz,
    override_reason     varchar(500),
    CONSTRAINT cdss_alert_resolution_time CHECK ((resolution = 'PENDING') = (resolved_at IS NULL)),
    CONSTRAINT cdss_alert_override_justified CHECK (
        resolution <> 'OVERRIDDEN' OR (override_reason IS NOT NULL AND resolved_by IS NOT NULL))
);
COMMENT ON TABLE clinical.cdss_alert IS
    'Every CDSS alert shown to a prescriber and how it was resolved (ACCEPTED = order changed; OVERRIDDEN needs a reason)';
CREATE INDEX cdss_alert_encounter_idx ON clinical.cdss_alert (encounter_id);
CREATE INDEX cdss_alert_pending_idx ON clinical.cdss_alert (prescription_id) WHERE resolution = 'PENDING';

CREATE TABLE clinical.service_order (
    id                  uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    encounter_id        uuid         NOT NULL,
    patient_id          uuid         NOT NULL,
    service_id          uuid         NOT NULL REFERENCES billing.service (id),
    order_type          varchar(12)  NOT NULL CHECK (order_type IN ('LABORATORY', 'RADIOLOGY', 'PROCEDURE')),
    priority            varchar(8)   NOT NULL DEFAULT 'ROUTINE' CHECK (priority IN ('ROUTINE', 'URGENT', 'STAT')),
    status              varchar(16)  NOT NULL DEFAULT 'ORDERED' CHECK (status IN (
                            'ORDERED', 'SCHEDULED', 'SAMPLE_COLLECTED', 'IN_PROGRESS', 'COMPLETED',
                            'REPORTED', 'CANCELLED')),
    clinical_indication varchar(500),
    accession_number    varchar(16)  UNIQUE CHECK (accession_number ~ '^[A-Z0-9/-]{1,16}$'),
    ordered_by          uuid         NOT NULL REFERENCES core.staff (id),
    ordered_at          timestamptz  NOT NULL DEFAULT now(),
    completed_at        timestamptz,
    cancelled_reason    varchar(300),
    result_summary      text,
    -- Structured results: [{code, name, value, unit, referenceRange, flag}], e.g. CREAT for the renal guard
    result_values       jsonb        CHECK (result_values IS NULL OR jsonb_typeof(result_values) = 'array'),
    sample_collected_at timestamptz,
    reported_by         uuid         REFERENCES core.staff (id),
    version             bigint       NOT NULL DEFAULT 0,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT service_order_encounter_fk FOREIGN KEY (encounter_id, patient_id)
        REFERENCES clinical.encounter (id, patient_id),
    CONSTRAINT service_order_accession_radiology CHECK (accession_number IS NULL OR order_type = 'RADIOLOGY'),
    CONSTRAINT service_order_cancel_reason CHECK (status <> 'CANCELLED' OR cancelled_reason IS NOT NULL)
);
COMMENT ON COLUMN clinical.service_order.accession_number IS
    'DICOM Accession Number (0008,0050), max 16 chars; issue with core.next_document_number(''RAD'')';
CREATE INDEX service_order_encounter_idx ON clinical.service_order (encounter_id);
CREATE INDEX service_order_patient_idx ON clinical.service_order (patient_id, ordered_at DESC);
CREATE INDEX service_order_worklist_idx ON clinical.service_order (order_type, status, priority)
    WHERE status NOT IN ('COMPLETED', 'REPORTED', 'CANCELLED');

-- Parameters (analytes) of each laboratory test, with reference intervals and critical limits.
-- The result-entry screen is built from these, and the Patient Service flags each value
-- (L, H, C) from them, so a critical result does not depend on a hand-typed flag.
-- Intervals are for adults (18+). Children are not flagged automatically: paediatric
-- intervals vary with age and must come from the laboratory itself.
CREATE TABLE clinical.lab_analyte (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id      uuid          NOT NULL REFERENCES billing.service (id),
    code            varchar(16)   NOT NULL CHECK (code ~ '^[A-Z0-9_]{2,16}$'),
    name            varchar(100)  NOT NULL,
    unit            varchar(20),
    decimals        smallint      NOT NULL DEFAULT 1 CHECK (decimals BETWEEN 0 AND 4),
    ref_low         numeric(12,4),
    ref_high        numeric(12,4),
    -- Adult women, when their interval differs (otherwise ref_low/ref_high apply to all)
    ref_low_female  numeric(12,4),
    ref_high_female numeric(12,4),
    critical_low    numeric(12,4),
    critical_high   numeric(12,4),
    -- LOINC code of the measured property (the code of the ABDM Observation)
    loinc_code      varchar(10)   CHECK (loinc_code ~ '^[0-9]{1,7}-[0-9]$'),
    loinc_display   varchar(150),
    display_order   smallint      NOT NULL DEFAULT 0,
    is_active       boolean       NOT NULL DEFAULT true,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT lab_analyte_code_per_test UNIQUE (service_id, code),
    CONSTRAINT lab_analyte_loinc_whole CHECK ((loinc_code IS NULL) = (loinc_display IS NULL)),
    CONSTRAINT lab_analyte_ref_order CHECK (ref_low IS NULL OR ref_high IS NULL OR ref_low <= ref_high),
    CONSTRAINT lab_analyte_female_ref_order CHECK (
        ref_low_female IS NULL OR ref_high_female IS NULL OR ref_low_female <= ref_high_female),
    CONSTRAINT lab_analyte_critical_outside_ref CHECK (
        (critical_low IS NULL OR ref_low IS NULL OR critical_low <= ref_low)
        AND (critical_high IS NULL OR ref_high IS NULL OR critical_high >= ref_high))
);
-- A critical lab result and how it reached the treating doctor (ISO 15189 / NABL: who told whom,
-- when, how, with read-back), then the doctor's acknowledgement. Opened when a value is reported C.
CREATE TABLE clinical.critical_result (
    id                  uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    service_order_id    uuid          NOT NULL UNIQUE REFERENCES clinical.service_order (id),
    patient_id          uuid          NOT NULL REFERENCES patient.patient (id),
    -- The critical values as reported: [{code, name, value, unit}]
    critical_values     jsonb         NOT NULL CHECK (jsonb_typeof(critical_values) = 'array'
                                                      AND jsonb_array_length(critical_values) > 0),
    reported_by         uuid          NOT NULL REFERENCES core.staff (id),
    reported_at         timestamptz   NOT NULL DEFAULT now(),
    communicated_to     varchar(150),
    communication_method varchar(12)  CHECK (communication_method IN ('PHONE', 'IN_PERSON', 'WARD_NURSE')),
    read_back           boolean,
    communicated_by     uuid          REFERENCES core.staff (id),
    communicated_at     timestamptz,
    acknowledged_by     uuid          REFERENCES core.staff (id),
    acknowledged_at     timestamptz,
    acknowledgement_note varchar(500),
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    -- A communication is recorded whole, and only with the value read back.
    CONSTRAINT critical_result_communication_whole CHECK (
        (communicated_at IS NULL AND communicated_to IS NULL AND communication_method IS NULL
             AND communicated_by IS NULL AND read_back IS NULL)
        OR (communicated_at IS NOT NULL AND communicated_to IS NOT NULL AND communication_method IS NOT NULL
             AND communicated_by IS NOT NULL AND read_back IS TRUE)),
    CONSTRAINT critical_result_acknowledgement_whole CHECK ((acknowledged_at IS NULL) = (acknowledged_by IS NULL))
);
CREATE INDEX critical_result_open_idx ON clinical.critical_result (reported_at) WHERE acknowledged_at IS NULL;

COMMENT ON TABLE clinical.lab_analyte IS
    'Lab test parameters. Seeded intervals are STARTER adult values from general references: the laboratory '
    'must replace them with its own validated, method-specific intervals (NABL / ISO 15189) before go-live.';


-- =============================================================================
--  IMAGING: index of Orthanc studies
-- =============================================================================

CREATE TABLE imaging.study (
    id                 uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id         uuid         NOT NULL REFERENCES patient.patient (id),
    service_order_id   uuid         REFERENCES clinical.service_order (id),
    study_instance_uid varchar(64)  NOT NULL UNIQUE CHECK (study_instance_uid ~ '^[0-9]+(\.[0-9]+)*$'),
    accession_number   varchar(16),
    orthanc_study_id   varchar(64)  UNIQUE,
    modality           varchar(4),
    study_date         date,
    study_description  varchar(200),
    series_count       smallint     CHECK (series_count >= 0),
    instance_count     integer      CHECK (instance_count >= 0),
    received_at        timestamptz  NOT NULL DEFAULT now(),
    report_status      varchar(12)  NOT NULL DEFAULT 'UNREPORTED' CHECK (report_status IN (
                           'UNREPORTED', 'DRAFT', 'FINAL', 'ADDENDUM')),
    report_note_id     uuid         REFERENCES clinical.clinical_note (id),
    reported_by        uuid         REFERENCES core.staff (id),
    reported_at        timestamptz,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT study_final_report CHECK (report_status NOT IN ('FINAL', 'ADDENDUM')
                                         OR (report_note_id IS NOT NULL AND reported_at IS NOT NULL))
);
COMMENT ON TABLE imaging.study IS
    'Studies received by Orthanc and matched to a patient (DICOM PatientID = UHID). Pixel data stays in Orthanc';
CREATE INDEX study_patient_idx ON imaging.study (patient_id, study_date DESC);
CREATE INDEX study_accession_idx ON imaging.study (accession_number) WHERE accession_number IS NOT NULL;
CREATE INDEX study_unreported_idx ON imaging.study (received_at) WHERE report_status = 'UNREPORTED';

CREATE TABLE imaging.pacs_sync (
    id          smallint    PRIMARY KEY CHECK (id = 1),
    last_change bigint      NOT NULL DEFAULT 0 CHECK (last_change >= 0),
    synced_at   timestamptz
);
COMMENT ON TABLE imaging.pacs_sync IS
    'Position in Orthanc''s change log up to which stable studies have been indexed into imaging.study';


-- =============================================================================
--  BILLING transactions: bills, items, payments
-- =============================================================================

CREATE TABLE billing.bill (
    id                  uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    bill_number         varchar(16)   UNIQUE,
    bill_type           varchar(10)   NOT NULL CHECK (bill_type IN ('OPD', 'IPD', 'PHARMACY', 'EMERGENCY')),
    patient_id          uuid          NOT NULL,
    encounter_id        uuid          NOT NULL,
    admission_id        uuid          REFERENCES ipd.admission (id),
    tariff_plan_id      uuid          NOT NULL REFERENCES billing.tariff_plan (id),
    status              varchar(10)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'INTERIM', 'FINAL', 'CANCELLED')),
    gross_amount        numeric(12,2) NOT NULL DEFAULT 0 CHECK (gross_amount >= 0),
    discount_amount     numeric(12,2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    tax_amount          numeric(12,2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
    net_amount          numeric(12,2) NOT NULL DEFAULT 0,
    round_off           numeric(4,2)  NOT NULL DEFAULT 0 CHECK (round_off > -1 AND round_off < 1),
    payable_amount      numeric(12,2) GENERATED ALWAYS AS (net_amount + round_off) STORED,
    finalized_at        timestamptz,
    finalized_by        varchar(64),
    cancelled_at        timestamptz,
    cancelled_by        varchar(64),
    cancellation_reason varchar(300),
    remarks             varchar(500),
    version             bigint        NOT NULL DEFAULT 0,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    created_by          varchar(64)   NOT NULL DEFAULT 'system',
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    updated_by          varchar(64),
    CONSTRAINT bill_encounter_fk FOREIGN KEY (encounter_id, patient_id)
        REFERENCES clinical.encounter (id, patient_id),
    CONSTRAINT bill_totals CHECK (net_amount = gross_amount - discount_amount + tax_amount
                                  AND discount_amount <= gross_amount),
    CONSTRAINT bill_ipd_has_admission CHECK (bill_type <> 'IPD' OR admission_id IS NOT NULL),
    CONSTRAINT bill_number_only_when_issued CHECK (status NOT IN ('DRAFT', 'INTERIM') OR bill_number IS NULL),
    CONSTRAINT bill_final_is_numbered CHECK (status <> 'FINAL' OR (bill_number IS NOT NULL AND finalized_at IS NOT NULL)),
    CONSTRAINT bill_cancel_reason CHECK (status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL))
);
COMMENT ON TABLE billing.bill IS
    'OPD slips and IPD bills. Totals are maintained from bill_item by trigger; the number is issued at finalisation';
COMMENT ON COLUMN billing.bill.status IS 'INTERIM = running IPD bill shown before discharge (not numbered)';
CREATE INDEX bill_patient_idx ON billing.bill (patient_id, created_at DESC);
CREATE INDEX bill_encounter_idx ON billing.bill (encounter_id);
CREATE UNIQUE INDEX bill_one_live_ipd_bill ON billing.bill (admission_id)
    WHERE bill_type = 'IPD' AND status <> 'CANCELLED';

CREATE TABLE billing.bill_item (
    id                   uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    bill_id              uuid          NOT NULL REFERENCES billing.bill (id),
    service_id           uuid          NOT NULL REFERENCES billing.service (id),
    tariff_rate_id       uuid          REFERENCES billing.tariff_rate (id),
    description          varchar(200)  NOT NULL,
    service_date         date          NOT NULL DEFAULT core.ist_today(),
    quantity             numeric(10,2) NOT NULL CHECK (quantity > 0),
    unit_price           numeric(12,2) NOT NULL CHECK (unit_price >= 0),
    discount_amount      numeric(12,2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    gst_rate             numeric(5,2)  NOT NULL DEFAULT 0 CHECK (gst_rate BETWEEN 0 AND 40),
    gross_amount         numeric(12,2) GENERATED ALWAYS AS (round(quantity * unit_price, 2)) STORED,
    taxable_amount       numeric(12,2) GENERATED ALWAYS AS (round(quantity * unit_price, 2) - discount_amount) STORED,
    tax_amount           numeric(12,2) GENERATED ALWAYS AS (
                             round((round(quantity * unit_price, 2) - discount_amount) * gst_rate / 100, 2)) STORED,
    line_total           numeric(12,2) GENERATED ALWAYS AS (
                             round(quantity * unit_price, 2) - discount_amount
                             + round((round(quantity * unit_price, 2) - discount_amount) * gst_rate / 100, 2)) STORED,
    discount_approved_by varchar(64),
    source_type          varchar(20)   CHECK (source_type IN (
                             'OPD_VISIT', 'BED_ASSIGNMENT', 'SERVICE_ORDER', 'PRESCRIPTION_ITEM',
                             'DOCTOR_VISIT', 'DISPENSE_LINE', 'MANUAL')),
    source_id            uuid,
    ordered_by           uuid          REFERENCES core.staff (id),
    created_at           timestamptz   NOT NULL DEFAULT now(),
    created_by           varchar(64)   NOT NULL DEFAULT 'system',
    CONSTRAINT bill_item_discount_within_gross CHECK (discount_amount <= round(quantity * unit_price, 2)),
    CONSTRAINT bill_item_discount_approved CHECK (discount_amount = 0 OR discount_approved_by IS NOT NULL),
    CONSTRAINT bill_item_source_pair CHECK ((source_type IS NULL) = (source_id IS NULL))
);
COMMENT ON COLUMN billing.bill_item.source_type IS 'What generated the charge (traceability, prevents silent double billing)';
CREATE INDEX bill_item_bill_idx ON billing.bill_item (bill_id);
CREATE INDEX bill_item_source_idx ON billing.bill_item (source_type, source_id) WHERE source_id IS NOT NULL;

CREATE FUNCTION billing.guard_bill() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status NOT IN ('DRAFT', 'INTERIM') THEN
            RAISE EXCEPTION 'Bills are created as DRAFT or INTERIM, then finalised' USING ERRCODE = 'SYN04';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Bill % is %: only drafts can be deleted, cancel it instead',
                COALESCE(OLD.bill_number, OLD.id::text), OLD.status USING ERRCODE = 'SYN01';
        END IF;
        RETURN OLD;
    END IF;

    -- UPDATE
    IF OLD.status = 'CANCELLED' THEN
        RAISE EXCEPTION 'Bill % is cancelled and cannot change', COALESCE(OLD.bill_number, OLD.id::text)
            USING ERRCODE = 'SYN01';
    END IF;

    IF OLD.status = 'FINAL' THEN
        IF NEW.status <> 'CANCELLED'
           OR (NEW.bill_number, NEW.bill_type, NEW.patient_id, NEW.encounter_id, NEW.admission_id,
               NEW.tariff_plan_id, NEW.gross_amount, NEW.discount_amount, NEW.tax_amount, NEW.net_amount,
               NEW.round_off, NEW.finalized_at, NEW.finalized_by)
              IS DISTINCT FROM
              (OLD.bill_number, OLD.bill_type, OLD.patient_id, OLD.encounter_id, OLD.admission_id,
               OLD.tariff_plan_id, OLD.gross_amount, OLD.discount_amount, OLD.tax_amount, OLD.net_amount,
               OLD.round_off, OLD.finalized_at, OLD.finalized_by) THEN
            RAISE EXCEPTION 'Final bill % can only be cancelled', OLD.bill_number USING ERRCODE = 'SYN01';
        END IF;
        RETURN NEW;
    END IF;

    -- DRAFT/INTERIM -> FINAL: issue the GST-compliant serial number
    IF NEW.status = 'FINAL' THEN
        IF NOT EXISTS (SELECT 1 FROM billing.bill_item WHERE bill_id = NEW.id) THEN
            RAISE EXCEPTION 'Cannot finalise a bill without items' USING ERRCODE = 'SYN04';
        END IF;
        NEW.bill_number := core.next_document_number(
            CASE NEW.bill_type WHEN 'OPD' THEN 'OPB' WHEN 'IPD' THEN 'IPB'
                               WHEN 'PHARMACY' THEN 'PHB' ELSE 'ERB' END);
        NEW.finalized_at := COALESCE(NEW.finalized_at, now());
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_bill_guard
    BEFORE INSERT OR UPDATE OR DELETE ON billing.bill
    FOR EACH ROW EXECUTE FUNCTION billing.guard_bill();

CREATE FUNCTION billing.guard_bill_item() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_bill_id uuid;
    v_status  varchar;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_bill_id := OLD.bill_id;
    ELSE
        v_bill_id := NEW.bill_id;
        IF TG_OP = 'UPDATE' AND NEW.bill_id <> OLD.bill_id THEN
            RAISE EXCEPTION 'Bill items cannot move between bills' USING ERRCODE = 'SYN01';
        END IF;
    END IF;

    SELECT status INTO v_status FROM billing.bill WHERE id = v_bill_id FOR UPDATE;
    IF v_status NOT IN ('DRAFT', 'INTERIM') THEN
        RAISE EXCEPTION 'Bill is %: its items can no longer change', v_status USING ERRCODE = 'SYN01';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;
CREATE TRIGGER trg_bill_item_guard
    BEFORE INSERT OR UPDATE OR DELETE ON billing.bill_item
    FOR EACH ROW EXECUTE FUNCTION billing.guard_bill_item();

CREATE FUNCTION billing.recalculate_bill_totals() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_bill_id uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_bill_id := OLD.bill_id;
    ELSE
        v_bill_id := NEW.bill_id;
    END IF;

    UPDATE billing.bill AS b
    SET gross_amount    = t.gross,
        discount_amount = t.discount,
        tax_amount      = t.tax,
        net_amount      = t.gross - t.discount + t.tax
    FROM (SELECT COALESCE(sum(i.gross_amount), 0)    AS gross,
                 COALESCE(sum(i.discount_amount), 0) AS discount,
                 COALESCE(sum(i.tax_amount), 0)      AS tax
          FROM billing.bill_item AS i
          WHERE i.bill_id = v_bill_id) AS t
    WHERE b.id = v_bill_id;
    RETURN NULL;
END
$$;
CREATE TRIGGER trg_bill_item_totals
    AFTER INSERT OR UPDATE OR DELETE ON billing.bill_item
    FOR EACH ROW EXECUTE FUNCTION billing.recalculate_bill_totals();

CREATE TABLE billing.payment (
    id               uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    receipt_number   varchar(16)   NOT NULL UNIQUE,
    patient_id       uuid          NOT NULL REFERENCES patient.patient (id),
    bill_id          uuid          REFERENCES billing.bill (id),
    admission_id     uuid          REFERENCES ipd.admission (id),
    payment_type     varchar(10)   NOT NULL CHECK (payment_type IN ('ADVANCE', 'SETTLEMENT', 'REFUND')),
    payment_mode     varchar(16)   NOT NULL CHECK (payment_mode IN (
                         'CASH', 'CARD', 'UPI', 'NET_BANKING', 'CHEQUE', 'DEMAND_DRAFT', 'INSURANCE',
                         'GOVT_SCHEME', 'CORPORATE_CREDIT')),
    amount           numeric(12,2) NOT NULL CHECK (amount > 0),
    reference_number varchar(64),
    card_last4       char(4)       CHECK (card_last4 ~ '^[0-9]{4}$'),
    received_at      timestamptz   NOT NULL DEFAULT now(),
    received_by      varchar(64)   NOT NULL,
    status           varchar(10)   NOT NULL DEFAULT 'COMPLETED' CHECK (status IN ('COMPLETED', 'VOIDED')),
    voided_at        timestamptz,
    voided_by        varchar(64),
    void_reason      varchar(300),
    remarks          varchar(300),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT payment_advance_has_admission CHECK (payment_type <> 'ADVANCE' OR admission_id IS NOT NULL),
    CONSTRAINT payment_settlement_has_bill CHECK (payment_type <> 'SETTLEMENT' OR bill_id IS NOT NULL),
    CONSTRAINT payment_refund_has_target CHECK (payment_type <> 'REFUND' OR bill_id IS NOT NULL OR admission_id IS NOT NULL),
    CONSTRAINT payment_non_cash_reference CHECK (payment_mode = 'CASH' OR reference_number IS NOT NULL),
    CONSTRAINT payment_void_reason CHECK (status <> 'VOIDED' OR (voided_at IS NOT NULL AND void_reason IS NOT NULL))
);
COMMENT ON TABLE billing.payment IS
    'Receipts (RCT/..) and refunds (RFD/..). IPD advances are allocated to the final bill by setting bill_id. Never store full card numbers';
CREATE INDEX payment_bill_idx ON billing.payment (bill_id) WHERE bill_id IS NOT NULL;
CREATE INDEX payment_admission_idx ON billing.payment (admission_id) WHERE admission_id IS NOT NULL;
CREATE INDEX payment_patient_idx ON billing.payment (patient_id, received_at DESC);
CREATE INDEX payment_received_at_idx ON billing.payment (received_at);

CREATE FUNCTION billing.guard_payment() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'COMPLETED' THEN
            RAISE EXCEPTION 'Payments are recorded as COMPLETED, then voided if needed' USING ERRCODE = 'SYN04';
        END IF;
        IF NEW.receipt_number IS NULL THEN
            NEW.receipt_number := core.next_document_number(
                CASE WHEN NEW.payment_type = 'REFUND' THEN 'RFD' ELSE 'RCT' END, NEW.received_at);
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Receipt % cannot be deleted: void it instead', OLD.receipt_number USING ERRCODE = 'SYN01';
    END IF;

    -- UPDATE: allow advance allocation (bill_id NULL -> value) and COMPLETED -> VOIDED only
    IF (NEW.receipt_number, NEW.patient_id, NEW.admission_id, NEW.payment_type, NEW.payment_mode,
        NEW.amount, NEW.reference_number, NEW.card_last4, NEW.received_at, NEW.received_by)
       IS DISTINCT FROM
       (OLD.receipt_number, OLD.patient_id, OLD.admission_id, OLD.payment_type, OLD.payment_mode,
        OLD.amount, OLD.reference_number, OLD.card_last4, OLD.received_at, OLD.received_by)
       OR (OLD.bill_id IS NOT NULL AND NEW.bill_id IS DISTINCT FROM OLD.bill_id)
       OR (OLD.status = 'VOIDED' AND NEW.status <> 'VOIDED') THEN
        RAISE EXCEPTION 'Receipt % is immutable (only allocation to a bill or voiding is allowed)',
            OLD.receipt_number USING ERRCODE = 'SYN01';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_payment_guard
    BEFORE INSERT OR UPDATE OR DELETE ON billing.payment
    FOR EACH ROW EXECUTE FUNCTION billing.guard_payment();


-- =============================================================================
--  ABDM: ABHA, care contexts, consents, health-information transfers (HIP)
-- =============================================================================

CREATE TABLE abdm.abha_verification (
    id             uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id     uuid         REFERENCES patient.patient (id),
    abha_number    char(14)     CHECK (abha_number ~ '^[0-9]{14}$'),
    abha_address   varchar(100),
    method         varchar(20)  NOT NULL CHECK (method IN (
                       'MOBILE_OTP', 'AADHAAR_OTP', 'AADHAAR_BIO', 'PASSWORD', 'QR_SCAN', 'DEMOGRAPHIC')),
    outcome        varchar(10)  NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILED', 'EXPIRED')),
    abdm_txn_id    varchar(64),
    failure_reason varchar(300),
    -- Demographics returned by ABDM, kept only to match the ABHA against a patient record
    gender         char(1)      CHECK (gender IN ('M', 'F', 'O', 'U')),
    year_of_birth  smallint     CHECK (year_of_birth BETWEEN 1900 AND 2100),
    performed_by   varchar(64)  NOT NULL,
    performed_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT abha_verification_subject CHECK (abha_number IS NOT NULL OR abha_address IS NOT NULL)
);
COMMENT ON TABLE abdm.abha_verification IS 'Milestone 1: every ABHA verification attempt (patient_id is null until registration)';
CREATE INDEX abha_verification_patient_idx ON abdm.abha_verification (patient_id) WHERE patient_id IS NOT NULL;

CREATE TABLE abdm.abha_enrolment (
    id              uuid         PRIMARY KEY,
    abha_number     char(14)     CHECK (abha_number ~ '^[0-9]{14}$'),
    abha_address    varchar(100),
    outcome         varchar(10)  NOT NULL CHECK (outcome IN ('CREATED', 'EXISTING', 'FAILED')),
    mobile_verified boolean      NOT NULL DEFAULT false,
    consent_version varchar(10)  NOT NULL,
    verification_id uuid         REFERENCES abdm.abha_verification (id),
    failure_reason  varchar(300),
    performed_by    varchar(64)  NOT NULL,
    performed_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT abha_enrolment_result CHECK (
        (outcome = 'FAILED') = (abha_number IS NULL) AND (outcome = 'FAILED') = (verification_id IS NULL))
);
COMMENT ON TABLE abdm.abha_enrolment IS
    'ABHA numbers created at the desk by Aadhaar OTP, with the patient''s consent. The Aadhaar number is never stored (Aadhaar Act 2016 s.29)';

CREATE TABLE abdm.share_token_counter (
    share_date date        NOT NULL,
    context    varchar(64) NOT NULL,
    last_value integer     NOT NULL CHECK (last_value > 0),
    PRIMARY KEY (share_date, context)
);
COMMENT ON TABLE abdm.share_token_counter IS 'Scan & Share queue numbers: one gap-free daily sequence per counter (QR context)';

CREATE TABLE abdm.patient_share (
    id                 uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    gateway_request_id uuid         NOT NULL UNIQUE,
    share_date         date         NOT NULL,
    context            varchar(64)  NOT NULL,
    token_number       integer      NOT NULL CHECK (token_number > 0),
    abha_number        char(14)     CHECK (abha_number ~ '^[0-9]{14}$'),
    abha_address       varchar(100) NOT NULL,
    name               varchar(200) NOT NULL,
    gender             char(1)      NOT NULL CHECK (gender IN ('M', 'F', 'O', 'U')),
    year_of_birth      smallint     CHECK (year_of_birth BETWEEN 1900 AND 2100),
    month_of_birth     smallint     CHECK (month_of_birth BETWEEN 1 AND 12),
    day_of_birth       smallint     CHECK (day_of_birth BETWEEN 1 AND 31),
    mobile             varchar(10)  CHECK (mobile ~ '^[6-9][0-9]{9}$'),
    address_line       varchar(250),
    district_name      varchar(100),
    state_name         varchar(60),
    pincode            char(6)      CHECK (pincode ~ '^[1-9][0-9]{5}$'),
    matched_patient_id uuid         REFERENCES patient.patient (id),
    status             varchar(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'REGISTERED', 'EXPIRED')),
    expires_at         timestamptz  NOT NULL,
    registered_patient_id uuid      REFERENCES patient.patient (id),
    completed_by       varchar(64),
    completed_at       timestamptz,
    received_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT patient_share_token_uq UNIQUE (share_date, context, token_number),
    CONSTRAINT patient_share_completed CHECK (
        (status = 'REGISTERED') = (registered_patient_id IS NOT NULL AND completed_at IS NOT NULL))
);
COMMENT ON TABLE abdm.patient_share IS
    'Milestone 1 Scan & Share: profiles shared by patients at the registration desk. Rows are purged within a day';
CREATE INDEX patient_share_pending_idx ON abdm.patient_share (share_date, context, token_number) WHERE status = 'PENDING';

CREATE TABLE abdm.care_context (
    id                  uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id          uuid          NOT NULL,
    encounter_id        uuid          NOT NULL UNIQUE,
    reference_number    varchar(64)   NOT NULL UNIQUE,
    display             varchar(200)  NOT NULL,
    hi_types            varchar(32)[] NOT NULL,
    link_status         varchar(12)   NOT NULL DEFAULT 'NOT_LINKED' CHECK (link_status IN (
                            'NOT_LINKED', 'PENDING', 'LINKED', 'FAILED')),
    linked_abha_address varchar(100),
    linked_at           timestamptz,
    link_error          varchar(300),
    -- REQUEST-ID of the pending link call; NULL while PENDING means "waiting for a link token"
    link_request_id     uuid,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT care_context_encounter_fk FOREIGN KEY (encounter_id, patient_id)
        REFERENCES clinical.encounter (id, patient_id),
    CONSTRAINT care_context_hi_types CHECK (
        cardinality(hi_types) > 0
        AND hi_types <@ ARRAY['OPConsultation', 'Prescription', 'DischargeSummary', 'DiagnosticReport',
                              'ImmunizationRecord', 'HealthDocumentRecord', 'WellnessRecord',
                              'Invoice']::varchar[]),
    CONSTRAINT care_context_linked CHECK (
        link_status <> 'LINKED' OR (linked_at IS NOT NULL AND linked_abha_address IS NOT NULL))
);
COMMENT ON TABLE abdm.care_context IS
    'Milestone 2: one care context per encounter, linked to the patient''s ABHA. Link tokens are short-lived and kept in Redis, not here';
CREATE INDEX care_context_patient_idx ON abdm.care_context (patient_id);
CREATE INDEX care_context_link_request_idx ON abdm.care_context (link_request_id) WHERE link_request_id IS NOT NULL;

CREATE TABLE abdm.discovery_request (
    transaction_id     uuid          PRIMARY KEY,
    gateway_request_id uuid          NOT NULL UNIQUE,
    abha_address       varchar(100)  NOT NULL,
    patient_id         uuid          REFERENCES patient.patient (id),
    care_context_ids   uuid[]        NOT NULL DEFAULT '{}',
    matched_by         varchar(16)[] NOT NULL DEFAULT '{}',
    outcome            varchar(10)   NOT NULL CHECK (outcome IN ('MATCHED', 'NOT_FOUND', 'AMBIGUOUS', 'NO_RECORDS', 'INVALID')),
    requested_at       timestamptz   NOT NULL DEFAULT now(),
    expires_at         timestamptz   NOT NULL,
    CONSTRAINT discovery_matched_patient CHECK ((outcome = 'MATCHED') = (patient_id IS NOT NULL))
);
COMMENT ON TABLE abdm.discovery_request IS
    'Milestone 2 patient-initiated linking: who searched (ABHA address), whom they matched and which care contexts were offered';
CREATE INDEX discovery_request_patient_idx ON abdm.discovery_request (patient_id) WHERE patient_id IS NOT NULL;

CREATE TABLE abdm.link_session (
    id                 uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    link_reference     varchar(64)   NOT NULL UNIQUE,
    transaction_id     uuid          NOT NULL REFERENCES abdm.discovery_request (transaction_id),
    gateway_request_id uuid          NOT NULL UNIQUE,
    abha_address       varchar(100)  NOT NULL,
    patient_id         uuid          NOT NULL REFERENCES patient.patient (id),
    care_context_ids   uuid[]        NOT NULL CHECK (cardinality(care_context_ids) > 0),
    -- The OTP itself is never stored: SHA-256 of salt || OTP, compared in constant time
    otp_salt           char(32)      NOT NULL CHECK (otp_salt ~ '^[0-9a-f]{32}$'),
    otp_hash           char(64)      NOT NULL CHECK (otp_hash ~ '^[0-9a-f]{64}$'),
    attempts           smallint      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    status             varchar(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'LINKED', 'FAILED', 'EXPIRED')),
    failure_reason     varchar(200),
    expires_at         timestamptz   NOT NULL,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    completed_at       timestamptz,
    CONSTRAINT link_session_completed CHECK ((status = 'PENDING') = (completed_at IS NULL))
);
COMMENT ON TABLE abdm.link_session IS
    'OTP challenge sent to the patient''s registered mobile before care contexts are linked to the ABHA that discovered them';
CREATE INDEX link_session_patient_idx ON abdm.link_session (patient_id, created_at DESC);

CREATE TABLE abdm.consent_artefact (
    id                   uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    consent_artefact_id  uuid          NOT NULL UNIQUE,
    consent_request_id   uuid,
    patient_id           uuid          REFERENCES patient.patient (id),
    patient_abha_address varchar(100)  NOT NULL,
    hiu_id               varchar(64),
    hiu_name             varchar(200),
    requester_name       varchar(200),
    purpose_code         varchar(10)   NOT NULL CHECK (purpose_code IN (
                             'CAREMGT', 'BTG', 'PUBHLTH', 'HPAYMT', 'DSRCH', 'PATRQT')),
    hi_types             varchar(32)[] NOT NULL,
    access_mode          varchar(8)    NOT NULL CHECK (access_mode IN ('VIEW', 'STORE', 'QUERY', 'STREAM')),
    date_range_from      timestamptz   NOT NULL,
    date_range_to        timestamptz   NOT NULL,
    data_erase_at        timestamptz   NOT NULL,
    frequency_unit       varchar(8),
    frequency_value      smallint,
    frequency_repeats    smallint,
    status               varchar(10)   NOT NULL CHECK (status IN ('GRANTED', 'REVOKED', 'EXPIRED', 'DENIED')),
    signature            text,
    artefact             jsonb         NOT NULL,
    received_at          timestamptz   NOT NULL DEFAULT now(),
    status_changed_at    timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT consent_date_range CHECK (date_range_to >= date_range_from)
);
COMMENT ON TABLE abdm.consent_artefact IS 'Consent artefacts notified to this HIP; the signed artefact is kept verbatim for audit';
CREATE INDEX consent_artefact_patient_idx ON abdm.consent_artefact (patient_id);
CREATE INDEX consent_artefact_granted_idx ON abdm.consent_artefact (data_erase_at) WHERE status = 'GRANTED';

CREATE TABLE abdm.consent_care_context (
    consent_id      uuid NOT NULL REFERENCES abdm.consent_artefact (id) ON DELETE CASCADE,
    care_context_id uuid NOT NULL REFERENCES abdm.care_context (id),
    PRIMARY KEY (consent_id, care_context_id)
);
CREATE INDEX consent_care_context_cc_idx ON abdm.consent_care_context (care_context_id);

CREATE TABLE abdm.health_information_request (
    id                 uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id     uuid         NOT NULL UNIQUE,
    gateway_request_id uuid         NOT NULL UNIQUE,
    consent_id         uuid         NOT NULL REFERENCES abdm.consent_artefact (id),
    date_range_from    timestamptz  NOT NULL,
    date_range_to      timestamptz  NOT NULL,
    data_push_url      varchar(500) NOT NULL,
    key_curve          varchar(20)  NOT NULL DEFAULT 'Curve25519',
    key_algorithm      varchar(20)  NOT NULL DEFAULT 'ECDH',
    hiu_public_key     text         NOT NULL,
    hiu_nonce          varchar(64)  NOT NULL,
    key_expiry         timestamptz,
    status             varchar(14)  NOT NULL DEFAULT 'REQUESTED' CHECK (status IN (
                           'REQUESTED', 'ACKNOWLEDGED', 'TRANSFERRING', 'TRANSFERRED', 'FAILED', 'REJECTED')),
    rejection_reason   varchar(300),
    requested_at       timestamptz  NOT NULL DEFAULT now(),
    completed_at       timestamptz,
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT hi_request_range CHECK (date_range_to >= date_range_from)
);
COMMENT ON TABLE abdm.health_information_request IS
    'HIU data requests. The HIP''s own ephemeral ECDH private key is never persisted';
CREATE INDEX hi_request_consent_idx ON abdm.health_information_request (consent_id);

CREATE TABLE abdm.health_information_transfer (
    id                uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    hi_request_id     uuid         NOT NULL REFERENCES abdm.health_information_request (id),
    care_context_id   uuid         NOT NULL REFERENCES abdm.care_context (id),
    hi_type           varchar(32)  NOT NULL,
    bundle_sha256     char(64)     NOT NULL CHECK (bundle_sha256 ~ '^[0-9a-f]{64}$'),
    bundle_size_bytes integer      NOT NULL CHECK (bundle_size_bytes > 0),
    status            varchar(10)  NOT NULL CHECK (status IN ('PENDING', 'DELIVERED', 'ERRORED')),
    error_message     varchar(500),
    sent_at           timestamptz,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT hi_transfer_uq UNIQUE (hi_request_id, care_context_id, hi_type)
);
COMMENT ON TABLE abdm.health_information_transfer IS
    'One FHIR R4 bundle sent per care context and HI type. Only its SHA-256 is kept; bundles are regenerated from source records';

-- ---------------------------------------------------------------------------
-- HIU (Milestone 3): this hospital asking for a patient's records held elsewhere.
-- consent request -> artefact(s) granted per HIP -> data request -> encrypted push -> records.
-- ---------------------------------------------------------------------------
CREATE TABLE abdm.hiu_consent_request (
    id                 uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    -- REQUEST-ID of our consent/request/init; ABDM's id arrives with on-init
    gateway_request_id uuid          NOT NULL UNIQUE,
    consent_request_id uuid          UNIQUE,
    patient_id         uuid          NOT NULL REFERENCES patient.patient (id),
    abha_address       varchar(100)  NOT NULL,
    purpose_code       varchar(8)    NOT NULL CHECK (purpose_code IN ('CAREMGT', 'BTG', 'PUBHLTH', 'HPAYMT', 'DSRCH', 'PATRQT')),
    hi_types           varchar(32)[] NOT NULL CHECK (cardinality(hi_types) BETWEEN 1 AND 8),
    date_range_from    timestamptz   NOT NULL,
    date_range_to      timestamptz   NOT NULL,
    data_erase_at      timestamptz   NOT NULL,
    requested_by       uuid          NOT NULL REFERENCES core.staff (id),
    status             varchar(10)   NOT NULL DEFAULT 'REQUESTED' CHECK (status IN (
                           'REQUESTED', 'GRANTED', 'DENIED', 'EXPIRED', 'REVOKED', 'FAILED')),
    error_message      varchar(300),
    requested_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT hiu_consent_request_range CHECK (date_range_to > date_range_from),
    CONSTRAINT hiu_consent_request_erase CHECK (data_erase_at > requested_at)
);
CREATE INDEX hiu_consent_request_patient_idx ON abdm.hiu_consent_request (patient_id, requested_at DESC);

-- One artefact per HIP that holds records for the patient, as ABDM's consent manager issued it.
CREATE TABLE abdm.hiu_consent_artefact (
    consent_id         uuid          PRIMARY KEY,
    consent_request_id uuid          NOT NULL REFERENCES abdm.hiu_consent_request (id),
    status             varchar(10)   NOT NULL CHECK (status IN ('GRANTED', 'REVOKED', 'EXPIRED', 'DENIED')),
    hip_id             varchar(64),
    hip_name           varchar(200),
    care_contexts      jsonb         CHECK (care_contexts IS NULL OR jsonb_typeof(care_contexts) = 'array'),
    hi_types           varchar(32)[],
    date_range_from    timestamptz,
    date_range_to      timestamptz,
    data_erase_at      timestamptz,
    fetched_at         timestamptz,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX hiu_artefact_request_idx ON abdm.hiu_consent_artefact (consent_request_id);

-- The ECDH private key for a request lives only in Redis (expires with the key); it is never stored here.
CREATE TABLE abdm.hiu_data_request (
    id                 uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    gateway_request_id uuid          NOT NULL UNIQUE,
    consent_id         uuid          NOT NULL REFERENCES abdm.hiu_consent_artefact (consent_id),
    transaction_id     uuid          UNIQUE,
    -- SHA-256 of the secret in the data-push URL given to the HIP
    push_token_sha256  char(64)      NOT NULL UNIQUE CHECK (push_token_sha256 ~ '^[0-9a-f]{64}$'),
    date_range_from    timestamptz   NOT NULL,
    date_range_to      timestamptz   NOT NULL,
    status             varchar(12)   NOT NULL DEFAULT 'REQUESTED' CHECK (status IN (
                           'REQUESTED', 'ACKNOWLEDGED', 'TRANSFERRED', 'PARTIAL', 'FAILED')),
    page_count         integer       CHECK (page_count > 0),
    pages_received     integer       NOT NULL DEFAULT 0 CHECK (pages_received >= 0),
    -- care-context reference -> why its record could not be accepted (reported back to the HIP)
    failures           jsonb         NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(failures) = 'object'),
    error_message      varchar(300),
    requested_at       timestamptz   NOT NULL DEFAULT now(),
    completed_at       timestamptz,
    updated_at         timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX hiu_data_request_consent_idx ON abdm.hiu_data_request (consent_id);

-- Records received, decrypted and checked; deleted at the consent's data-erase time (or on revocation).
CREATE TABLE abdm.hiu_health_record (
    id                     uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    data_request_id        uuid          NOT NULL REFERENCES abdm.hiu_data_request (id),
    consent_id             uuid          NOT NULL REFERENCES abdm.hiu_consent_artefact (consent_id),
    patient_id             uuid          NOT NULL REFERENCES patient.patient (id),
    care_context_reference varchar(100)  NOT NULL,
    page_number            integer       NOT NULL CHECK (page_number > 0),
    record_type            varchar(80),
    title                  varchar(200),
    record_date            timestamptz,
    bundle                 jsonb         NOT NULL CHECK (jsonb_typeof(bundle) = 'object'),
    bundle_sha256          char(64)      NOT NULL CHECK (bundle_sha256 ~ '^[0-9a-f]{64}$'),
    data_erase_at          timestamptz   NOT NULL,
    received_at            timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT hiu_record_once UNIQUE (data_request_id, bundle_sha256)
);
CREATE INDEX hiu_record_patient_idx ON abdm.hiu_health_record (patient_id, record_date DESC);
CREATE INDEX hiu_record_erase_idx ON abdm.hiu_health_record (data_erase_at);
COMMENT ON TABLE abdm.hiu_health_record IS
    'Health records received from other HIPs under consent (FHIR R4 documents). Erased at data_erase_at';

CREATE TABLE abdm.gateway_message (
    id             bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id     uuid         NOT NULL,
    direction      varchar(8)   NOT NULL CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    api_path       varchar(200) NOT NULL,
    transaction_id uuid,
    -- Patient an outbound call was made for (lets callbacks be matched without trusting their body)
    patient_id     uuid,
    http_status    smallint,
    outcome        varchar(10)  NOT NULL CHECK (outcome IN ('RECEIVED', 'SENT', 'ACKED', 'ERROR')),
    error_code     varchar(40),
    error_message  varchar(500),
    occurred_at    timestamptz  NOT NULL DEFAULT now(),
    -- ABDM retries callbacks with the same REQUEST-ID: a conflict here means "already processed"
    CONSTRAINT gateway_message_idempotency UNIQUE (direction, request_id)
);
COMMENT ON TABLE abdm.gateway_message IS 'ABDM gateway traffic log for idempotency and support. Payloads (PHI) are not stored';
CREATE INDEX gateway_message_txn_idx ON abdm.gateway_message (transaction_id) WHERE transaction_id IS NOT NULL;
CREATE INDEX gateway_message_time_idx ON abdm.gateway_message USING brin (occurred_at);


-- =============================================================================
--  AUDIT: append-only trail
-- =============================================================================

CREATE TABLE audit.event_log (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor       varchar(64) NOT NULL,
    actor_role  varchar(32),
    action      varchar(16) NOT NULL CHECK (action IN (
                    'CREATE', 'READ', 'UPDATE', 'DELETE', 'PRINT', 'EXPORT', 'SHARE',
                    'LOGIN', 'LOGOUT', 'ACCESS_DENIED', 'BREAK_GLASS')),
    entity_type varchar(64) NOT NULL,
    entity_id   varchar(64),
    patient_id  uuid,
    request_id  varchar(64),
    client_ip   inet,
    service     varchar(40) NOT NULL,
    details     jsonb
);
COMMENT ON TABLE audit.event_log IS
    'Who did what to which record. No FK on patient_id so the trail survives merges. details must not contain PHI values';
CREATE INDEX event_log_patient_idx ON audit.event_log (patient_id, occurred_at DESC) WHERE patient_id IS NOT NULL;
CREATE INDEX event_log_actor_idx ON audit.event_log (actor, occurred_at DESC);
CREATE INDEX event_log_time_idx ON audit.event_log USING brin (occurred_at);

CREATE TRIGGER trg_event_log_append_only
    BEFORE UPDATE OR DELETE ON audit.event_log
    FOR EACH ROW EXECUTE FUNCTION core.forbid_change();
CREATE TRIGGER trg_event_log_no_truncate
    BEFORE TRUNCATE ON audit.event_log
    FOR EACH STATEMENT EXECUTE FUNCTION core.forbid_change();


-- =============================================================================
--  PHARMACY: dispensing (after the clinical and billing tables it refers to)
-- =============================================================================

-- Medicines handed over against an issued prescription (FEFO batches, never expired).
CREATE TABLE pharmacy.dispense (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    dispense_number varchar(16) NOT NULL UNIQUE DEFAULT core.next_document_number('DSP'),
    prescription_id uuid        NOT NULL REFERENCES clinical.prescription (id),
    patient_id      uuid        NOT NULL REFERENCES patient.patient (id),
    store_id        uuid        NOT NULL REFERENCES pharmacy.store (id),
    dispensed_by    uuid        NOT NULL REFERENCES core.staff (id),
    dispensed_at    timestamptz NOT NULL DEFAULT now(),
    bill_id         uuid        REFERENCES billing.bill (id)
);
CREATE INDEX dispense_prescription_idx ON pharmacy.dispense (prescription_id);

CREATE TABLE pharmacy.dispense_line (
    id                   uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    dispense_id          uuid          NOT NULL REFERENCES pharmacy.dispense (id),
    prescription_item_id uuid          REFERENCES clinical.prescription_item (id),
    drug_id              uuid          NOT NULL REFERENCES pharmacy.drug (id),
    batch_id             uuid          NOT NULL REFERENCES pharmacy.batch (id),
    quantity             numeric(10,2) NOT NULL CHECK (quantity > 0),
    mrp                  numeric(10,2) NOT NULL CHECK (mrp > 0),
    gst_rate             numeric(5,2)  NOT NULL,
    schedule             varchar(4)
);
CREATE INDEX dispense_line_dispense_idx ON pharmacy.dispense_line (dispense_id);

-- Schedule H1 register (Drugs and Cosmetics Rules 1945, rule 65(3)(c)): prescriber, patient,
-- drug and quantity for every H1 supply, kept for three years.
CREATE VIEW pharmacy.v_schedule_h1_register AS
    SELECT d.dispensed_at, d.dispense_number, p.uhid, p.full_name AS patient_name,
           concat_ws(', ', p.address_line, p.city_village, p.district_name) AS patient_address,
           concat_ws(' ', ps.title, ps.full_name) AS prescriber, ps.council_registration AS prescriber_registration,
           dr.brand_name, dr.strength, b.batch_number, l.quantity, dr.dispensing_unit,
           concat_ws(' ', ds.title, ds.full_name) AS dispensed_by
    FROM pharmacy.dispense_line l
    JOIN pharmacy.dispense d ON d.id = l.dispense_id
    JOIN pharmacy.drug dr ON dr.id = l.drug_id
    JOIN pharmacy.batch b ON b.id = l.batch_id
    JOIN patient.patient p ON p.id = d.patient_id
    JOIN clinical.prescription rx ON rx.id = d.prescription_id
    JOIN core.staff ps ON ps.id = rx.prescribed_by
    JOIN core.staff ds ON ds.id = d.dispensed_by
    WHERE l.schedule = 'H1';


-- =============================================================================
--  IAM: staff sign-in (the IAM service issues the HS256 tokens every service checks)
-- =============================================================================

-- One account per member of staff. The username is the token's `sub`, and equals
-- core.staff.iam_subject so services find the caller's staff record.
CREATE TABLE iam.user_account (
    id                    uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    username              varchar(64)   NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
    -- bcrypt only; the plain password is never stored or logged.
    password_hash         varchar(100)  NOT NULL CHECK (password_hash ~ '^\$2[aby]\$'),
    display_name          varchar(150)  NOT NULL,
    staff_id              uuid          UNIQUE REFERENCES core.staff (id),
    roles                 text[]        NOT NULL CHECK (cardinality(roles) BETWEEN 1 AND 4 AND roles <@ ARRAY[
                              'DOCTOR', 'NURSE', 'RECEPTIONIST', 'BILLING_CLERK', 'PHARMACIST', 'LAB_TECHNICIAN',
                              'RADIOGRAPHER', 'RADIOLOGIST', 'MRD_OFFICER', 'ADMINISTRATOR']::text[]),
    status                varchar(10)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    failed_attempts       smallint      NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until          timestamptz,
    -- Set for new accounts and after a reset: the temporary password must be replaced first.
    must_change_password  boolean       NOT NULL DEFAULT true,
    password_changed_at   timestamptz,
    last_login_at         timestamptz,
    created_by            varchar(64)   NOT NULL,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    updated_at            timestamptz   NOT NULL DEFAULT now()
);

-- Refresh tokens are opaque random values; only their SHA-256 is stored. Each use rotates the
-- token within its family; presenting a rotated (already used) token revokes the whole family.
CREATE TABLE iam.refresh_token (
    id           uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid         NOT NULL REFERENCES iam.user_account (id) ON DELETE CASCADE,
    token_hash   char(64)     NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    family_id    uuid         NOT NULL,
    expires_at   timestamptz  NOT NULL,
    revoked_at   timestamptz,
    replaced_by  uuid         REFERENCES iam.refresh_token (id),
    created_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX refresh_token_family_idx ON iam.refresh_token (family_id) WHERE revoked_at IS NULL;

-- Sign-in and account events (append-only), for security review.
CREATE TABLE iam.auth_event (
    id          bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz   NOT NULL DEFAULT now(),
    username    varchar(64)   NOT NULL,
    event       varchar(24)   NOT NULL CHECK (event IN (
                    'LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'ACCOUNT_LOCKED', 'LOGOUT', 'REFRESH_REUSED',
                    'PASSWORD_CHANGED', 'PASSWORD_RESET', 'ACCOUNT_CREATED', 'ACCOUNT_DISABLED',
                    'ACCOUNT_ENABLED', 'ACCOUNT_UNLOCKED', 'ROLES_CHANGED')),
    actor       varchar(64),
    client_ip   varchar(45)
);
CREATE INDEX auth_event_user_idx ON iam.auth_event (username, occurred_at DESC);
CREATE TRIGGER trg_auth_event_append_only
    BEFORE UPDATE OR DELETE ON iam.auth_event
    FOR EACH ROW EXECUTE FUNCTION core.forbid_change();


-- =============================================================================
--  updated_at maintenance for every base table that has the column
-- =============================================================================

DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT c.table_schema, c.table_name
        FROM information_schema.columns AS c
        JOIN information_schema.tables AS t
          ON t.table_schema = c.table_schema AND t.table_name = c.table_name
        WHERE c.column_name = 'updated_at'
          AND t.table_type = 'BASE TABLE'
          AND c.table_schema IN ('core', 'patient', 'clinical', 'opd', 'ipd', 'pharmacy',
                                 'billing', 'imaging', 'abdm', 'iam')
    LOOP
        EXECUTE format(
            'CREATE TRIGGER trg_%s_updated_at BEFORE UPDATE ON %I.%I '
            'FOR EACH ROW EXECUTE FUNCTION core.set_updated_at()',
            r.table_name, r.table_schema, r.table_name);
    END LOOP;
END
$$;


-- =============================================================================
--  VIEWS
-- =============================================================================

CREATE VIEW opd.v_queue_today AS
SELECT v.id                 AS visit_id,
       v.encounter_id,
       d.code               AS department_code,
       d.name               AS department_name,
       v.token_number,
       v.priority,
       CASE v.priority
           WHEN 'EMERGENCY' THEN 0
           WHEN 'NORMAL'    THEN 2
           ELSE 1
       END                  AS priority_rank,
       v.queue_status,
       v.visit_type,
       p.id                 AS patient_id,
       p.uhid,
       p.full_name          AS patient_name,
       p.gender,
       date_part('year', age(core.ist_today(), p.date_of_birth))::int AS age_years,
       s.full_name          AS doctor_name,
       v.registered_at,
       v.called_at
FROM opd.visit AS v
JOIN core.department AS d ON d.id = v.department_id
JOIN patient.patient AS p ON p.id = v.patient_id
LEFT JOIN core.staff AS s ON s.id = v.doctor_id
WHERE v.visit_date = core.ist_today();
COMMENT ON VIEW opd.v_queue_today IS 'Today''s OPD queue; display ORDER BY priority_rank, token_number';

CREATE VIEW ipd.v_bed_board AS
SELECT w.code              AS ward_code,
       w.name              AS ward_name,
       w.ward_type,
       b.id                AS bed_id,
       b.bed_code,
       b.bed_category,
       b.status            AS bed_status,
       a.id                AS admission_id,
       a.admission_number,
       a.admitted_at,
       p.uhid,
       p.full_name         AS patient_name,
       p.gender,
       date_part('year', age(core.ist_today(), p.date_of_birth))::int AS age_years,
       s.full_name         AS admitting_doctor
FROM ipd.bed AS b
JOIN ipd.ward AS w ON w.id = b.ward_id
LEFT JOIN ipd.bed_assignment AS ba ON ba.bed_id = b.id AND ba.released_at IS NULL
LEFT JOIN ipd.admission AS a ON a.id = ba.admission_id
LEFT JOIN patient.patient AS p ON p.id = a.patient_id
LEFT JOIN core.staff AS s ON s.id = a.admitting_doctor_id
WHERE b.is_active AND w.is_active;
COMMENT ON VIEW ipd.v_bed_board IS 'Live bed occupancy board (one row per active bed)';

CREATE VIEW billing.v_bill_balance AS
SELECT b.id AS bill_id,
       b.bill_number,
       b.bill_type,
       b.status,
       b.patient_id,
       b.payable_amount,
       COALESCE(sum(p.amount) FILTER (WHERE p.payment_type IN ('ADVANCE', 'SETTLEMENT')), 0) AS amount_received,
       COALESCE(sum(p.amount) FILTER (WHERE p.payment_type = 'REFUND'), 0)                   AS amount_refunded,
       b.payable_amount
         - COALESCE(sum(p.amount) FILTER (WHERE p.payment_type IN ('ADVANCE', 'SETTLEMENT')), 0)
         + COALESCE(sum(p.amount) FILTER (WHERE p.payment_type = 'REFUND'), 0)              AS balance_due
FROM billing.bill AS b
LEFT JOIN billing.payment AS p ON p.bill_id = b.id AND p.status = 'COMPLETED'
GROUP BY b.id;
COMMENT ON VIEW billing.v_bill_balance IS 'Amount received/refunded and balance due per bill (completed payments only)';

CREATE VIEW billing.v_unallocated_advance AS
SELECT p.admission_id,
       a.admission_number,
       a.patient_id,
       COALESCE(sum(p.amount) FILTER (WHERE p.payment_type = 'ADVANCE'), 0)
         - COALESCE(sum(p.amount) FILTER (WHERE p.payment_type = 'REFUND'), 0) AS advance_balance
FROM billing.payment AS p
JOIN ipd.admission AS a ON a.id = p.admission_id
WHERE p.status = 'COMPLETED' AND p.bill_id IS NULL
GROUP BY p.admission_id, a.admission_number, a.patient_id;
COMMENT ON VIEW billing.v_unallocated_advance IS 'IPD deposits not yet set off against a bill';


-- =============================================================================
--  SEED DATA (reference data; edit to match the hospital before go-live)
-- =============================================================================

INSERT INTO core.department (code, name, is_clinical, opd_enabled) VALUES
    ('GMED', 'General Medicine',              true, true),
    ('GSUR', 'General Surgery',               true, true),
    ('OBGY', 'Obstetrics & Gynaecology',      true, true),
    ('PAED', 'Paediatrics',                   true, true),
    ('ORTH', 'Orthopaedics',                  true, true),
    ('ENT',  'ENT',                           true, true),
    ('OPTH', 'Ophthalmology',                 true, true),
    ('DERM', 'Dermatology',                   true, true),
    ('DENT', 'Dental',                        true, true),
    ('EMER', 'Emergency & Casualty',          true, true),
    ('ANES', 'Anaesthesiology',               true, false),
    ('RADI', 'Radiology',                     true, false),
    ('PATH', 'Pathology & Laboratory',        true, false);

-- 50-bed layout: 13 + 13 + 6 + 6 + 4 + 4 + 4 = 50
INSERT INTO ipd.ward (code, name, ward_type, gender_restriction, default_bed_category, department_id, floor_label)
SELECT w.code, w.name, w.ward_type, w.gender, w.category, d.id, w.floor_label
FROM (VALUES
    ('GMW', 'General Ward - Male',   'GENERAL',      'M',   'GENERAL',      NULL,   'Ground'),
    ('GFW', 'General Ward - Female', 'GENERAL',      'F',   'GENERAL',      NULL,   'Ground'),
    ('PED', 'Paediatric Ward',       'PAEDIATRIC',   'ANY', 'GENERAL',      'PAED', 'First'),
    ('MAT', 'Maternity Ward',        'MATERNITY',    'F',   'GENERAL',      'OBGY', 'First'),
    ('ICU', 'Intensive Care Unit',   'ICU',          'ANY', 'ICU',          NULL,   'First'),
    ('SPV', 'Semi-Private Rooms',    'SEMI_PRIVATE', 'ANY', 'SEMI_PRIVATE', NULL,   'Second'),
    ('PVT', 'Private Rooms',         'PRIVATE',      'ANY', 'PRIVATE',      NULL,   'Second')
) AS w (code, name, ward_type, gender, category, dept_code, floor_label)
LEFT JOIN core.department AS d ON d.code = w.dept_code;

INSERT INTO ipd.bed (ward_id, bed_code, bed_category, has_oxygen, has_monitor)
SELECT w.id,
       w.code || '-' || lpad(n::text, 2, '0'),
       w.default_bed_category,
       w.ward_type = 'ICU',
       w.ward_type = 'ICU'
FROM ipd.ward AS w
JOIN (VALUES ('GMW', 13), ('GFW', 13), ('PED', 6), ('MAT', 6), ('ICU', 4), ('SPV', 4), ('PVT', 4))
     AS c (code, beds) ON c.code = w.code
CROSS JOIN LATERAL generate_series(1, c.beds) AS n;

INSERT INTO imaging.pacs_sync (id) VALUES (1);

-- Indicative vaccine catalogue (Universal Immunization Programme schedule, plus common others)
INSERT INTO clinical.vaccine (code, name, target_disease, default_route, default_dose_ml, series_doses, programme) VALUES
    ('BCG',       'BCG',                                    'Tuberculosis',                                          'ID',   0.05, 1, 'UIP'),
    ('HEPB-BD',   'Hepatitis B (birth dose)',               'Hepatitis B',                                           'IM',   0.50, 1, 'UIP'),
    ('OPV',       'Oral Polio Vaccine',                     'Poliomyelitis',                                         'ORAL', NULL, 5, 'UIP'),
    ('PENTA',     'Pentavalent (DPT-HepB-Hib)',             'Diphtheria, pertussis, tetanus, hepatitis B, Hib disease', 'IM', 0.50, 3, 'UIP'),
    ('ROTA',      'Rotavirus Vaccine',                      'Rotavirus diarrhoea',                                   'ORAL', NULL, 3, 'UIP'),
    ('FIPV',      'Fractional Inactivated Polio Vaccine',   'Poliomyelitis',                                         'ID',   0.10, 3, 'UIP'),
    ('PCV',       'Pneumococcal Conjugate Vaccine',         'Pneumococcal disease',                                  'IM',   0.50, 3, 'UIP'),
    ('MR',        'Measles-Rubella',                        'Measles, rubella',                                      'SC',   0.50, 2, 'UIP'),
    ('JE',        'Japanese Encephalitis',                  'Japanese encephalitis',                                 'SC',   0.50, 2, 'UIP'),
    ('DPT-B',     'DPT booster',                            'Diphtheria, pertussis, tetanus',                        'IM',   0.50, 2, 'UIP'),
    ('TD',        'Tetanus and adult Diphtheria (Td)',      'Tetanus, diphtheria',                                   'IM',   0.50, 3, 'UIP'),
    ('HPV',       'Human Papillomavirus Vaccine',           'Human papillomavirus infection',                        'IM',   0.50, 2, 'OTHER'),
    ('TCV',       'Typhoid Conjugate Vaccine',              'Typhoid fever',                                         'IM',   0.50, 1, 'OTHER'),
    ('HEPB',      'Hepatitis B (adult)',                    'Hepatitis B',                                           'IM',   1.00, 3, 'ADULT'),
    ('INFLUENZA', 'Influenza (inactivated)',                'Influenza',                                             'IM',   0.50, NULL, 'ADULT'),
    ('COVID19',   'COVID-19 Vaccine',                       'COVID-19',                                              'IM',   0.50, 3, 'ADULT'),
    ('ARV',       'Anti-Rabies Vaccine (post-exposure)',    'Rabies',                                                'IM',   0.50, 5, 'OTHER');

INSERT INTO billing.tariff_plan (code, name, payer_type, is_default, description) VALUES
    ('GENERAL', 'General (Self-pay)', 'SELF', true,
     'Indicative default rates seeded at install. Replace with the hospital''s approved tariff before go-live.');

INSERT INTO billing.service (code, name, category, department_id, sac_code, charge_unit, dicom_modality)
SELECT s.code, s.name, s.category, d.id, s.sac, s.unit, s.modality
FROM (VALUES
    ('REG-OPD', 'OPD Registration',               'REGISTRATION', NULL,   '999312', 'PER_VISIT', NULL),
    ('CON-NEW', 'Consultation - New Visit',       'CONSULTATION', NULL,   '999312', 'PER_VISIT', NULL),
    ('CON-FUP', 'Consultation - Follow-up',       'CONSULTATION', NULL,   '999312', 'PER_VISIT', NULL),
    ('CON-EMR', 'Emergency Consultation',         'CONSULTATION', 'EMER', '999312', 'PER_VISIT', NULL),
    ('ADM-CHG', 'Admission Charges',              'ADMISSION',    NULL,   '999311', 'PER_UNIT',  NULL),
    ('BED-DAY', 'Bed Charges',                    'BED',          NULL,   '999311', 'PER_DAY',   NULL),
    ('NUR-DAY', 'Nursing Charges',                'NURSING',      NULL,   '999314', 'PER_DAY',   NULL),
    ('VIS-IPD', 'Consultant Visit (IPD)',         'DOCTOR_VISIT', NULL,   '999311', 'PER_UNIT',  NULL),
    ('LAB-CBC', 'Complete Blood Count',           'LABORATORY',   'PATH', '999316', 'PER_TEST',  NULL),
    ('LAB-RBS', 'Random Blood Sugar',             'LABORATORY',   'PATH', '999316', 'PER_TEST',  NULL),
    ('LAB-LFT', 'Liver Function Test',            'LABORATORY',   'PATH', '999316', 'PER_TEST',  NULL),
    ('LAB-RFT', 'Renal Function Test',            'LABORATORY',   'PATH', '999316', 'PER_TEST',  NULL),
    ('RAD-XCH', 'X-Ray Chest PA View',            'RADIOLOGY',    'RADI', '999316', 'PER_STUDY', 'DX'),
    ('RAD-USA', 'USG Whole Abdomen',              'RADIOLOGY',    'RADI', '999316', 'PER_STUDY', 'US'),
    ('RAD-CTH', 'CT Head (Plain)',                'RADIOLOGY',    'RADI', '999316', 'PER_STUDY', 'CT')
) AS s (code, name, category, dept_code, sac, unit, modality)
LEFT JOIN core.department AS d ON d.code = s.dept_code;

INSERT INTO billing.tariff_rate (tariff_plan_id, service_id, bed_category, amount, valid_from)
SELECT tp.id, sv.id, r.bed_category, r.amount, DATE '2026-04-01'
FROM (VALUES
    ('REG-OPD', 'ALL',            50.00),
    ('CON-NEW', 'ALL',           300.00),
    ('CON-FUP', 'ALL',           150.00),
    ('CON-EMR', 'ALL',           500.00),
    ('ADM-CHG', 'ALL',           500.00),
    ('BED-DAY', 'GENERAL',       800.00),
    ('BED-DAY', 'SEMI_PRIVATE', 1500.00),
    ('BED-DAY', 'PRIVATE',      3000.00),
    ('BED-DAY', 'ICU',          4500.00),
    ('NUR-DAY', 'GENERAL',       300.00),
    ('NUR-DAY', 'SEMI_PRIVATE',  400.00),
    ('NUR-DAY', 'PRIVATE',       500.00),
    ('NUR-DAY', 'ICU',          1000.00),
    ('VIS-IPD', 'GENERAL',       300.00),
    ('VIS-IPD', 'SEMI_PRIVATE',  400.00),
    ('VIS-IPD', 'PRIVATE',       500.00),
    ('VIS-IPD', 'ICU',           700.00),
    ('LAB-CBC', 'ALL',           300.00),
    ('LAB-RBS', 'ALL',            80.00),
    ('LAB-LFT', 'ALL',           600.00),
    ('LAB-RFT', 'ALL',           600.00),
    ('RAD-XCH', 'ALL',           400.00),
    ('RAD-USA', 'ALL',          1200.00),
    ('RAD-CTH', 'ALL',          2500.00)
) AS r (service_code, bed_category, amount)
JOIN billing.service AS sv ON sv.code = r.service_code
CROSS JOIN billing.tariff_plan AS tp
WHERE tp.code = 'GENERAL';

-- STARTER adult intervals (see the table comment): to be replaced by the laboratory's own.
INSERT INTO clinical.lab_analyte (service_id, code, name, unit, decimals, ref_low, ref_high,
                                  ref_low_female, ref_high_female, critical_low, critical_high, display_order)
SELECT sv.id, a.code, a.name, a.unit, a.decimals, a.lo, a.hi, a.lo_f, a.hi_f, a.crit_lo, a.crit_hi, a.ord
FROM (VALUES
    ('LAB-CBC', 'HB',        'Haemoglobin',             'g/dL',     1,  13.0,  17.0,  12.0,  15.0,   7.0,   20.0, 1),
    ('LAB-CBC', 'TLC',       'Total leucocyte count',   '10^3/uL',  1,   4.0,  11.0,  NULL,  NULL,   2.0,   30.0, 2),
    ('LAB-CBC', 'PLT',       'Platelet count',          '10^3/uL',  0, 150.0, 410.0,  NULL,  NULL,  20.0, 1000.0, 3),
    ('LAB-RBS', 'GLU_R',     'Random blood glucose',    'mg/dL',    0,  70.0, 140.0,  NULL,  NULL,  50.0,  400.0, 1),
    ('LAB-LFT', 'TBIL',      'Total bilirubin',         'mg/dL',    2,   0.3,   1.2,  NULL,  NULL,  NULL,   NULL, 1),
    ('LAB-LFT', 'DBIL',      'Direct bilirubin',        'mg/dL',    2,   0.0,   0.3,  NULL,  NULL,  NULL,   NULL, 2),
    ('LAB-LFT', 'AST',       'AST (SGOT)',              'U/L',      0,   0.0,  40.0,  NULL,  NULL,  NULL,   NULL, 3),
    ('LAB-LFT', 'ALT',       'ALT (SGPT)',              'U/L',      0,   0.0,  40.0,  NULL,  NULL,  NULL,   NULL, 4),
    ('LAB-LFT', 'ALP',       'Alkaline phosphatase',    'U/L',      0,  44.0, 147.0,  NULL,  NULL,  NULL,   NULL, 5),
    ('LAB-LFT', 'TP',        'Total protein',           'g/dL',     1,   6.0,   8.3,  NULL,  NULL,  NULL,   NULL, 6),
    ('LAB-LFT', 'ALB',       'Albumin',                 'g/dL',     1,   3.5,   5.0,  NULL,  NULL,  NULL,   NULL, 7),
    ('LAB-RFT', 'UREA',      'Blood urea',              'mg/dL',    0,  15.0,  40.0,  NULL,  NULL,  NULL,   NULL, 1),
    -- CREAT is the code the drug-safety renal guard reads.
    ('LAB-RFT', 'CREAT',     'Serum creatinine',        'mg/dL',    2,   0.7,   1.3,   0.6,   1.1,  NULL,   NULL, 2),
    ('LAB-RFT', 'SODIUM',    'Sodium',                  'mmol/L',   0, 135.0, 145.0,  NULL,  NULL, 120.0,  160.0, 3),
    ('LAB-RFT', 'POTASSIUM', 'Potassium',               'mmol/L',   1,   3.5,   5.1,  NULL,  NULL,   2.8,    6.2, 4)
) AS a (service_code, code, name, unit, decimals, lo, hi, lo_f, hi_f, crit_lo, crit_hi, ord)
JOIN billing.service AS sv ON sv.code = a.service_code;

-- LOINC codes of the tests and their parameters, so shared lab reports are coded (ABDM / NRCES).
UPDATE billing.service AS sv SET loinc_code = l.loinc, loinc_display = l.display
FROM (VALUES
    ('LAB-CBC', '58410-2', 'CBC panel - Blood by Automated count'),
    ('LAB-RBS', '2345-7',  'Glucose [Mass/volume] in Serum or Plasma'),
    ('LAB-LFT', '24325-3', 'Hepatic function 2000 panel - Serum or Plasma'),
    ('LAB-RFT', '24362-6', 'Renal function 2000 panel - Serum or Plasma')
) AS l (code, loinc, display)
WHERE sv.code = l.code;

UPDATE clinical.lab_analyte AS a SET loinc_code = l.loinc, loinc_display = l.display
FROM billing.service AS sv, (VALUES
    ('LAB-CBC', 'HB',        '718-7',  'Hemoglobin [Mass/volume] in Blood'),
    ('LAB-CBC', 'TLC',       '6690-2', 'Leukocytes [#/volume] in Blood by Automated count'),
    ('LAB-CBC', 'PLT',       '777-3',  'Platelets [#/volume] in Blood by Automated count'),
    ('LAB-RBS', 'GLU_R',     '2345-7', 'Glucose [Mass/volume] in Serum or Plasma'),
    ('LAB-LFT', 'TBIL',      '1975-2', 'Bilirubin.total [Mass/volume] in Serum or Plasma'),
    ('LAB-LFT', 'DBIL',      '1968-7', 'Bilirubin.direct [Mass/volume] in Serum or Plasma'),
    ('LAB-LFT', 'AST',       '1920-8', 'Aspartate aminotransferase [Enzymatic activity/volume] in Serum or Plasma'),
    ('LAB-LFT', 'ALT',       '1742-6', 'Alanine aminotransferase [Enzymatic activity/volume] in Serum or Plasma'),
    ('LAB-LFT', 'ALP',       '6768-6', 'Alkaline phosphatase [Enzymatic activity/volume] in Serum or Plasma'),
    ('LAB-LFT', 'TP',        '2885-2', 'Protein [Mass/volume] in Serum or Plasma'),
    ('LAB-LFT', 'ALB',       '1751-7', 'Albumin [Mass/volume] in Serum or Plasma'),
    ('LAB-RFT', 'UREA',      '3091-6', 'Urea [Mass/volume] in Serum or Plasma'),
    ('LAB-RFT', 'CREAT',     '2160-0', 'Creatinine [Mass/volume] in Serum or Plasma'),
    ('LAB-RFT', 'SODIUM',    '2951-2', 'Sodium [Moles/volume] in Serum or Plasma'),
    ('LAB-RFT', 'POTASSIUM', '2823-3', 'Potassium [Moles/volume] in Serum or Plasma')
) AS l (service_code, code, loinc, display)
WHERE sv.code = l.service_code AND a.service_id = sv.id AND a.code = l.code;

-- Medicines sold by the pharmacy: one catalogue entry; each bill line names the drug and batch.
INSERT INTO billing.service (code, name, category, sac_code, charge_unit)
VALUES ('PHM-DRUG', 'Medicines', 'PHARMACY', '3004', 'PER_UNIT');

INSERT INTO pharmacy.store (code, name, kind) VALUES
    ('MAIN', 'Main store', 'MAIN'),
    ('DISPENSARY', 'OPD pharmacy', 'DISPENSARY');

-- STARTER formulary of common OPD medicines, for the pharmacist to review and extend
-- (schedules, GST rates and reorder levels included). Brands are examples, not preferences.
INSERT INTO pharmacy.generic (name, atc_code, drug_class) VALUES
    ('Paracetamol', 'N02BE01', 'Anilides'),
    ('Ibuprofen', 'M01AE01', 'NSAIDs'),
    ('Amoxicillin', 'J01CA04', 'Penicillins'),
    ('Azithromycin', 'J01FA10', 'Macrolides'),
    ('Cefixime', 'J01DD08', 'Cephalosporins'),
    ('Cetirizine', 'R06AE07', 'Antihistamines'),
    ('Pantoprazole', 'A02BC02', 'Proton pump inhibitors'),
    ('Metformin', 'A10BA02', 'Biguanides'),
    ('Amlodipine', 'C08CA01', 'Calcium channel blockers'),
    ('Atorvastatin', 'C10AA05', 'Statins'),
    ('Oral rehydration salts', NULL, 'Oral rehydration'),
    ('Ondansetron', 'A04AA01', 'Antiemetics'),
    ('Salbutamol', 'R03AC02', 'Beta-2 agonists'),
    ('Alprazolam', 'N05BA12', 'Benzodiazepines'),
    ('Warfarin', 'B01AA03', 'Vitamin K antagonists'),
    ('Insulin (human, regular)', 'A10AB01', 'Insulins');

INSERT INTO pharmacy.drug (code, brand_name, manufacturer, dosage_form, strength, default_route, schedule, is_high_alert,
                           is_nlem, gst_rate, dispensing_unit, reorder_level)
VALUES
    ('PCM-650', 'Dolo 650', 'Micro Labs', 'TABLET', '650 mg', 'ORAL', 'OTC', false, true, 12, 'tablet', 500),
    ('IBU-400', 'Brufen 400', 'Abbott', 'TABLET', '400 mg', 'ORAL', 'H', false, true, 12, 'tablet', 200),
    ('AMX-500', 'Mox 500', 'Sun Pharma', 'CAPSULE', '500 mg', 'ORAL', 'H', false, true, 12, 'capsule', 200),
    ('AZI-500', 'Azithral 500', 'Alembic', 'TABLET', '500 mg', 'ORAL', 'H', false, true, 12, 'tablet', 100),
    ('CFX-200', 'Taxim-O 200', 'Alkem', 'TABLET', '200 mg', 'ORAL', 'H1', false, false, 12, 'tablet', 100),
    ('CTZ-10', 'Okacet 10', 'Cipla', 'TABLET', '10 mg', 'ORAL', 'H', false, true, 12, 'tablet', 200),
    ('PAN-40', 'Pan 40', 'Alkem', 'TABLET', '40 mg', 'ORAL', 'H', false, true, 12, 'tablet', 300),
    ('MET-500', 'Glycomet 500', 'USV', 'TABLET', '500 mg', 'ORAL', 'H', false, true, 12, 'tablet', 300),
    ('AML-5', 'Amlong 5', 'Micro Labs', 'TABLET', '5 mg', 'ORAL', 'H', false, true, 12, 'tablet', 300),
    ('ATV-10', 'Atorva 10', 'Zydus', 'TABLET', '10 mg', 'ORAL', 'H', false, true, 12, 'tablet', 200),
    ('ORS', 'Electral', 'FDC', 'POWDER', '21.8 g', 'ORAL', 'OTC', false, true, 12, 'sachet', 100),
    ('OND-4', 'Emeset 4', 'Cipla', 'TABLET', '4 mg', 'ORAL', 'H', false, true, 12, 'tablet', 100),
    ('SAL-INH', 'Asthalin inhaler', 'Cipla', 'INHALER', '100 mcg/dose', 'INHALATION', 'H', false, true, 12, 'inhaler', 20),
    ('ALP-025', 'Restyl 0.25', 'Cipla', 'TABLET', '0.25 mg', 'ORAL', 'H1', false, true, 12, 'tablet', 50),
    ('WAR-5', 'Warf 5', 'Intas', 'TABLET', '5 mg', 'ORAL', 'H', true, true, 12, 'tablet', 50),
    ('INS-R', 'Huminsulin R 40 IU/ml', 'Eli Lilly', 'INJECTION', '40 IU/ml, 10 ml', 'SC', 'H', true, true, 5, 'vial', 10);

INSERT INTO pharmacy.drug_component (drug_id, generic_id, strength_value, strength_unit)
SELECT d.id, g.id, c.value, c.unit
FROM (VALUES
    ('PCM-650', 'Paracetamol', 650, 'mg'), ('IBU-400', 'Ibuprofen', 400, 'mg'), ('AMX-500', 'Amoxicillin', 500, 'mg'),
    ('AZI-500', 'Azithromycin', 500, 'mg'), ('CFX-200', 'Cefixime', 200, 'mg'), ('CTZ-10', 'Cetirizine', 10, 'mg'),
    ('PAN-40', 'Pantoprazole', 40, 'mg'), ('MET-500', 'Metformin', 500, 'mg'), ('AML-5', 'Amlodipine', 5, 'mg'),
    ('ATV-10', 'Atorvastatin', 10, 'mg'), ('ORS', 'Oral rehydration salts', 21.8, 'g'), ('OND-4', 'Ondansetron', 4, 'mg'),
    ('SAL-INH', 'Salbutamol', 100, 'mcg'), ('ALP-025', 'Alprazolam', 0.25, 'mg'), ('WAR-5', 'Warfarin', 5, 'mg'),
    ('INS-R', 'Insulin (human, regular)', 40, 'IU/ml')
) AS c (code, generic, value, unit)
JOIN pharmacy.drug d ON d.code = c.code
JOIN pharmacy.generic g ON g.name = c.generic;

COMMIT;
