// Shapes returned by the Patient Service (camelCase JSON) via the gateway.

export type QueueStatus = 'WAITING' | 'CALLED' | 'IN_CONSULTATION' | 'ON_HOLD' | 'COMPLETED' | 'NO_SHOW' | 'CANCELLED';
export type Priority = 'EMERGENCY' | 'SENIOR_CITIZEN' | 'PREGNANT' | 'DIFFERENTLY_ABLED' | 'CHILD_UNDER_5' | 'NORMAL';
export type Gender = 'M' | 'F' | 'O' | 'U';

export interface Department {
  code: string;
  name: string;
}

export interface Visit {
  visitId: string;
  encounterId: string;
  tokenNumber: number;
  visitDate: string;
  departmentCode: string;
  departmentName: string;
  doctor?: { id: string; name: string };
  uhid: string;
  patientName: string;
  gender: Gender;
  ageYears?: number;
  visitType: string;
  priority: Priority;
  queueStatus: QueueStatus;
  registeredAt: string;
  calledAt?: string;
  consultationStartedAt?: string;
  completedAt?: string;
}

export interface Queue {
  departmentCode: string;
  departmentName: string;
  date: string;
  counts: Partial<Record<QueueStatus, number>>;
  visits: Visit[];
}

export interface VitalsRecord {
  id: string;
  recordedAt: string;
  recordedBy: string;
  temperatureC?: number;
  pulseBpm?: number;
  respiratoryRate?: number;
  bpSystolic?: number;
  bpDiastolic?: number;
  spo2Percent?: number;
  weightKg?: number;
  heightCm?: number;
  bmi?: number;
  bloodGlucoseMgDl?: number;
  source: string;
}

export interface ComplaintRecord {
  id: string;
  description: string;
  durationValue?: number;
  durationUnit?: string;
  chief: boolean;
  source: string;
}

export interface PrescriptionItemRecord {
  lineNo: number;
  drugText: string;
  dose?: string;
  route?: string;
  frequency?: string;
  timing?: string;
  durationDays?: number;
  instructions?: string;
  source: string;
}

export interface PrescriptionRecord {
  id: string;
  status: string;
  issuedAt?: string;
  prescribedBy: string;
  prescriberRegistration?: string;
  advice?: string;
  followUpDate?: string;
  items: PrescriptionItemRecord[];
}

export interface EncounterRecord {
  encounterId: string;
  encounterType: string;
  status: string;
  patient: { id: string; uhid: string; name: string; gender: Gender; ageYears?: number };
  departmentCode: string;
  departmentName: string;
  visit?: { visitId: string; tokenNumber: number; queueStatus: QueueStatus };
  latestVitals?: VitalsRecord;
  vitalsHistory: VitalsRecord[];
  complaints: ComplaintRecord[];
  prescriptions: PrescriptionRecord[];
  allergies: { allergen: string; reaction?: string; severity?: string; drugClass?: string }[];
  diagnoses?: DiagnosisRecord[];
  notes?: NoteRecord[];
  orders?: OrderRecord[];
  cdssAlerts?: RecordedCdssAlert[];
  /** Latest structured serum creatinine (last 90 days), for the renal guard. */
  latestCreatinine?: { mgPerDl: number; at: string };
  criticalResults?: CriticalResult[];
}

export interface DiagnosisRecord {
  id: string;
  description: string;
  icd10Code?: string;
  diagnosisType: 'PROVISIONAL' | 'DIFFERENTIAL' | 'FINAL';
  primary: boolean;
  enteredInError: boolean;
}

export interface RecordedCdssAlert {
  alertType: string;
  severity: CdssSeverity;
  ruleCode: string;
  message: string;
  resolution: 'OVERRIDDEN' | 'ACKNOWLEDGED' | 'ACCEPTED' | 'PENDING';
  overrideReason?: string;
}

// ---- Clinical notes

export type NoteType =
  | 'HISTORY' | 'EXAMINATION' | 'PROGRESS' | 'PROCEDURE' | 'NURSING' | 'ADVICE' | 'DISCHARGE_SUMMARY'
  | 'RADIOLOGY_REPORT' | 'OTHER';

export interface NoteRecord {
  id: string;
  encounterId: string;
  noteType: NoteType;
  content: string;
  authorId: string;
  author: string;
  signedAt?: string;
  amendsNoteId?: string;
  enteredInError: boolean;
  /** Optimistic-lock version, sent as If-Match when a draft is edited. */
  version: number;
  createdAt: string;
}

