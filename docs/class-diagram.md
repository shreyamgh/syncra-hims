# Syncra HIMS: class diagrams

The domain model of Syncra HIMS, taken from the database schema (`database/init-schema.sql`,
68 tables in 11 schemas) and the services that work on it. GitHub draws the diagrams below;
elsewhere, paste a block into [mermaid.live](https://mermaid.live).

**How to read them**

- One diagram per area; a class drawn in another area's colour appears only to show the link.
- Attributes are the important fields, not every column. Every entity also has an `id`
  (UUID) and `created_at` / `updated_at`; most record who created or changed them.
- `*--` is composition (the part cannot exist without the whole), `-->` an association,
  `..>` a soft reference (an id or code, not a foreign key). Numbers are multiplicities.
- Links to the member of staff who did something (ordered, signed, reported, dispensed…)
  are left out to keep the diagrams readable: there are 21 of them, all to `Staff`.

Contents:

1. [Overview](#1-overview)
2. [Patients, staff, OPD and wards](#2-patients-staff-opd-and-wards)
3. [Clinical record (EMR)](#3-clinical-record-emr)
4. [Orders, laboratory and imaging](#4-orders-laboratory-and-imaging)
5. [Billing](#5-billing)
6. [Pharmacy](#6-pharmacy)
7. [ABDM as health information provider (HIP)](#7-abdm-as-health-information-provider-hip)
8. [ABDM as health information user (HIU)](#8-abdm-as-health-information-user-hiu)
9. [Sign-in, staff and audit](#9-sign-in-staff-and-audit)
10. [Application services](#10-application-services): [Patient Service](#10a-patient-service), [Billing, Pharmacy and IAM](#10b-billing-pharmacy-and-iam-services), [ABDM](#10c-abdm-service)
11. [AI service](#11-ai-service)

## 1. Overview

The main classes of each area and how they meet. Everything clinical hangs off an
`Encounter`: an OPD visit, an inpatient admission, an emergency or day-care episode.

```mermaid
classDiagram
direction LR
class Patient {
  +String uhid
  +String fullName
  +String abhaAddress
}
class Encounter {
  +EncounterType encounterType
  +EncounterStatus status
}
class OpdVisit {
  +int tokenNumber
  +QueueStatus queueStatus
}
class Admission {
  +String admissionNumber
  +AdmissionStatus status
}
class ServiceOrder {
  +OrderType orderType
  +OrderStatus status
}
class Prescription {
  +PrescriptionStatus status
}
class Bill {
  +String billNumber
  +BillStatus status
}
class Dispense {
  +String dispenseNumber
}
class CareContext {
  +String referenceNumber
  +LinkStatus linkStatus
}
class HiuConsentRequest {
  +HiuRequestStatus status
}
class Staff {
  +String fullName
  +StaffRole staffRole
}
class UserAccount {
  +String username
  +List~Role~ roles
}

Patient "1" --> "0..*" Encounter
Encounter "1" -- "0..1" OpdVisit
Encounter "1" -- "0..1" Admission
Encounter "1" *-- "0..*" ServiceOrder
Encounter "1" *-- "0..*" Prescription
Encounter "1" --> "0..*" Bill
Prescription "1" --> "0..*" Dispense : dispensed as
Dispense "0..1" --> "0..1" Bill : invoiced on
Encounter "1" --> "0..1" CareContext : shared to ABHA as
Patient "1" --> "0..*" HiuConsentRequest : records asked from other hospitals
UserAccount "0..1" --> "0..1" Staff : signs in as
Encounter "0..*" --> "0..1" Staff : attending doctor
```

## 2. Patients, staff, OPD and wards

Schemas `core`, `patient`, `opd`, `ipd`. A patient keeps one UHID for life; a duplicate
record is never deleted but can point to the surviving one (`merged_into`). A bed holds at most one patient
at a time (`bed_assignment` with no `released_at`, enforced by the database).

```mermaid
classDiagram
direction LR
class Department {
  +String code
  +String name
  +boolean isClinical
  +boolean opdEnabled
}
class Staff {
  +String employeeCode
  +String title
  +String fullName
  +StaffRole staffRole
  +String qualification
  +String councilRegistration
  +String hprId
}
class Patient {
  +String uhid
  +String fullName
  +char gender
  +LocalDate dateOfBirth
  +boolean dobIsEstimated
  +String mobile
  +String addressLine
  +String pincode
  +String abhaNumber
  +String abhaAddress
  +Instant abhaVerifiedAt
}
class PatientIdentifier {
  +String idType
  +String idValue
  +String issuer
  +LocalDate validUntil
}
class Encounter {
  +EncounterType encounterType
  +EncounterStatus status
  +Instant startedAt
  +Instant endedAt
  +boolean isMlc
  +String mlcNumber
}
class OpdVisit {
  +LocalDate visitDate
  +int tokenNumber
  +VisitType visitType
  +Priority priority
  +QueueStatus queueStatus
  +Instant calledAt
  +Instant completedAt
}
class Ward {
  +String code
  +String name
  +String wardType
  +String genderRestriction
}
class Bed {
  +String bedCode
  +String bedCategory
  +BedStatus status
  +boolean hasOxygen
  +boolean hasMonitor
  +boolean hasVentilator
}
class Admission {
  +String admissionNumber
  +AdmissionType admissionType
  +Instant admittedAt
  +String provisionalDiagnosis
  +AdmissionStatus status
  +Instant dischargedAt
}
class BedAssignment {
  +Instant assignedAt
  +Instant releasedAt
  +String reason
}
class QueueStatus {
  <<enumeration>>
  WAITING
  CALLED
  IN_CONSULTATION
  ON_HOLD
  COMPLETED
  NO_SHOW
  CANCELLED
}
class Priority {
  <<enumeration>>
  NORMAL
  SENIOR_CITIZEN
  PREGNANT
  DIFFERENTLY_ABLED
  CHILD_UNDER_5
  EMERGENCY
}
class AdmissionStatus {
  <<enumeration>>
  ADMITTED
  DISCHARGE_INITIATED
  DISCHARGED
  LAMA
  ABSCONDED
  EXPIRED
  TRANSFERRED_OUT
  CANCELLED
}
class BedStatus {
  <<enumeration>>
  AVAILABLE
  RESERVED
  OCCUPIED
  CLEANING
  MAINTENANCE
  BLOCKED
}

Department "1" --> "0..*" Staff
Patient "1" *-- "0..*" PatientIdentifier
Patient "0..*" --> "0..1" Patient : merged into
Patient "1" --> "0..*" Encounter
Encounter "0..*" --> "1" Department
Encounter "0..*" --> "0..1" Staff : attending doctor
Encounter "1" -- "0..1" OpdVisit
OpdVisit "0..*" --> "0..1" OpdVisit : follow-up of
OpdVisit "0..*" --> "0..1" Staff : doctor
Encounter "1" -- "0..1" Admission
Admission "0..*" --> "1" Staff : admitting doctor
Department "1" --> "0..*" Ward
Ward "1" *-- "1..*" Bed
Admission "1" *-- "1..*" BedAssignment
BedAssignment "0..*" --> "1" Bed
OpdVisit ..> QueueStatus
OpdVisit ..> Priority
Admission ..> AdmissionStatus
Bed ..> BedStatus
```

## 3. Clinical record (EMR)

Schema `clinical`. Entries are never deleted: a wrong one is marked entered in error, a
signed note is corrected by an amendment, and an issued prescription cannot change
(database triggers). Entries filled by Shrutlekhan dictation point to their dictation session.

```mermaid
classDiagram
direction LR
class Patient {
  +String uhid
}
class Encounter {
  +EncounterType encounterType
  +EncounterStatus status
}
class DictationSession {
  +String aiSessionId
  +String language
  +String engine
  +decimal audioSeconds
  +String outcome
}
class VitalSign {
  +Instant recordedAt
  +decimal temperatureC
  +int pulseBpm
  +int respiratoryRate
  +int bpSystolic
  +int bpDiastolic
  +int spo2Percent
  +decimal weightKg
  +decimal heightCm
  +decimal bmi
  +int bloodGlucoseMgDl
  +int painScore
  +Source source
}
class Complaint {
  +String description
  +int durationValue
  +String durationUnit
  +Severity severity
  +String snomedCode
  +boolean isChief
}
class Diagnosis {
  +String icd10Code
  +String snomedCode
  +String description
  +String diagnosisType
  +boolean isPrimary
}
class ClinicalNote {
  +NoteType noteType
  +String content
  +Instant signedAt
  +boolean isEnteredInError
}
class Allergy {
  +AllergenType allergenType
  +String drugClass
  +String allergenText
  +String reaction
  +AllergySeverity severity
  +AllergyStatus status
}
class Prescription {
  +PrescriptionStatus status
  +Instant issuedAt
  +String advice
  +LocalDate followUpDate
}
class PrescriptionItem {
  +int lineNo
  +String drugText
  +String dose
  +String route
  +String frequency
  +String timing
  +int durationDays
  +decimal quantity
  +String instructions
}
class CdssAlert {
  +String alertType
  +CdssSeverity severity
  +String message
  +String ruleCode
  +String kbVersion
  +String resolution
  +String overrideReason
}
class Vaccine {
  +String code
  +String name
  +String targetDisease
  +int seriesDoses
  +String programme
}
class Immunization {
  +ImmunizationStatus status
  +String notDoneReason
  +int doseNumber
  +Instant administeredAt
  +boolean primarySource
  +String lotNumber
  +LocalDate expiryDate
  +String route
  +String site
  +LocalDate nextDueDate
}
class Drug {
  +String code
  +String brandName
}
class Generic {
  +String name
  +String atcCode
}
class NoteType {
  <<enumeration>>
  HISTORY
  EXAMINATION
  PROGRESS
  PROCEDURE
  NURSING
  ADVICE
  DISCHARGE_SUMMARY
  RADIOLOGY_REPORT
  OTHER
}
class PrescriptionStatus {
  <<enumeration>>
  DRAFT
  ISSUED
  CANCELLED
}
class CdssSeverity {
  <<enumeration>>
  CONTRAINDICATED
  MAJOR
  MODERATE
  MINOR
}

Patient "1" --> "0..*" Encounter
Patient "1" *-- "0..*" Allergy
Encounter "1" *-- "0..*" VitalSign
Encounter "1" *-- "0..*" Complaint
Encounter "1" *-- "0..*" Diagnosis
Encounter "1" *-- "0..*" ClinicalNote
Encounter "1" *-- "0..*" Prescription
Encounter "1" *-- "0..*" Immunization
Encounter "1" *-- "0..*" CdssAlert
Encounter "1" *-- "0..*" DictationSession
ClinicalNote "0..*" --> "0..1" ClinicalNote : amends
Prescription "1" *-- "1..*" PrescriptionItem
PrescriptionItem "0..*" --> "0..1" Drug : formulary item
Allergy "0..*" --> "0..1" Generic
CdssAlert "0..*" --> "0..1" Prescription
CdssAlert "0..*" --> "0..1" Allergy
Immunization "0..*" --> "1" Vaccine
VitalSign "0..*" --> "0..1" DictationSession : filled by
Complaint "0..*" --> "0..1" DictationSession : filled by
Diagnosis "0..*" --> "0..1" DictationSession : filled by
PrescriptionItem "0..*" --> "0..1" DictationSession : filled by
ClinicalNote "0..*" --> "0..1" DictationSession : filled by
ClinicalNote ..> NoteType
Prescription ..> PrescriptionStatus
CdssAlert ..> CdssSeverity
```

## 4. Orders, laboratory and imaging

Schemas `clinical` and `imaging`. The service catalogue (`billing.service`) is shared:
the same entry is ordered, worked on by the lab or imaging, and billed. The server flags
each lab value from the test's parameters; a critical value opens a `CriticalResult` that
must be phoned through (with read-back) and acknowledged by the doctor.

```mermaid
classDiagram
direction LR
class Encounter {
  +EncounterType encounterType
}
class Service {
  +String code
  +String name
  +ServiceCategory category
  +String dicomModality
  +String loincCode
}
class LabAnalyte {
  +String code
  +String name
  +String unit
  +int decimals
  +decimal refLow
  +decimal refHigh
  +decimal refLowFemale
  +decimal refHighFemale
  +decimal criticalLow
  +decimal criticalHigh
  +String loincCode
}
class ServiceOrder {
  +OrderType orderType
  +OrderPriority priority
  +OrderStatus status
  +String clinicalIndication
  +String accessionNumber
  +Instant orderedAt
  +Instant sampleCollectedAt
  +Instant completedAt
  +String resultSummary
}
class ResultValue {
  <<value object>>
  +String code
  +String name
  +decimal value
  +String text
  +String unit
  +String referenceRange
  +Flag flag
}
class CriticalResult {
  +List~ResultValue~ criticalValues
  +Instant reportedAt
  +String communicatedTo
  +String communicationMethod
  +boolean readBack
  +Instant communicatedAt
  +Instant acknowledgedAt
  +String acknowledgementNote
}
class ImagingStudy {
  +String studyInstanceUid
  +String accessionNumber
  +String orthancStudyId
  +String modality
  +LocalDate studyDate
  +int seriesCount
  +int instanceCount
  +ReportStatus reportStatus
  +Instant reportedAt
}
class ClinicalNote {
  +NoteType noteType
  +String content
}
class Patient {
  +String uhid
}
class OrderStatus {
  <<enumeration>>
  ORDERED
  SCHEDULED
  SAMPLE_COLLECTED
  IN_PROGRESS
  COMPLETED
  REPORTED
  CANCELLED
}
class Flag {
  <<enumeration>>
  N
  L
  H
  A
  C
}
class ReportStatus {
  <<enumeration>>
  UNREPORTED
  DRAFT
  FINAL
  ADDENDUM
}

Encounter "1" *-- "0..*" ServiceOrder
ServiceOrder "0..*" --> "1" Service
Service "1" *-- "0..*" LabAnalyte : parameters
ServiceOrder "1" *-- "0..*" ResultValue : result_values
ResultValue ..> LabAnalyte : by code
ServiceOrder "1" -- "0..1" CriticalResult
CriticalResult "0..*" --> "1" Patient
ImagingStudy "0..*" --> "0..1" ServiceOrder : matched by accession
ImagingStudy "0..*" --> "1" Patient
ImagingStudy "0..1" --> "0..1" ClinicalNote : signed report
ServiceOrder ..> OrderStatus
ResultValue ..> Flag
ImagingStudy ..> ReportStatus
```

## 5. Billing

Schema `billing`. A bill belongs to one encounter (and, for inpatients, the admission).
Lines are priced from the patient's tariff plan; each line remembers what it came from
(`source_type` + `source_id`: an OPD visit, a bed day, an order, a doctor's visit or a
dispensed medicine). A bill gets its gap-free GST number only when it becomes final, and a
final bill can only be cancelled. Advances are paid against the admission.

```mermaid
classDiagram
direction LR
class Encounter {
  +EncounterType encounterType
}
class Admission {
  +String admissionNumber
}
class Service {
  +String code
  +String name
  +ServiceCategory category
  +String sacCode
  +String chargeUnit
}
class TariffPlan {
  +String code
  +String name
  +String payerType
  +boolean isDefault
}
class TariffRate {
  +String bedCategory
  +decimal amount
  +decimal gstRate
  +LocalDate validFrom
  +LocalDate validTo
}
class Bill {
  +String billNumber
  +BillType billType
  +BillStatus status
  +decimal grossAmount
  +decimal discountAmount
  +decimal taxAmount
  +decimal netAmount
  +decimal roundOff
  +decimal payableAmount
  +Instant finalizedAt
  +String cancellationReason
}
class BillItem {
  +String description
  +LocalDate serviceDate
  +decimal quantity
  +decimal unitPrice
  +decimal discountAmount
  +decimal gstRate
  +decimal taxableAmount
  +decimal taxAmount
  +decimal lineTotal
  +SourceType sourceType
  +UUID sourceId
}
class Payment {
  +String receiptNumber
  +PaymentType paymentType
  +PaymentMode paymentMode
  +decimal amount
  +String referenceNumber
  +Instant receivedAt
  +PaymentStatus status
}
class DispenseLine {
  +decimal quantity
  +decimal mrp
}
class BillStatus {
  <<enumeration>>
  DRAFT
  INTERIM
  FINAL
  CANCELLED
}
class BillType {
  <<enumeration>>
  OPD
  IPD
  PHARMACY
  EMERGENCY
}
class PaymentMode {
  <<enumeration>>
  CASH
  CARD
  UPI
  NET_BANKING
  CHEQUE
  DEMAND_DRAFT
  INSURANCE
  GOVT_SCHEME
  CORPORATE_CREDIT
}

TariffPlan "1" *-- "0..*" TariffRate
TariffRate "0..*" --> "1" Service
Encounter "1" --> "0..*" Bill
Admission "0..1" <-- "0..*" Bill
Admission "0..*" --> "1" TariffPlan
Bill "0..*" --> "1" TariffPlan
Bill "1" *-- "1..*" BillItem
BillItem "0..*" --> "1" Service
BillItem "0..*" --> "0..1" TariffRate
BillItem ..> DispenseLine : source (pharmacy)
Bill "0..1" <-- "0..*" Payment : settlement or refund
Admission "0..1" <-- "0..*" Payment : advance
Bill ..> BillStatus
Bill ..> BillType
Payment ..> PaymentMode
```

## 6. Pharmacy

Schema `pharmacy`. Stock is held per store and batch; every change is written to the
append-only `StockMovement` ledger. Goods arrive in the main store, move to the OPD
pharmacy by requisition, and leave by dispensing, always earliest expiry first and never
from an expired batch. A dispense is invoiced once, as a pharmacy bill.

```mermaid
classDiagram
direction LR
class Generic {
  +String name
  +String atcCode
  +String drugClass
}
class Drug {
  +String code
  +String brandName
  +String strength
  +String dosageForm
  +Schedule schedule
  +boolean isHighAlert
  +boolean isNlem
  +String hsnCode
  +decimal gstRate
  +String dispensingUnit
  +int reorderLevel
}
class DrugComponent {
  +decimal strengthValue
  +String strengthUnit
}
class Batch {
  +String batchNumber
  +LocalDate expiryDate
  +decimal mrp
  +decimal purchaseRate
}
class Store {
  +String code
  +String name
  +StoreKind kind
}
class Stock {
  +decimal quantity
}
class StockMovement {
  +MovementKind kind
  +decimal quantity
  +UUID referenceId
  +String reason
  +Instant occurredAt
}
class GoodsReceipt {
  +String receiptNumber
  +String supplier
  +String invoiceNumber
  +LocalDate invoiceDate
}
class GoodsReceiptLine {
  +decimal quantity
  +decimal freeQuantity
}
class Requisition {
  +String requisitionNumber
  +RequisitionStatus status
  +Instant requestedAt
  +Instant issuedAt
}
class RequisitionLine {
  +decimal quantityRequested
  +decimal quantityIssued
}
class Dispense {
  +String dispenseNumber
  +Instant dispensedAt
}
class DispenseLine {
  +decimal quantity
  +decimal mrp
  +decimal gstRate
  +Schedule schedule
}
class Prescription {
  +PrescriptionStatus status
}
class PrescriptionItem {
  +String drugText
}
class Bill {
  +String billNumber
}
class Schedule {
  <<enumeration>>
  OTC
  G
  H
  H1
  X
}
class MovementKind {
  <<enumeration>>
  RECEIPT
  TRANSFER_OUT
  TRANSFER_IN
  DISPENSE
  RETURN
  WRITE_OFF
}

Drug "1" *-- "1..*" DrugComponent
DrugComponent "0..*" --> "1" Generic
Drug "1" *-- "0..*" Batch
Store "1" *-- "0..*" Stock
Stock "0..*" --> "1" Batch
StockMovement "0..*" --> "1" Store
StockMovement "0..*" --> "1" Batch
GoodsReceipt "0..*" --> "1" Store
GoodsReceipt "1" *-- "1..*" GoodsReceiptLine
GoodsReceiptLine "0..*" --> "1" Batch
Requisition "0..*" --> "1" Store : requesting
Requisition "0..*" --> "1" Store : issuing
Requisition "1" *-- "1..*" RequisitionLine
RequisitionLine "0..*" --> "1" Drug
Prescription "1" --> "0..*" Dispense
Dispense "0..*" --> "1" Store
Dispense "1" *-- "1..*" DispenseLine
DispenseLine "0..*" --> "1" Batch
DispenseLine "0..*" --> "1" Drug
DispenseLine "0..*" --> "0..1" PrescriptionItem
Dispense "0..*" --> "0..1" Bill : invoiced on
Drug ..> Schedule
StockMovement ..> MovementKind
```

## 7. ABDM as health information provider (HIP)

Schema `abdm`. ABHA verification and creation (Milestone 1), then each encounter becomes a
care context linked to the patient's ABHA, by the hospital or by the patient from their
app (Milestone 2). Under a consent artefact the records are generated from the EMR as FHIR
documents, encrypted and pushed; only each document's hash is kept. Aadhaar numbers are
never stored.

```mermaid
classDiagram
direction LR
class Patient {
  +String uhid
  +String abhaNumber
  +String abhaAddress
}
class Encounter {
  +EncounterType encounterType
}
class AbhaVerification {
  +String abhaNumber
  +String abhaAddress
  +String method
  +VerificationOutcome outcome
  +Instant performedAt
}
class AbhaEnrolment {
  +String abhaNumber
  +String abhaAddress
  +EnrolmentOutcome outcome
  +boolean mobileVerified
  +String consentVersion
}
class PatientShare {
  +LocalDate shareDate
  +int tokenNumber
  +String abhaAddress
  +String name
  +ShareStatus status
  +Instant expiresAt
}
class CareContext {
  +String referenceNumber
  +String display
  +List~HiType~ hiTypes
  +LinkStatus linkStatus
  +String linkedAbhaAddress
  +Instant linkedAt
}
class DiscoveryRequest {
  +UUID transactionId
  +String abhaAddress
  +List~String~ matchedBy
  +DiscoveryOutcome outcome
  +Instant expiresAt
}
class LinkSession {
  +String linkReference
  +String otpHash
  +int attempts
  +LinkSessionStatus status
  +Instant expiresAt
}
class ConsentArtefact {
  +UUID consentArtefactId
  +String hiuId
  +String requesterName
  +String purposeCode
  +List~HiType~ hiTypes
  +String accessMode
  +Instant dateRangeFrom
  +Instant dateRangeTo
  +Instant dataEraseAt
  +ConsentStatus status
}
class HealthInformationRequest {
  +UUID transactionId
  +Instant dateRangeFrom
  +Instant dateRangeTo
  +String dataPushUrl
  +String hiuPublicKey
  +String hiuNonce
  +HiRequestStatus status
}
class HealthInformationTransfer {
  +HiType hiType
  +String bundleSha256
  +int bundleSizeBytes
  +TransferStatus status
  +Instant sentAt
}
class GatewayMessage {
  +UUID requestId
  +String direction
  +String apiPath
  +UUID transactionId
  +int httpStatus
  +String outcome
}
class HiType {
  <<enumeration>>
  OPConsultation
  Prescription
  DischargeSummary
  DiagnosticReport
  ImmunizationRecord
  HealthDocumentRecord
  WellnessRecord
  Invoice
}

Patient "1" --> "0..*" AbhaVerification
AbhaEnrolment "0..*" --> "0..1" AbhaVerification
PatientShare "0..*" --> "0..1" Patient : matched or registered
Patient "1" --> "0..*" Encounter
Encounter "1" -- "0..1" CareContext
Patient "1" --> "0..*" CareContext
DiscoveryRequest "0..*" --> "0..1" Patient
DiscoveryRequest "1" --> "0..*" LinkSession
LinkSession ..> CareContext : links
ConsentArtefact "0..*" --> "0..1" Patient
ConsentArtefact "0..*" --> "0..*" CareContext : covers
ConsentArtefact "1" --> "0..*" HealthInformationRequest
HealthInformationRequest "1" *-- "0..*" HealthInformationTransfer
HealthInformationTransfer "0..*" --> "1" CareContext
CareContext ..> HiType
```

`GatewayMessage` logs every call to and from ABDM (path and status, never the body); a
callback seen before by its `REQUEST-ID` is not processed twice.

## 8. ABDM as health information user (HIU)

Milestone 3: this hospital asking for a patient's records held elsewhere. One consent
request gives one artefact per hospital that holds records; each artefact gets data
requests, and each received document is stored until the consent's erase time. The
one-time decryption key of a data request lives only in the service's memory.

```mermaid
classDiagram
direction LR
class Patient {
  +String uhid
  +String abhaAddress
}
class Staff {
  +String fullName
  +String councilRegistration
}
class HiuConsentRequest {
  +UUID gatewayRequestId
  +UUID consentRequestId
  +String abhaAddress
  +String purposeCode
  +List~HiType~ hiTypes
  +Instant dateRangeFrom
  +Instant dateRangeTo
  +Instant dataEraseAt
  +HiuRequestStatus status
  +String errorMessage
}
class HiuConsentArtefact {
  +UUID consentId
  +ConsentStatus status
  +String hipId
  +String hipName
  +List~CareContextRef~ careContexts
  +List~HiType~ hiTypes
  +Instant dataEraseAt
  +Instant fetchedAt
}
class HiuDataRequest {
  +UUID gatewayRequestId
  +UUID transactionId
  +String pushTokenSha256
  +DataRequestStatus status
  +int pageCount
  +int pagesReceived
  +Map failures
  +Instant completedAt
}
class HiuHealthRecord {
  +String careContextReference
  +int pageNumber
  +String recordType
  +String title
  +Instant recordDate
  +FhirBundle bundle
  +String bundleSha256
  +Instant dataEraseAt
}
class HiuRequestStatus {
  <<enumeration>>
  REQUESTED
  GRANTED
  DENIED
  EXPIRED
  REVOKED
  FAILED
}
class DataRequestStatus {
  <<enumeration>>
  REQUESTED
  ACKNOWLEDGED
  TRANSFERRED
  PARTIAL
  FAILED
}

Patient "1" --> "0..*" HiuConsentRequest
HiuConsentRequest "0..*" --> "1" Staff : requested by
HiuConsentRequest "1" *-- "0..*" HiuConsentArtefact : one per hospital
HiuConsentArtefact "1" *-- "0..*" HiuDataRequest
HiuDataRequest "1" *-- "0..*" HiuHealthRecord
HiuHealthRecord "0..*" --> "1" Patient
HiuConsentRequest ..> HiuRequestStatus
HiuDataRequest ..> DataRequestStatus
```

## 9. Sign-in, staff and audit

Schemas `iam` and `audit`. A user account signs in as a staff member (services find the
caller's staff record by the JWT subject, `core.staff.iam_subject`). Refresh tokens are
stored as hashes and chained: each use replaces the token, and reuse of a replaced one ends
the whole family. Sign-in events and the clinical audit log are append-only.

```mermaid
classDiagram
direction LR
class UserAccount {
  +String username
  +String passwordHash
  +String displayName
  +List~Role~ roles
  +AccountStatus status
  +int failedAttempts
  +Instant lockedUntil
  +boolean mustChangePassword
  +Instant lastLoginAt
}
class RefreshToken {
  +String tokenHash
  +UUID familyId
  +Instant expiresAt
  +Instant revokedAt
}
class AuthEvent {
  <<append-only>>
  +Instant occurredAt
  +String username
  +String event
  +String clientIp
}
class Staff {
  +String employeeCode
  +String fullName
  +StaffRole staffRole
  +String iamSubject
}
class Department {
  +String code
  +String name
}
class AuditEvent {
  <<append-only>>
  +Instant occurredAt
  +String actor
  +String actorRole
  +AuditAction action
  +String entityType
  +String entityId
  +UUID patientId
  +String service
  +Map details
}
class StaffRole {
  <<enumeration>>
  DOCTOR
  NURSE
  RECEPTIONIST
  BILLING_CLERK
  PHARMACIST
  LAB_TECHNICIAN
  RADIOGRAPHER
  RADIOLOGIST
  MRD_OFFICER
  ADMINISTRATOR
}

UserAccount "1" *-- "0..*" RefreshToken
RefreshToken "0..1" --> "0..1" RefreshToken : replaced by
UserAccount "0..1" --> "0..1" Staff : signs in as
Staff "0..*" --> "0..1" Department
AuthEvent ..> UserAccount : by username
AuditEvent ..> Staff : actor
Staff ..> StaffRole
UserAccount ..> StaffRole : roles
```

## 10. Application services

The Java services (Spring Boot 3.2, Java 21) and their main operations. Each service is
called through its REST controller behind the API gateway; controllers, repositories and
request/response records are left out. Dashed arrows are calls from one class to another;
the stereotype names the deployable service.

### 10a. Patient Service

Registration, OPD queue, wards and the clinical record. Signing a consultation re-runs the
drug-safety check in the AI service over HTTP.

```mermaid
classDiagram
direction LR
class PatientService {
  <<patient-service>>
  +register(Demographics, RegistrationSource, boolean) Patient
  +get(Uhid) Patient
  +update(Uhid, long, Demographics) Patient
  +search(String, int, int) List~PatientSummary~
}
class OpdService {
  <<patient-service>>
  +register(RegisterVisitRequest) VisitResponse
  +queue(String, LocalDate, UUID) QueueResponse
  +callNext(String) VisitResponse
  +transition(UUID, QueueStatus) VisitResponse
}
class AdmissionService {
  <<patient-service>>
  +admit(AdmitRequest) AdmissionResponse
  +moveBed(UUID, BedMoveRequest) AdmissionResponse
  +initiateDischarge(UUID) AdmissionResponse
  +discharge(UUID, DischargeRequest) AdmissionResponse
}
class BedService {
  <<patient-service>>
  +board(String, BedStatus) List~BoardEntry~
  +census() List~WardCensus~
  +available(Gender, BedCategory) List~AvailableBed~
}
class EmrService {
  <<patient-service>>
  +record(UUID) EncounterRecord
  +recordVitals(UUID, VitalsInput) EncounterRecord
  +recordConsultation(UUID, ConsultationRequest) EncounterRecord
}
class EntriesService {
  <<patient-service>>
  +addDiagnosis(UUID, DiagnosisInput) Diagnosis
  +addAllergy(String, AllergyInput) Allergy
  +addNote(UUID, NoteInput) Note
  +sign(UUID, UUID) Note
}
class OrderService {
  <<patient-service>>
  +place(UUID, String, String, String) Order
  +sampleCollected(UUID, Instant) Order
  +report(UUID, String, List~ResultValue~) Order
  +cancel(UUID, String) Order
  +worklist(String, List~String~, Instant) List~Order~
}
class CriticalResultService {
  <<patient-service>>
  +openIfCritical(UUID, UUID, List~ResultValue~, UUID)
  +communicate(UUID, String, String, boolean) CriticalResult
  +acknowledge(UUID, String) CriticalResult
}
class ImagingService {
  <<patient-service>>
  +worklist(List~String~, Boolean, Instant) List~Study~
  +match(UUID, UUID) Study
  +report(UUID, String, String) Study
}
class ImmunizationService {
  <<patient-service>>
  +record(UUID, ImmunizationInput) Immunization
  +markEnteredInError(UUID, UUID, String) Immunization
  +history(String) Map.Entry
}
class CdssGuard {
  <<patient-service>>
  +check(...) Outcome
}
class CdssEngine {
  <<ai-service>>
  +check(CheckRequest) CheckResponse
}
OpdService ..> PatientService
AdmissionService ..> PatientService
EmrService ..> OpdService : completes the visit
EmrService ..> CdssGuard
CdssGuard ..> CdssEngine : HTTP
OrderService ..> CriticalResultService
```

### 10b. Billing, Pharmacy and IAM services

```mermaid
classDiagram
direction LR
class OpdBillingService {
  <<billing-service>>
  +issueSlip(OpdSlipRequest) BillView
}
class IpdBillingService {
  <<billing-service>>
  +current(UUID) BillView
  +addLine(UUID, AddLineRequest) BillView
  +finalizeBill(UUID) BillView
}
class PharmacyBillingService {
  <<billing-service>>
  +sell(UUID, PaymentInput) BillView
}
class PaymentService {
  <<billing-service>>
  +settle(UUID, SettlementType, PaymentInput) BillView
  +advance(UUID, PaymentInput) Payment
  +voidPayment(UUID, String) Payment
}
class TariffService {
  <<billing-service>>
  +requireRate(UUID, String, String, LocalDate) Rate
  +rateCard(UUID, String, LocalDate) List~Rate~
}
class PharmacyService {
  <<pharmacy-service>>
  +drugs(String) List~DrugStock~
  +receive(String, String, LocalDate, List~ReceiptLine~) Created
  +requisition(String, String, Map) Requisition
  +issue(UUID) Requisition
  +pending() List~PendingPrescription~
  +dispense(UUID, List~DispenseInput~) Dispense
  +writeOff(String, UUID, BigDecimal, String)
}
class AuthService {
  <<iam-service>>
  +login(String, String, String) Session
  +refresh(String, String) Session
  +logout(String, String)
  +changePassword(String, String, String, String)
}
class AdminService {
  <<iam-service>>
  +create(String, String, UUID, List~String~) Created
  +resetPassword(UUID) Created
  +setStatus(UUID, boolean) AccountView
  +setRoles(UUID, List~String~) AccountView
}
IpdBillingService ..> TariffService
OpdBillingService ..> TariffService
PharmacyBillingService ..> PaymentService
OpdBillingService ..> PaymentService
```

The pharmacy dispenses; the billing service turns a dispense into a GST invoice at MRP
(`PharmacyBillingService.sell`). IAM issues the JWT every other service checks.

### 10c. ABDM service

```mermaid
classDiagram
direction LR
class AbhaService {
  <<abdm-service>>
  +requestOtp(AbhaLoginMethod, String) OtpRequested
  +verify(...) Verified
  +link(UUID, UUID) LinkedAbha
}
class AbhaEnrolmentService {
  <<abdm-service>>
  +start(String, boolean) Started
  +create(UUID, String, String, UUID) Created
  +createAddress(...) AddressCreated
}
class CareContextService {
  <<abdm-service>>
  +link(UUID) CareContext
  +onLinkToken(JsonNode)
  +onLinkResult(JsonNode)
}
class ConsentService {
  <<abdm-service>>
  +onNotify(JsonNode, UUID)
}
class HealthInformationService {
  <<abdm-service>>
  +onRequest(JsonNode, UUID)
}
class DataTransferWorker {
  <<abdm-service>>
  +process(UUID)
  +sweep()
}
class HealthRecordBuilder {
  <<abdm-service>>
  +available(EncounterRecord) Set~HiType~
  +build(HiType, EncounterRecord) Bundle
}
class HiuService {
  <<abdm-service>>
  +requestConsent(String, NewConsentRequest) ConsentRequest
  +onConsentNotify(JsonNode, UUID)
  +onConsentFetch(JsonNode)
  +receive(String, JsonNode)
  +records(String) List~RecordView~
}
class Fidelius {
  <<abdm-service>>
  +generateKeyMaterial()$ KeyMaterial
  +encrypt(...)$ String
  +decrypt(...)$ String
}
DataTransferWorker ..> HealthRecordBuilder
DataTransferWorker ..> Fidelius
HealthInformationService ..> DataTransferWorker
HiuService ..> Fidelius
```

The services share one PostgreSQL database, each through its own schema, and read others'
tables where a flow crosses areas (for example, billing reads the pharmacy dispense it
invoices, and the ABDM service reads the clinical record to build FHIR documents).

## 11. AI service

The Python service (FastAPI) behind the dictation WebSocket and the drug-safety check.

```mermaid
classDiagram
direction LR
class DictationSession {
  +run()
}
class StreamingTranscriber {
  <<interface>>
  +accept_audio(bytes) str
  +finalize() str
  +close()
}
class SegmentingTranscriber {
  +transcript() str
  +accept_audio(bytes) str
  +finalize() str
}
class NullTranscriber
class SpeechEngine {
  <<interface>>
  +status() EngineStatus
}
class ClinicalExtraction {
  +Vitals vitals
  +List~Complaint~ complaints
  +List~Diagnosis~ diagnoses
  +List~PrescriptionLine~ prescriptions
  +List~String~ warnings
}
class Engine {
  +check(CheckRequest) CheckResponse
}
class KnowledgeBase {
  +String version
  +boolean reviewed
  +List~Generic~ generics
  +Map brands
  +List~Interaction~ interactions
  +List~DuplicateClass~ duplicate_classes
  +List~AllergyGroup~ allergy_groups
  +List~DoseLimit~ doses
  +List~AgeCaution~ geriatric
  +List~RenalRule~ renal
  +generic(String) Generic
}
class Resolver {
  +generics_in(String) List~String~
  +ingredients(String) List~Ingredient~
}
class CheckRequest {
  +List~DrugLine~ prescriptions
  +List~Allergy~ allergies
  +Patient patient
}
class CheckResponse {
  +String kb_version
  +boolean kb_reviewed
  +List~Alert~ alerts
  +List recognised
  +float creatinine_clearance_ml_min
  +List~String~ not_checked
}
class Alert {
  +AlertType alert_type
  +Severity severity
  +String rule_code
  +String message
  +String management
  +List~int~ lines
}

StreamingTranscriber <|.. SegmentingTranscriber
StreamingTranscriber <|.. NullTranscriber
DictationSession --> StreamingTranscriber
SegmentingTranscriber --> SpeechEngine : Whisper
DictationSession ..> ClinicalExtraction : extracts
Engine --> KnowledgeBase
Engine --> Resolver
Engine ..> CheckRequest
Engine ..> CheckResponse
CheckResponse "1" *-- "0..*" Alert
```
