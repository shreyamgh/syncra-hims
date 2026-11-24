# Syncra HIMS

An ABDM-compliant Hospital Information Management System for a 50-bed hospital,
modelled on C-DAC's e-Sushrut architecture.

## Build plan status

| Step | Scope | Status |
|------|-------|--------|
| 1 | Docker Compose runtime (PostgreSQL 16, Redis, RabbitMQ, Orthanc, API Gateway, AI service) | **Done** |
| 2 | Database schema `database/init-schema.sql` and its initialisation | **Done** |
| 3 | Patient Service: UHID `SYNC-YYYYMMDD-XXXX`, OPD token queue, IPD 50-bed wards | **Done** |
| 4 | Doctor Desk EMR with WebSocket dictation (Shrutlekhan) | **Done** |
| 5 | Billing & tariff engine (OPD slips, IPD discharge bills) | **Done** |
| 6 | ABDM Milestones 1 & 2 (ABHA verification, HIP FHIR bundles) | **Done** (verified against a simulated ABDM; sandbox run needs the hospital's credentials) |
| 7 | Single sign-on (IAM Service), administration desk, front office (registration, OPD and IPD billing, admissions, bed board) | **Done** |
| 8 | Laboratory and imaging desks, critical-result call log, in-browser DICOM viewer (OHIF), vaccinations on the Doctor Desk | **Done** |
| 9 | Pharmacy: stores, goods receipt, requisitions, FEFO dispensing, GST invoices, Schedule H1 register | **Done** |
| 10 | ABDM Milestone 3 (this hospital as HIU) and all eight ABDM record types, with coded lab results | **Done** (simulated ABDM) |

**In the specification but not built yet:** operation theatre scheduling, the emergency
department, insurance / TPA claims, a DICOM Modality Worklist for the scanners, Camunda
workflows, and telemedicine. ABHA creation by biometric or demographic authentication is
also not built (OTP only).

## Quick start

You only need Docker on the workstation. JDK, Maven and Python run inside containers.

```bash
./scripts/init-env.sh            # once: writes .env with random 256-bit secrets (mode 600)
docker compose up -d --build     # dev: also publishes datastore ports on 127.0.0.1
docker compose ps                # wait until all twelve services are "healthy"
make seed-dev                    # development only: sample staff and their sign-in accounts
make seed-demo                   # development only: a demo OPD morning, lab and imaging work, a stocked pharmacy
open http://localhost:3000       # sign in, e.g. as dev.doctor, dev.reception, dev.pharmacist, dev.admin
```

The development accounts share one password, written at the top of
`database/dev/seed-dev-staff.sql`. It is for development only: `make seed-dev` and
`make seed-demo` refuse to run when `.env` says `SYNCRA_ENVIRONMENT=production`. On a
hospital server, the administrator creates accounts on the Administration desk.

On a hospital server, leave out the dev overrides:

```bash
docker compose -f docker-compose.yml up -d --build
```

`make help` lists the shortcuts (`make test`, `make token`, `make logs`, …).

## Architecture

```
                 Browsers (Doctor Desk, Front Office)        CT / CR / USG modalities
                               │ HTTPS/WSS :8080                      │ DICOM C-STORE :4242
                               ▼                                      ▼
  ┌──────────────── app network ─────────────────────────────────────────────────────┐
  │  api-gateway  ── JWT ─┬─► patient-service (Step 3)   /api/v1/patients|opd|ipd/** │
  │  (Spring Cloud GW)    ├─► billing-service (Step 5)   /api/v1/billing/**           │
  │                       ├─► iam-service                /api/v1/auth|iam/**          │
  │                       ├─► pharmacy-service           /api/v1/pharmacy/**          │
  │                       ├─► abdm-service (Step 6)      /api/v1/abdm/**              │
  │  ABDM gateway ─ ABDM JWT ─► abdm-service             /api/v3/** (callbacks)       │
  │  Other HIPs ─ URL secret ─► abdm-service             /hiu/v3/data/push/*          │
  │                       ├─► ai-service (FastAPI)       /api/v1/ai/**, /ws/ai/**     │
  │                       └─► orthanc (PACS)             /pacs/dicom-web/**           │
  └───────┬──────────────────────────┬───────────────────────────┬───────────────────┘
          │                          │                           │
  ┌───────▼──────────── data network (internal, no internet egress) ─────────────────┐
  │   redis (rate limits, cache)   postgres 16 (syncra_hims + orthanc DBs)   rabbitmq │
  └──────────────────────────────────────────────────────────────────────────────────┘
```

| Service | Image | Host exposure (prod) | Dev-only loopback |
|---------|-------|----------------------|-------------------|
| api-gateway | built from `services/api-gateway` | `:8080` | — |
| patient-service | built from `services/patient-service` | none | — |
| billing-service | built from `services/billing-service` | none | — |
| abdm-service | built from `services/abdm-service` | none (reached via the gateway) | — |
| iam-service | built from `services/iam-service` | none (reached via the gateway) | — |
| pharmacy-service | built from `services/pharmacy-service` | none (reached via the gateway) | — |
| doctor-desk | built from `frontend/doctor-desk` (nginx) | `:3000` | — |
| ai-service | built from `services/ai-service` | none | `127.0.0.1:8000` |
| orthanc | `orthancteam/orthanc:26.9.1` | DICOM `:4242` | HTTP `127.0.0.1:8042` |
| postgres | `postgres:16-alpine` | none | `127.0.0.1:5432` |
| redis | `redis:8.10-alpine` | none | `127.0.0.1:6379` |
| rabbitmq | `rabbitmq:4.3-management-alpine` | none | `127.0.0.1:5672`, UI `:15672` |

## Database schema (Step 2)

`database/init-schema.sql` defines 42 tables and 4 views in 11 schemas, one per
area of the hospital:

| Schema | Contents |
|--------|----------|
| `core` | departments, staff, financial-year document numbering |
| `patient` | master patient index; the UHID `SYNC-YYYYMMDD-XXXX` is assigned by the database |
| `clinical` | encounters, vitals, complaints, diagnoses, notes, allergies, prescriptions, investigation orders, CDSS alerts, dictation provenance |
| `opd` | daily token queue per department (`v_queue_today`) |
| `ipd` | 7 wards, 50 beds, admissions, bed assignments (`v_bed_board`) |
| `pharmacy` | drug formulary (the CDSS knowledge base ships with the AI service) |
| `billing` | service catalogue, tariff plans and rates, bills, receipts (`v_bill_balance`) |
| `imaging` | index of Orthanc studies, kept current from Orthanc's change log |
| `abdm` | ABHA verifications, care contexts, consents, health-information transfers |
| `audit` | append-only access trail |

The database itself enforces the rules that must never break, whichever service writes:

- UHIDs, OPD tokens, admission numbers, bill numbers and receipt numbers are
  issued without gaps or duplicates. Bill and receipt numbers follow the 16-character
  GST format, for example `OPB/26-27/000001`.
- A bed can't be double-booked. A patient can't hold two beds or two active admissions.
  Ward gender restrictions are checked, and a patient can't be discharged while
  still holding a bed.
- Signed notes, issued prescriptions, final bills, receipts, bed history and the
  audit log can't be edited. Corrections are made by amendment, cancellation or voiding.
- Bill totals are calculated from line items. Discounts need an approver.
- Aadhaar numbers can't be stored. Identity goes through ABHA.

When a rule is broken, the database raises one of four custom error codes, which
services map to HTTP responses: `SYN01` record is locked, `SYN02` numbering is
exhausted, `SYN03` bed unavailable, `SYN04` workflow step out of order.

The seed data includes 13 departments, the 50-bed layout and a self-pay tariff with
**indicative** prices. Review the ward layout and prices before go-live.

The lab test parameters (`clinical.lab_analyte`) come with **starter adult reference
ranges and critical limits**. These are typical published values, not this laboratory's.
The laboratory must replace them with its own validated, method-specific ranges
(NABL / ISO 15189) before go-live, the same caution as the drug-safety knowledge base.

`make test-db` runs 48 checks of these rules as the application's database user
and rolls everything back afterwards.

### How initialisation runs

On the **first** boot of an empty `pgdata` volume, PostgreSQL runs:

1. `infra/postgres/initdb/00-bootstrap.sh` creates the `pgcrypto` and `pg_trgm`
   extensions, the least-privilege `syncra_app` role (DML only; default privileges
   cover every table that `init-schema.sql` creates), and an isolated `orthanc`
   database. Neither role can connect to the other's database.
2. `infra/postgres/initdb/10-apply-schema.sh` runs `database/init-schema.sql` with `ON_ERROR_STOP`.
3. `infra/postgres/initdb/99-mark-initialized.sh` writes a completion marker.

`infra/postgres/entrypoint-preflight.sh` refuses to start PostgreSQL when the
schema file is missing on first boot, or when an earlier first boot failed
partway. Without it, an automatic restart would bring up a database with no schema.
If initialisation fails, fix the cause and recreate the volume:

```bash
docker compose rm -sf postgres && docker volume rm syncra-hims_pgdata && docker compose up -d
```

Application services connect as `SYNCRA_APP_DB_USER`, never as the superuser.
PostgreSQL is configured with `log_parameter_max_length=0` so that bound values
(patient names, identifiers) never reach the server logs.

## Security model

- **Single entry point.** Only the gateway (and Orthanc's DICOM port, for modalities)
  is published. Datastores sit on an internal network with no internet egress.
- **JWT (HS256).** Every routed path requires a token with a valid signature,
  `iss`, `aud`, `sub`, and an `exp`. The `roles` claim maps to `ROLE_*` authorities.
  The original `Authorization` header goes to services unchanged so each one
  re-validates it. Tokens come from the IAM Service (see *Sign-in*); `make token`
  still mints a development token for scripts.
- **WebSockets.** Browsers can't set headers on a WebSocket handshake, so only
  `/ws/**` accepts `?access_token=`. The gateway strips that parameter before
  proxying, so the token never reaches upstream logs. This was checked end to end.
- **PACS.** `/pacs/dicom-web/**` is JWT-protected. The gateway replaces the caller's
  token with Orthanc service credentials, so Orthanc's HTTP API is never exposed.
- **Rate limiting.** Redis token bucket, 25 req/s with a burst of 50 per user. It
  fails open if Redis is down. The PACS route isn't rate-limited because viewers
  fetch frames in bursts.
- **ABDM callbacks.** `POST /api/v3/**` is the one route the gateway lets through
  without a Syncra JWT, because ABDM signs its calls with its own key. The ABDM
  Service verifies that token (signature against ABDM's published keys, expiry) on
  every call, and a Syncra staff token is refused there, just as ABDM's token is
  refused on the staff API.
- **Records pushed by other hospitals.** `POST /hiu/v3/data/push/*` is also let through
  without a Syncra JWT: ABDM's data push carries no credential. Each data request gets
  its own push URL with a 256-bit secret (only its SHA-256 is stored) and its own
  one-time Curve25519 key, so only the hospital ABDM sent the request to can deliver,
  and only data encrypted for that key is accepted. Pages are capped at 10 MB.
- **Correlation.** `X-Request-Id` is generated or validated at the edge, forwarded
  upstream, and echoed on every response, including 401 and 429.
- **Containers.** Gateway and AI service run as non-root on a read-only root
  filesystem, with all Linux capabilities dropped and `no-new-privileges` set.

## Patient Service (Step 3)

Spring Boot 3.2, Java 21 with virtual threads, and JPA. It connects to the database
as the limited `syncra_app` user, and Hibernate checks its mappings against the real
schema at startup. It re-checks every token the gateway forwards, enforces roles per
endpoint, and writes the audit trail.

| Endpoint | Who | Purpose |
|----------|-----|---------|
| `POST /api/v1/patients` | reception, nurse, doctor, MRD, admin | Register; the database issues the UHID. Returns 409 `POSSIBLE_DUPLICATE` with existing UHIDs unless `?confirmNotDuplicate=true` |
| `GET /api/v1/patients/{uhid}` | all staff | Full record, with an `ETag` (audited) |
| `PUT /api/v1/patients/{uhid}` | as for registration | Update demographics; needs `If-Match` (428 if missing, 412 if stale) |
| `GET /api/v1/patients?q=` | all staff | Search by UHID, mobile, ABHA number/address or fuzzy name; mobiles are masked |
| `GET /api/v1/opd/departments[/{code}/doctors]` | all staff | Pick-lists for the desk |
| `POST /api/v1/opd/visits` | OPD desk | Issue a token. Priority defaults by age (60+ senior citizen, under 5) |
| `GET /api/v1/opd/queue?department=` | OPD desk, billing | Queue board in priority-then-token order, with counts |
| `POST /api/v1/opd/queue/{dept}/call-next` | OPD desk | Next patient. `SKIP LOCKED` stops two doctors getting the same one |
| `POST /api/v1/opd/visits/{id}/call\|start\|complete\|hold\|requeue\|no-show\|cancel` | desk; start and complete are doctors only | Queue state machine; illegal moves return 409 |
| `GET /api/v1/ipd/wards`, `/beds`, `/beds/available?gender=&category=` | IPD view | Ward census, bed board, bed finder |
| `PUT /api/v1/ipd/beds/{code}/status` | nurse, admin | Housekeeping (CLEANING → AVAILABLE, maintenance, block, reserve) |
| `POST /api/v1/ipd/admissions` | admission desk | Admit, with an optional bed; issues `ADM/YY-YY/NNNNNN` |
| `POST /api/v1/ipd/admissions/{id}/bed` | nurse, doctor, desk | Allocate a bed or transfer |
| `POST /api/v1/ipd/admissions/{id}/discharge-initiation`, `/discharge` | nurse, doctor | Discharge: releases the bed and closes the stay. `EXPIRED` marks the patient deceased |
| `GET /api/v1/emr/vaccines` | doctor, nurse | Vaccine catalogue (UIP schedule and others) for the pick-list |
| `POST /api/v1/emr/encounters/{id}/immunizations` | doctor, nurse | Record a vaccination, or an earlier dose transcribed from the patient's card (`primarySource: false`, `reportOrigin`), or a dose not given (`status: NOT_DONE`, with the reason). Refused: an expired vial or one without batch and expiry, a dose outside the visit or beyond the vaccine's series, or a dose already on record. Allowed up to 24 h after the visit ends |
| `POST …/immunizations/{immunizationId}/entered-in-error` | doctor, nurse | Withdraw a wrong entry. Vaccinations are never edited or deleted |
| `GET /api/v1/emr/patients/{uhid}/immunizations` | doctor, nurse | Vaccination history with next due dates (audited) |
| `POST /api/v1/emr/encounters/{id}/diagnoses` | doctor | Coded diagnosis (ICD-10, optional SNOMED CT), provisional / differential / final. One primary final diagnosis per encounter |
| `POST …/diagnoses/{id}/entered-in-error` | doctor | Withdraw a wrong diagnosis |
| `GET` / `POST /api/v1/emr/patients/{uhid}/allergies` | doctor, nurse | Allergies (drug, drug class, food, environment), recorded once while active |
| `PUT …/allergies/{id}/status` | doctor, nurse | Inactive, resolved or refuted: allergies are never deleted |
| `POST /api/v1/emr/encounters/{id}/notes` | doctor, nurse | Clinical notes. History, examination, procedure, advice and discharge-summary notes are doctors only. `sign: true` signs at once; `amendsNoteId` corrects a signed note |
| `PUT …/notes/{id}` (`If-Match`), `POST …/sign`, `…/entered-in-error` | the note's author | Edit a draft, sign it, withdraw it. A signed note can't be edited |
| `GET /api/v1/emr/orderables?q=&type=` | doctor, nurse | Search the orderable catalogue (laboratory, radiology and procedure services only) by name or code |
| `GET /api/v1/emr/me` | any staff | The caller's staff record (`linked: false` if the login has none): notes and orders are attributed to it |
| `POST /api/v1/emr/encounters/{id}/orders` | doctor | Order an investigation or procedure from the service catalogue. Radiology orders get a DICOM accession number (`RAD/26-27/000001`). Allowed up to 24 h after the visit ends |
| `POST /api/v1/emr/orders/{id}/sample` · `/result` · `/cancel` | nurse / lab technician · lab technician, doctor · doctor | Sample collected → result (summary plus structured values such as `CREAT`; final once reported) → cancel (not once billed or reported). The server sets each value's flag (L, H, C) from the test's parameters, as described below |
| `GET /api/v1/emr/orders/{id}/analytes` | worklist roles | The test's parameters with this patient's reference ranges (by sex; adults only), for the result form |
| `GET /api/v1/emr/worklist?type=LABORATORY\|RADIOLOGY\|PROCEDURE&status=&since=` | lab, radiography, radiology, nursing, doctors | Orders by status, STAT first. `since` limits closed lists (e.g. reported in the last 24 h) |
| `GET /api/v1/emr/imaging/studies?reportStatus=&matched=&reportedSince=` | as above | Radiology worklist from the PACS index, STAT first, with each study's clinical indication, current report and DICOMweb path |
| `POST /api/v1/emr/imaging/studies/{id}/match` · `/report` | radiographer, radiologist · radiologist | Match a study that arrived without an accession number. The report is a signed note; reporting again adds an addendum |

**PACS index.** Every 30 s the Patient Service reads Orthanc's change log from its last
position, and for each newly stable study records:

- the Study Instance UID, accession number, modality, description, and series and image counts;
- the patient (DICOM PatientID = UHID);
- the radiology order, matched by accession number. That order becomes `COMPLETED`, ready for reporting.

Images stay in Orthanc. Studies whose PatientID isn't a UHID here are counted and skipped.

Errors are RFC 7807 problem documents with a stable `code`. Database rule failures
(for example a bed of the wrong gender, or a bed that is taken) come back as 409/422
with a message staff can read. The Postgres driver runs with `logServerErrorDetail=false`
so patient values never reach exception messages or logs.

## Doctor Desk and Shrutlekhan dictation (Step 4)

**Doctor Desk** (`frontend/doctor-desk`): React 18, TypeScript and Tailwind, served by
unprivileged nginx on `:3000`. nginx also proxies `/api` and `/ws` to the gateway, so
the browser only ever talks to one origin. The layout follows the brief: dense tables
and few clicks.

- The left column is today's department queue: token, priority badge, patient, status
  and waiting time. **Call next** (`Alt+N`) serves emergencies first, then priority
  patients.
- The right column is the selected patient's workspace: a header with allergies, then
  six tabs (`Alt+1` to `Alt+6`):
  - **Consultation**: the dictation bar, vitals, complaints, diagnoses (ICD-10 search)
    and the prescription table, signed together.
  - **Orders & results**: order a test or scan from the catalogue (keyboard search;
    the primary diagnosis is offered as the clinical indication, which imaging
    requires). Follow each order through to its result. Values out of range are
    flagged, and critical ones are red and counted on the tab. An open order can be
    cancelled with a reason.
  - **Notes**: write a note and sign it, or keep it as a draft. Drafts are edited
    under a version check (`If-Match`). A signed note is corrected only by a signed
    amendment, and the original stays visible as *amended*. Authors can mark their
    own notes entered in error. Doctors get doctor note types and nurses get nursing,
    progress and other notes. Radiology reports appear here read-only.
  - **Allergies**: the patient's full list across visits. Record an allergy (a drug
    class needs the class named), or end it as inactive, resolved or refuted. Changes
    reach the header and the drug-safety check straight away. An empty list is shown
    as "not recorded", never as "no known allergies".
  - **Vaccinations**: the patient's doses across visits and the next ones due. A dose is
    recorded as given here (batch and expiry required), transcribed from the patient's
    card, or not given with a reason; a wrong entry is withdrawn with a reason, never
    deleted.
  - **Other hospitals**: the patient's records held elsewhere, through ABDM (see
    *Milestone 3*). Doctors only.
  - Unsaved note text counts as unsigned work when switching patients, and `Ctrl+Enter`
    signs only from the Consultation tab.
- **Drug safety (CDSS).** As the prescription is written, the AI service checks it
  against the patient's allergies, age, sex, weight and latest creatinine.
  - Red banners (contraindicated, major) need a reason before signing; amber is
    moderate; grey is minor.
  - Signing re-runs the same check on the server. A serious alert without a reason is
    refused (`422 CDSS_OVERRIDE_REQUIRED`). Every alert is stored with its resolution
    and the knowledge-base version.
  - If the server-side check cannot run, signing goes ahead unchecked and the audit
    entry records `cdssChecked: false`. A CDSS that rejects the request (HTTP 4xx) is
    logged as an error, because that is a defect, not an outage. Set
    `SYNCRA_CDSS_REQUIRED=true` to refuse signing instead (`503 CDSS_UNAVAILABLE`).
- Press the mic (`Alt+M`) and speak. The transcript appears after each pause, and the
  form fills in live. Every value that came from dictation has a violet outline and a
  mic marker.
- **Dictation never overwrites the doctor.** A value typed or edited by hand becomes
  the doctor's own, and later dictation leaves it alone.
- If the transcript is wrong, correct it and press **Re-extract**.
- **Sign & complete visit** (`Ctrl+Enter`) saves the vitals, complaints, issued
  prescription and dictation provenance in one transaction, then closes the OPD visit.
- Nurses see the consultation read-only, and can write nursing notes and record allergies. Dictation is limited to doctors and nurses at the gateway.

**Speech-to-text** runs Whisper (`faster-whisper`, CTranslate2 int8 on CPU) **inside the
hospital**, so audio never leaves the network.

- The default model is `small`, which handles Indian English and Hindi well. It
  downloads once (about 480 MB) into the `ai-models` volume on first start, and the AI
  service reports not-ready until it has loaded. For sites with no internet access,
  copy the model in ahead of time and set `SYNCRA_AI_WHISPER_MODEL` to its path.
- The stream is split into utterances at pauses, analysed in 20 ms frames, so how the
  browser chunks the audio doesn't matter. Pure silence is never sent to the model,
  which avoids invented text.
- Each utterance is primed with clinical vocabulary ("BP 130/80", "1-0-1", "Tab", "TDS"…).

**Clinical extraction** (`app/clinical/extraction.py`) is rule-based, so it's
deterministic and auditable. It turns Indian OPD phrasing into the database's own codes:

- vitals (BP "130 by 80", temperature in °F converted to °C, SpO₂, pulse, RR, weight,
  height, sugar);
- complaints with durations;
- diagnoses ("diagnosis …", "impression …", "final diagnosis …"), each with a suggested
  ICD-10 code from a table of common OPD diagnoses (`app/clinical/icd10.py`) for the
  doctor to confirm;
- prescription lines with form, strength, dose, `1-0-1`/OD/BD/TDS/QID/HS/SOS frequency,
  timing, route and days.

Values outside safe ranges are dropped with a warning rather than guessed. It's also
available as `POST /api/v1/ai/extract`.

**Saved record:** `POST /api/v1/emr/encounters/{id}/consultation` (doctors only).
Dictated rows are stored as `source = DICTATION` and linked to a `clinical.dictation_session`
row, which records the engine, language, audio length and doctor. Audio itself is never stored.

**WebSocket contract** (`services/ai-service/app/dictation/protocol.py`):
1. The client sends `start`.
2. The client streams PCM16 mono 16 kHz binary frames (at most 64 KiB each), then sends `stop`.
3. The server sends `ready`, then a `partial` after each utterance (the full transcript
   so far plus its extraction), then `final`. On failure it sends `error` and closes with
   1008, 1009, or 1013 if the model is still loading.

**CDSS** (`services/ai-service/app/cdss`, `POST /api/v1/ai/cdss/check`). It is
deterministic and stateless, and it checks:

- drug–drug interactions;
- duplicate therapy (the same drug twice, or two NSAIDs or SSRIs…);
- allergies, both direct and cross-reactive (penicillin → cephalosporins, NSAIDs);
- daily dose against adult, paediatric (mg/kg/day) and older-adult limits;
- older-adult cautions (Beers criteria);
- a renal guard: Cockcroft–Gault creatinine clearance from the latest structured lab
  result, checked against renal rules.

Drugs are recognised by generic name or by common Indian brand (Dolo, Combiflam,
Augmentin, Ecosprin…). When a check can't run, for example with no weight for a child
or an unclear frequency, it says so instead of guessing.

> **The knowledge base is a starter set** (`app/cdss/knowledge.json`, version
> `2026.11-starter`): about 30 well-established interactions plus the allergy, dose and
> renal rules, each citing its reference (BNF, Stockley's, NFI, Beers, EMA/FDA). It is
> marked `reviewed: false`, and the Doctor Desk shows "Rules pending pharmacist review"
> until the hospital's pharmacist and drug & therapeutics committee sign off a version.
> A reviewed file can be mounted with `SYNCRA_AI_CDSS_KNOWLEDGE_PATH`. Loading fails fast
> if a rule names an unknown drug or class.

> **HTTPS is required in production.** Browsers only allow microphone access on HTTPS
> or `localhost`. Terminate TLS in the `doctor-desk` nginx, or in front of it, before
> rolling out to OPD computers.

## Laboratory and imaging desks

The same web app (`:3000`) opens the right desk for each sign-in: doctors get the Doctor
Desk; lab technicians the **Laboratory**; radiographers and radiologists **Imaging**;
receptionists and billing clerks the **Front office**; pharmacists the **Pharmacy**;
administrators the **Administration** desk (and the front office). Nurses get both the
Doctor Desk and the Laboratory, where they collect samples, and switch between them in
the header. Each list refreshes on its own every 20–30 s.

**Laboratory** (LIS worklist):

- **To collect**, **To report** and **Reported (24 h)**, STAT first, searchable by patient,
  UHID or test.
- **Enter result** builds the form from the test's parameters, with units and this
  patient's reference range. `Enter` moves down the column.
- The flag previews as you type. The server sets the stored flag itself, from the
  parameter ranges: L/H outside the range, C (critical) beyond a critical limit. A
  technician can raise a flag (for example to critical) but never lower the computed one.
- Values in another unit than the parameter's, and results for children, keep the flag
  entered by hand. Adult ranges don't apply to children, and the form says so.
- Reporting a critical value asks for confirmation, naming the value and the ordering
  doctor, and the confirmation reminds the technician to inform that doctor.
- Creatinine is reported as `CREAT` in mg/dL, so it feeds the drug-safety renal check.

**Imaging** (RIS worklist):

- **Awaiting images** lists radiology orders with the accession number to key in at the modality.
- **Unmatched studies** lists studies that reached the PACS without a matching accession.
  Each can be matched to an open imaging order of the same patient only.
- **To report**, STAT first, opens the report panel. It shows the clinical question, who
  ordered the study, and the series and image counts. The radiologist signs findings plus
  a one-line impression, which the doctor sees on the order.
- **Reported (7 days)**: a signed report is corrected by an addendum that starts from the
  current text. The original stays in the record.
- Radiographers can match studies but not report them.
- **View images** (on the Imaging desk and on a doctor's imaging orders) opens the study in
  the OHIF viewer served by Orthanc's plugin at `/pacs/ohif/`. The browser first gets a
  viewer cookie (`POST /pacs/session`: HttpOnly, SameSite=Strict, `Path=/pacs`, at most
  12 h) that the gateway accepts only for reads under `/pacs/`; only doctors, nurses,
  radiographers and radiologists get one. OHIF is not a certified diagnostic viewer:
  primary reporting stays on the PACS workstation.

**Critical results.** A value reported as critical opens a call to make: the
laboratory's **Critical to call** list shows it until someone logs whom they told, how
(phone, in person, ward nurse) and that the value was read back (NABL / ISO 15189). The
treating doctor then sees a banner on the Doctor Desk and acknowledges it; the trail
shows on the order.

## Billing (Step 5)

`services/billing-service`: Spring Boot, JDBC only. Line totals and bill totals are
computed by the database, and issued bills and receipts can't be edited.

**OPD slip** (`POST /api/v1/billing/opd-slips` with the token's `visitId`, optionally
with payment). It is issued and numbered at the counter:

- The registration fee is charged only if the patient hasn't paid one within
  `SYNCRA_BILLING_REGISTRATION_VALIDITY_DAYS` (default 365).
- The consultation is priced by visit type: new or referral, follow-up, or emergency.
- Investigation orders already placed are added, plus any extras from the desk.
- A token can be billed only once, unless its slip is cancelled.

**IPD bill**, one per admission:

- **Interim:** `…/admissions/{id}/bill/refresh` recalculates the admission charge,
  bed-days and nursing. **Bed-days use the midnight census:** one day per midnight in
  a bed, at that bed's ward-class rate, so transfers split correctly; a same-day stay
  counts as one day. Services the desk adds (consultant visits, procedures) survive
  every refresh. Admission, bed and nursing lines can't be added or removed by hand.
- **Advances:** `…/admissions/{id}/advances` while the patient is admitted.
- **Final:** `…/bill/finalize`, only after discharge has been initiated. It recalculates
  up to the discharge time, sets the advances against the bill, rounds to the rupee,
  and the database issues the serial number.
- **Settle or refund:** `POST /bills/{id}/payments` with `SETTLEMENT` or `REFUND`.
  Overpaying, or refunding more than the credit on the bill, is refused.

**Numbers and documents:**

- Serials are gap-free per financial year and fit the 16-character GST limit:
  `OPB/26-27/000001` (OPD), `IPB/…` (IPD), `RCT/…` (receipts), `RFD/…` (refunds).
- A bill with no tax is a *Bill of Supply*, since clinical services are GST-exempt.
  One that also carries taxable lines is an *Invoice-cum-Bill of Supply*.
- `GET /bills/{id}/print` returns print-ready HTML with the hospital letterhead
  (`SYNCRA_HOSPITAL_NAME`, `_ADDRESS`, `_PHONE`, `_GSTIN` in `.env`), Indian digit
  grouping and the amount in words (lakh/crore). Draft and interim bills carry a watermark.

**Who can do what:**

- Reception: OPD slips and payments.
- Billing clerks: IPD bills.
- Administrators: discounts (recorded as the approver), cancellations (only when no
  money is held against the bill) and voiding receipts.

Every action, including printing, is written to the audit trail.

**Shared library** (`libs/hims-common`): JWT re-validation, RFC 7807 errors,
database-error translation that keeps patient data out of messages, request IDs,
audit and the hospital clock are shared by the Patient, Billing and ABDM services through
Spring Boot auto-configuration. Their Docker builds use the repository root as
context and install the library first.

## Sign-in and administration (IAM Service)

Staff sign in with a username and password on the web app; the IAM Service
(`services/iam-service`, port 8087, behind the gateway) issues the JWT every service checks.

- Passwords are bcrypt-hashed (cost 12) and need at least 10 characters, must not be a
  common password or contain the username. New and reset accounts get a 12-character
  temporary password that must be changed at first sign-in.
- Five wrong passwords lock the account for 15 minutes. Unknown usernames take as long
  to refuse as wrong passwords.
- Access tokens last 30 minutes and are renewed in the background. The refresh token is
  an HttpOnly, SameSite=Strict cookie limited to `/api/v1/auth`, replaced on every use; a
  reused (stolen) refresh token ends that whole sign-in. Sessions end after 12 hours.
- Every sign-in, failure, lockout, password change and sign-out goes to the append-only
  `iam.auth_event`.
- The **Administration** desk (`ADMINISTRATOR`) creates accounts for staff records,
  resets passwords, disables, enables and unlocks accounts, and sets roles. An
  administrator cannot disable their own account or remove their own administrator role.
- `SYNCRA_IAM_DEVELOPMENT_SIGN_IN=true` (set only in `docker-compose.override.yml`) adds a
  "development sign-in with a token" link to the sign-in page.

## Front office

Receptionists and billing clerks get the **Front office** desk:

- **Patients & OPD**: search by name, UHID or mobile; register with a duplicate check
  (similar patients are shown and the receptionist confirms a different person); issue
  an OPD token; issue the OPD slip and take payment (cash, UPI, card, net banking, cheque).
- **Admissions & beds**: the 50-bed board by ward (available, occupied, reserved,
  cleaning, maintenance); admit a patient to a free bed; move a patient between beds.
- **Billing**: interim IPD bills with charges added from the tariff, advances, final
  bills and receipts. Bills print from `/print.html` (GST invoice or bill of supply).

## Pharmacy

`services/pharmacy-service` (port 8084, behind the gateway) and the **Pharmacy** desk
for pharmacists. Stock is kept by batch and expiry in two stores, the main store and the
OPD pharmacy counter; every movement is written to an append-only ledger.

- **Receive goods**: a supplier invoice (supplier, number, date) with one line per batch:
  expiry month, quantity, free quantity, purchase rate and MRP per dispensing unit.
  Expired batches, a rate above MRP, a future invoice date and a repeated
  supplier invoice are refused. Goods go into the main store.
- **Requisitions**: the counter asks the main store; issuing takes batches earliest
  expiry first (FEFO) and issues part when stock is short.
- **Counter**: signed prescriptions waiting, with the formulary medicine and quantity
  suggested (dose × times a day × days for tablets and capsules). Dispensing takes the
  counter's batches earliest expiry first and never an expired one; Schedule X is
  refused (it needs the narcotics register). **Invoice and receive** issues a GST
  invoice at MRP through the Billing Service (`PHB/` numbers; taxable value = MRP ÷
  (1 + GST rate)), takes the payment and prints.
- **Stock & alerts**: stock per medicine in each store, earliest expiry, and alerts for
  stock below the reorder level, batches expiring within 90 days and expired batches.
  A batch is written off (all or part) with a reason.
- **H1 register**: every Schedule H1 supply with prescriber, patient, drug, batch and
  quantity (Drugs and Cosmetics Rules 1945, rule 65(3)).

The formulary seeded in `init-schema.sql` (16 common OPD medicines with schedules, GST
rates and reorder levels) is a starter list for the hospital's pharmacist to review.

## ABDM (Step 6)

`services/abdm-service`: the hospital's ABDM bridge, as a Health Information Provider
(HIP). Spring Boot, JDBC, HAPI FHIR 8 (R4 model) and BouncyCastle.

**Switching it on.** It is off by default (`SYNCRA_ABDM_ENABLED=false`): records can be
previewed, but every call to ABDM answers 503 `ABDM_DISABLED` and ABDM's callbacks are
refused. To connect to the sandbox:

1. Register at the ABDM sandbox and get a client id and secret.
2. Register the hospital in the Health Facility Registry; its HFR id is the HIP id.
3. Register this bridge's public HTTPS URL with ABDM. ABDM calls `<url>/api/v3/...`.
4. In `.env`: `SYNCRA_ABDM_ENABLED=true`, `SYNCRA_ABDM_HIP_ID`, `SYNCRA_ABDM_CLIENT_ID`,
   `SYNCRA_ABDM_CLIENT_SECRET`. The sandbox URLs are the defaults; production URLs are
   noted in `.env.example`.

**Milestone 1, at the registration desk:**

| Endpoint | Who | Purpose |
|---|---|---|
| `POST /api/v1/abdm/abha/otp` | reception, nurse, doctor, MRD, admin | Send an OTP for an ABHA number (Aadhaar-linked or ABHA mobile) |
| `POST /api/v1/abdm/abha/verify` | as above | Verify it. Returns the ABDM profile to register from, and any patient already holding that ABHA. With `patientId`, attaches it straight away |
| `POST /api/v1/abdm/patients/{id}/abha` | as above | Attach a verification to a newly registered patient |
| `GET /api/v1/abdm/scan-share?context=` | as above | Today's Scan & Share queue for a counter |
| `POST /api/v1/abdm/scan-share/{id}/register` | as above | Patient registered or found: attach the shared ABHA |

- The ABHA number and OTP are sent RSA-OAEP encrypted with ABHA's published key.
  Every attempt, failed or not, is recorded in `abdm.abha_verification`.
- An ABHA is attached only from a successful verification made in the last 30 minutes,
  by the same member of staff, and only if gender and year of birth match the patient.
  Names are not compared. The Patient Service clears the verified stamp if staff edit
  the ABHA by hand.
- **ABHA creation with Aadhaar**, for patients who have no ABHA (`/api/v1/abdm/abha/enrolments`):
  1. Read ABDM's consent text to the patient; with `patientConsent: true` and the Aadhaar
     number, an OTP goes to their Aadhaar-linked mobile.
  2. `…/{id}/verify` with the OTP and a mobile number creates the ABHA. If the Aadhaar
     already has one, ABHA returns it instead (`isNew: false`). With `patientId`, it is
     attached to the patient straight away, with the same demographic checks as above.
  3. Optional: `…/{id}/mobile/otp` and `/mobile/verify` make a mobile that isn't
     Aadhaar-linked the ABHA contact. `…/{id}/address-suggestions` and `/address` choose
     the ABHA address (8–18 letters or digits). `…/{id}/card` returns the ABHA card to print.

  **Aadhaar handling** (Aadhaar Act 2016 s.29):
  - The number is checked (12 digits, Verhoeff check digit) before anything is sent, then
    encrypted for ABHA and dropped. It is never stored, never logged and shown only as
    `XXXX-XXXX-1234`; the tests check every table and the log output for it.
  - An enrolment in progress lives 30 minutes in Redis, belongs to the member of staff who
    started it, and holds ABHA's short-lived token only for the card.
  - `abdm.abha_enrolment` records the outcome and the ABDM consent version (no Aadhaar).
- **Scan & Share:** the patient scans the counter's QR code in their ABHA app. ABDM
  delivers the verified profile, and the patient gets a queue number back in the app.
  Sharing again keeps the same number. Profiles are deleted after 24 hours.

**Milestone 2, sharing records (HIP):**

| Endpoint | Who | Purpose |
|---|---|---|
| `GET /api/v1/abdm/encounters/{id}/records` | reception, nurse, doctor, MRD, admin | Record types this encounter can share, and its care context |
| `GET /api/v1/abdm/encounters/{id}/records/{hiType}` | doctor, MRD, admin | The FHIR R4 document (`application/fhir+json`, audited as an export) |
| `POST /api/v1/abdm/encounters/{id}/care-context` | reception, nurse, doctor, MRD, admin | Create the care context and link it to the patient's verified ABHA |
| `GET /api/v1/abdm/patients/{id}/care-contexts` | as above | Link status per encounter |
| `GET /api/v1/abdm/patients/{id}/consents` | doctor, MRD, admin | Consents received and what was sent under each |

ABDM calls these on the bridge. Each one answers 202 straight away, and the real reply
(`on-*`) follows once the data is committed:

| Callback | What happens |
|---|---|
| `/api/v3/hip/token/on-generate-token` | Link token for a patient. It is cached in Redis (25 min) and the waiting care contexts are linked with it |
| `/api/v3/link/on_carecontext` | Result of a link call. The care contexts become `LINKED` or `FAILED` |
| `/api/v3/consent/request/hip/notify` | Consent `GRANTED` (stored verbatim with the care contexts it covers), or `REVOKED`/`EXPIRED`/`DENIED`. Acknowledged with `on-notify` |
| `/api/v3/hip/health-information/request` | An HIU's request. It is checked against its consent (status, expiry, date range, key material, push URL), acknowledged with `on-request`, then the records are encrypted, pushed to the HIU in pages, and reported with `health-information/notify` |
| `/api/v3/hip/patient/share` | Scan & Share profile; answered with `on-share` and a queue number |
| `/api/v3/hip/patient/care-context/discover` | Patient-initiated linking: the patient searched this hospital in their PHR app. `on-discover` lists their unlinked records, or says why not |
| `/api/v3/hip/link/care-context/init` | The patient picked records to link: an OTP goes by SMS to the mobile registered with the hospital. Answered with `on-init` |
| `/api/v3/hip/link/care-context/confirm` | The patient entered the OTP: the records are linked to their ABHA. Answered with `on-confirm` |

- **Matching.** Callbacks are matched to the call they answer by `REQUEST-ID`. A link
  token is accepted only for a token request this service made, never because of who
  the body names. A callback ABDM sends again is not processed twice.
- **Patient-initiated linking.**
  - **Matching.** A search is answered only when exactly one patient matches:
    - either a patient whose verified ABHA is the searcher's;
    - or, all at once: ABDM's verified mobile equals the registered mobile, and gender,
      year of birth (±2 years if the date of birth was estimated) and name (similarity ≥ 0.4) agree.
  - **Excluded:** patients holding a different verified ABHA. A UHID the patient typed in
    can narrow a family sharing one phone, but is never enough on its own. Ambiguous
    searches are refused rather than guessed.
  - **Linking:** only records offered in that search can be linked, after an OTP sent to the
    registered mobile. The OTP is valid 10 minutes, with 3 attempts, at most 5 per patient
    per hour. Only a salted hash of it is stored.
  - **SMS:** sent through the hospital's provider (`SYNCRA_SMS_*` in `.env`; DLT-registered
    sender and template). Without one, ABDM is told the hospital can't send OTPs yet.
- **Care contexts.** One per encounter; its reference is the encounter id. OPD and
  emergency encounters share `OPConsultation` and `Prescription`. Inpatient stays share
  `DischargeSummary` and `Prescription`, and only after discharge. Any encounter shares
  `DiagnosticReport` once one of its investigations has a result, and `ImmunizationRecord`
  once a vaccination is recorded in it.
  - Results often arrive after the visit was linked. Linking the encounter again
    (`POST …/care-context`) adds the new record type.

**FHIR R4 records.** Each record is a FHIR R4 document bundle, laid out as in the NRCES
implementation guide that ABDM uses (`https://nrces.in/ndhm/fhir/r4`).

- **Contents:**
  - The Composition (SNOMED type and section codes).
  - Patient (UHID, plus the ABHA number once verified), Practitioner (HPR id, council
    registration) and Organization (HFR id), and the Encounter with its diagnoses.
  - Clinical resources: complaints as Conditions, vitals as LOINC/UCUM Observations,
    AllergyIntolerance, investigation orders (ServiceRequest) and results (DiagnosticReport).
  - **Diagnostic Report record** (`DiagnosticReport`): one DiagnosticReport per finished
    investigation, grouped into lab and imaging sections.
    - Lab results carry the result summary.
    - Radiology results carry the ImagingStudy (DICOM Study Instance UID, accession number,
      modality, series and image counts), the reporting radiologist and the signed report
      text. Images stay in the PACS.
    - Only final reports (or addenda, shared as "amended") with a signed report are shared;
      drafts and pending orders are left out.
  - **Immunization record** (`ImmunizationRecord`): an Immunization per vaccination recorded
    in the encounter, and an ImmunizationRecommendation with the next due doses.
    - **Doses given:** vaccine, dose in series, batch, expiry, manufacturer, site, route,
      volume and vaccinator.
    - **Doses transcribed from a card** (`primarySource: false` with its origin).
    - **Doses not given**, with the reason.
    - Entries marked in error are left out.
  - Medications as MedicationRequests, with structured timing (OD/BD/TDS…), route
    (SNOMED) and duration.
  - Advice (CarePlan), follow-up (Appointment), and signed notes (DocumentReference).
- **Properties:**
  - Records are generated from the EMR when needed, and never stored.
  - The same record always produces the same resource ids.
  - Each record passes HAPI's R4 validator with no errors.

**Encryption.** Uses Fidelius, ABDM's scheme: ECDH on Curve25519, then HKDF-SHA256 and
AES-GCM. The output is bit-for-bit what ABDM's reference tool (`fidelius-cli`) produces
for its published example. Each transfer uses a fresh key pair; the private key exists
only in memory.

**What is kept:**

- Only the SHA-256 and size of each record sent. The traffic log keeps paths and
  status codes, not message bodies.
- Every share and export is recorded in `audit.event_log`.
- Push URLs must be HTTPS and must not point at internal addresses.
- This HIP's gateway token is not sent to HIUs. Set
  `SYNCRA_ABDM_PUSH_SEND_SESSION_TOKEN=true` only if ABDM requires it.

**To confirm in the ABDM sandbox.** The simulated gateway follows the V3 documentation
available at the time. Some message details came from community guides because the
official pages couldn't be read; check these in the first sandbox run and adjust them
in `AbdmPaths` and the handlers:

- the callback paths, including the `user-initiated-linking` ones, and the ABHA enrolment
  paths (`/v3/enrollment/...`, the `Transaction_Id` header on address suggestions);
- whether ABHA numbers in `generate-token` are hyphenated;
- the `checksum` encoding (hex MD5 of the plain record);
- the format of HIP error codes;
- whether ABDM's tokens should be pinned to an issuer (`SYNCRA_ABDM_TOKEN_ISSUER`).

Validation against the NRCES profiles themselves also happens there.

**Record types.** All eight ABDM health-information types are produced, each as an NRCES
document from the encounter's record:

| Type | Content |
|------|---------|
| `OPConsultation`, `Prescription`, `DischargeSummary`, `ImmunizationRecord` | as described above |
| `DiagnosticReport` | lab reports with one Observation per reported value: LOINC code (seeded for every lab test and parameter), UCUM unit, reference range, interpretation (N, L, H, A; critical as LL or HH), effective at sample collection; imaging reports with their ImagingStudy |
| `WellnessRecord` | every vitals reading of the encounter, in Vital Signs, Body Measurement and General Assessment sections (NRCES `ObservationVitalSigns`, `ObservationBodyMeasurement`, `ObservationGeneralAssessment`) |
| `HealthDocumentRecord` | an inpatient stay's signed case notes (progress, procedure, nursing…) that no other record carries |
| `Invoice` | the encounter's final bills: one Invoice each, a ChargeItem per line (NRCES billing codes), price components rate, MRP, discount, CGST and SGST, and for medicines the batch and expiry sold |

### Milestone 3: records from other hospitals (HIU)

On the Doctor Desk's **Other hospitals** tab a doctor asks for the patient's consent:
which kinds of record, from when to when, and until when they may be kept here (at most
30 days, `SYNCRA_ABDM_HIU_MAX_RETENTION_DAYS`). The patient must have a verified ABHA
address. Then:

1. The request goes to ABDM (`consent/v3/request/init`) with the doctor's name and
   council registration; the patient approves or declines in their ABHA app.
2. For each hospital that holds records, ABDM sends a consent artefact; it is
   acknowledged and its details fetched (which hospital, record types, dates). The
   patient can shorten the keep-until date, never lengthen it.
3. Records are requested from that hospital with a one-time Curve25519 key (kept in
   memory only, never in the database or Redis, which persists to disk) and a one-time
   push URL under `SYNCRA_ABDM_PUBLIC_URL` (no URL configured: nothing is requested).
4. The hospital pushes the encrypted documents. Each is decrypted, its checksum verified
   and parsed as FHIR R4, then stored until the keep-until date. What could not be
   accepted is reported back to ABDM per care context (`health-information/notify`).
5. The doctor sees each request, the hospitals that answered, the transfer status, and
   the records, summarised for reading (a value with its unit and flag, a medicine with
   its dosage…) or downloaded as FHIR. Every read and download is audited.
6. If the patient withdraws consent, or the keep-until date passes, the records are
   deleted. A failed transfer can be asked for again.

**Also to confirm in the sandbox:** the HIU paths (`/api/v3/hiu/...` callbacks,
`consent/v3/fetch`), the `requester.identifier` system for the council registration, and
the `hiStatus` values an HIU reports (`OK` / `ERRORED` here).

**Not built yet:** ABHA creation by Aadhaar biometrics or demographic authentication
(OTP only for now).

## Tests

```bash
make test           # all of the below except test-db (about 15 minutes)
make test-gateway   # 17 tests: auth, audience/expiry/signature, CB fallback, CORS, request id, dictation roles, routes, sign-in, PACS viewer cookie, ABDM callbacks and data push
make test-patient   # 59 tests: domain rules, lab range flagging + API/EMR tests (diagnoses, notes, orders, server-side result flags, critical-result call log, vaccinations, PACS index, CDSS at signing) against a real Postgres 16
make test-iam       # 6 tests: password rules, sign-in, lockout, refresh rotation and reuse, administration
make test-pharmacy  # 5 tests: goods receipt rules, FEFO requisition and dispensing, never expired stock, Schedule X, write-off, H1 register
make test-billing   # 15 tests: bed-day census, rounding, amount in words; OPD/IPD/payment/print API tests; pharmacy GST invoice at MRP
make test-abdm      # 61 tests: Fidelius vs reference vectors, FHIR validation of all eight record types, coded lab values, ABHA OTP and Aadhaar enrolment, Scan & Share, HIP- and patient-initiated linking, consent, encrypted transfer, HIU consent and data push, callback auth
make test-ai        # 66 tests: extraction (incl. diagnoses, ICD-10), CDSS rules, pause segmentation, dictation protocol, health
make test-web       # 70 tests: Doctor Desk flows, orders/notes/allergies/vaccinations, records from other hospitals, lab and imaging desks, critical calls, front office, administration, pharmacy, sign-in; + production build
make test-db        # 49 schema checks as the app role (postgres must be running; rolled back)
```

## Notes

- **No schema migrations yet.** `database/init-schema.sql` runs only when the database
  volume is first created. After a schema change, a development database is either reset
  (`make down`, `docker volume rm syncra-hims_pgdata`, `make up`, `make seed-dev`,
  `make seed-demo`) or upgraded by hand with the equivalent `ALTER`s. Adopt Flyway (or
  similar) before go-live so a hospital's data is upgraded in place.
- **Spring Boot 3.2.12** is used as specified. The 3.2 line no longer gets
  open-source support. Moving to a current 3.x release is recommended before go-live.
- Redis 8 is licensed AGPLv3/RSALv2/SSPLv1. Running it unmodified as a separate
  server places no obligations on Syncra code. Valkey is a drop-in BSD alternative.
- `ORTHANC_STORAGE` can point to the imaging RAID (for example `/srv/syncra/dicom`)
  instead of a Docker volume. Restrict `ORTHANC_DICOM_BIND` to the imaging VLAN.