// ---- Investigation orders (CPOE)

export type OrderType = 'LABORATORY' | 'RADIOLOGY' | 'PROCEDURE';
export type OrderPriority = 'ROUTINE' | 'URGENT' | 'STAT';
export type OrderStatus =
  | 'ORDERED' | 'SCHEDULED' | 'SAMPLE_COLLECTED' | 'IN_PROGRESS' | 'COMPLETED' | 'REPORTED' | 'CANCELLED';
/** H high, L low, N normal, A abnormal, C critical. */
export type ResultFlag = 'H' | 'L' | 'N' | 'A' | 'C';

export interface ResultValue {
  code: string;
  name: string;
  value?: number;
  text?: string;
  unit?: string;
  referenceRange?: string;
  flag?: ResultFlag;
}

export interface OrderRecord {
  id: string;
  encounterId: string;
  /** Present on worklist rows (the patient the order is for). */
  patientId?: string;
  uhid?: string;
  patientName?: string;
  serviceCode: string;
  serviceName: string;
  orderType: OrderType;
  status: OrderStatus;
  priority: OrderPriority;
  clinicalIndication?: string;
  accessionNumber?: string;
  orderedBy: string;
  orderedAt: string;
  sampleCollectedAt?: string;
  completedAt?: string;
  resultSummary?: string;
  resultValues?: ResultValue[];
  reportedBy?: string;
  cancelledReason?: string;
  /** The PACS study of a radiology order, once its images have arrived. */
  studyInstanceUid?: string;
}

/** One parameter of a lab test with this patient's reference interval (absent when none applies). */
export interface AnalyteTemplate {
  code: string;
  name: string;
  unit?: string;
  decimals: number;
  low?: number;
  high?: number;
  criticalLow?: number;
  criticalHigh?: number;
  referenceRange?: string;
}

export interface ResultTemplate {
  sex: Gender;
  ageYears?: number;
  /** False for children and unknown ages: flags are then entered by hand. */
  rangesApply: boolean;
  note?: string;
  analytes: AnalyteTemplate[];
}

export interface NewResultValue {
  code: string;
  name: string;
  value?: number;
  text?: string;
  unit?: string;
  referenceRange?: string;
  flag?: ResultFlag;
}

export type ReportStatus = 'UNREPORTED' | 'DRAFT' | 'FINAL' | 'ADDENDUM';

/** A study indexed from the PACS, with its order and report. */
export interface ImagingStudy {
  id: string;
  patientId: string;
  uhid: string;
  patientName: string;
  orderId?: string;
  serviceName?: string;
  encounterId?: string;
  studyInstanceUid: string;
  accessionNumber?: string;
  modality?: string;
  studyDate?: string;
  description?: string;
  seriesCount?: number;
  instanceCount?: number;
  reportStatus: ReportStatus;
  reportNoteId?: string;
  reportedBy?: string;
  reportedAt?: string;
  receivedAt: string;
  clinicalIndication?: string;
  priority?: OrderPriority;
  orderedBy?: string;
  /** The current report text (the latest addendum when there is one). */
  reportContent?: string;
  dicomWebPath: string;
}

export interface Orderable {
  serviceId: string;
  code: string;
  name: string;
  category: OrderType;
  modality?: string;
}

// ---- Allergies (the patient's full list, including ended ones)

export type AllergenType = 'DRUG' | 'DRUG_CLASS' | 'FOOD' | 'ENVIRONMENT' | 'OTHER';
export type AllergyStatus = 'ACTIVE' | 'INACTIVE' | 'RESOLVED' | 'REFUTED';
export type AllergySeverity = 'MILD' | 'MODERATE' | 'SEVERE' | 'LIFE_THREATENING';

export interface AllergyEntry {
  id: string;
  allergenType: AllergenType;
  allergenText: string;
  drugClass?: string;
  reaction?: string;
  severity?: AllergySeverity;
  status: AllergyStatus;
  recordedBy: string;
  recordedAt: string;
}

// ---- Critical results (call log and acknowledgement)

export type CommunicationMethod = 'PHONE' | 'IN_PERSON' | 'WARD_NURSE';

export interface CriticalResult {
  id: string;
  orderId: string;
  encounterId: string;
  uhid: string;
  patientName: string;
  serviceName: string;
  criticalValues: ResultValue[];
  reportedBy: string;
  reportedAt: string;
  orderedBy: string;
  communicatedTo?: string;
  communicationMethod?: CommunicationMethod;
  communicatedBy?: string;
  communicatedAt?: string;
  acknowledgedBy?: string;
  acknowledgedAt?: string;
  acknowledgementNote?: string;
  status: 'TO_COMMUNICATE' | 'COMMUNICATED' | 'ACKNOWLEDGED';
}

// ---- Immunizations

export type VaccineRoute = 'IM' | 'SC' | 'ORAL' | 'ID' | 'INTRANASAL';
export type VaccineSite = 'LEFT_ARM' | 'RIGHT_ARM' | 'LEFT_DELTOID' | 'RIGHT_DELTOID' | 'LEFT_THIGH' | 'RIGHT_THIGH' | 'ORAL' | 'NASAL';

export interface Vaccine {
  code: string;
  name: string;
  targetDisease: string;
  defaultRoute?: VaccineRoute;
  defaultDoseMl?: number;
  /** Doses in the series; absent for vaccines given repeatedly (e.g. influenza). */
  seriesDoses?: number;
  programme: 'UIP' | 'ADULT' | 'OTHER';
}

export interface ImmunizationRecord {
  id: string;
  encounterId: string;
  vaccineCode: string;
  vaccineName: string;
  targetDisease: string;
  status: 'COMPLETED' | 'NOT_DONE';
  notDoneReason?: string;
  doseNumber?: number;
  seriesDoses?: number;
  administeredAt: string;
  /** False when transcribed from the patient's card. */
  primarySource: boolean;
  reportOrigin?: string;
  lotNumber?: string;
  manufacturer?: string;
  expiryDate?: string;
  route?: VaccineRoute;
  site?: VaccineSite;
  doseMl?: number;
  administeredBy?: string;
  nextDueDate?: string;
  notes?: string;
  enteredInError: boolean;
  enteredInErrorReason?: string;
  recordedAt: string;
}

export interface NewImmunization {
  vaccineCode: string;
  status: 'COMPLETED' | 'NOT_DONE';
  notDoneReason?: string;
  doseNumber?: number;
  administeredAt?: string;
  primarySource: boolean;
  reportOrigin?: string;
  lotNumber?: string;
  manufacturer?: string;
  expiryDate?: string;
  route?: VaccineRoute;
  site?: VaccineSite;
  doseMl?: number;
  nextDueDate?: string;
  notes?: string;
}

/** The signed-in user's staff record: notes and orders are attributed to it. */
export interface Me {
  linked: boolean;
  staffId?: string;
  name?: string;
  staffRole?: string;
  doctor: boolean;
}

// ---- CDSS (AI service, snake_case JSON)

export type CdssSeverity = 'CONTRAINDICATED' | 'MAJOR' | 'MODERATE' | 'MINOR';

export interface CdssCheckRequest {
  prescriptions: { drug_text: string; dose?: string; frequency?: string; duration_days?: number }[];
  allergies: { allergen: string; drug_class?: string }[];
  patient: { age_years?: number; sex?: Gender; weight_kg?: number; serum_creatinine_mg_dl?: number };
}

export interface CdssAlert {
  alert_type: 'DRUG_DRUG' | 'DRUG_ALLERGY' | 'DUPLICATE_THERAPY' | 'DOSE_RANGE' | 'CONTRAINDICATION';
  severity: CdssSeverity;
  rule_code: string;
  message: string;
  management?: string | null;
  source?: string | null;
  lines: number[];
  allergen?: string | null;
}

export interface CdssCheckResponse {
  kb_version: string;
  kb_reviewed: boolean;
  alerts: CdssAlert[];
  recognised: string[][];
  creatinine_clearance_ml_min?: number | null;
  not_checked: string[];
}

export interface Icd10Code {
  code: string;
  title: string;
}

export interface DictationSessionRecord {
  aiSessionId: string;
  language: string;
  engine: string;
  audioSeconds: number;
  outcome: string;
  startedAt: string;
  endedAt?: string;
}

export interface ConsultationRequest {
  dictationSessions: DictationSessionRecord[];
  vitals?: Record<string, number | string | undefined>;
  complaints: Record<string, unknown>[];
  diagnoses: Record<string, unknown>[];
  prescription?: { items: Record<string, unknown>[]; advice?: string; followUpDate?: string };
  cdssOverrides: { ruleCode: string; reason: string }[];
  completeVisit: boolean;
}
