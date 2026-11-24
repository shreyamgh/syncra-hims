package com.syncra.hims.abdm.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import com.syncra.hims.abdm.fhir.EncounterRecord.Admission;
import com.syncra.hims.abdm.fhir.EncounterRecord.Allergy;
import com.syncra.hims.abdm.fhir.EncounterRecord.Complaint;
import com.syncra.hims.abdm.fhir.EncounterRecord.Diagnosis;
import com.syncra.hims.abdm.fhir.EncounterRecord.Encounter;
import com.syncra.hims.abdm.fhir.EncounterRecord.Note;
import com.syncra.hims.abdm.fhir.EncounterRecord.Order;
import com.syncra.hims.abdm.fhir.EncounterRecord.Patient;
import com.syncra.hims.abdm.fhir.EncounterRecord.Practitioner;
import com.syncra.hims.abdm.fhir.EncounterRecord.Prescription;
import com.syncra.hims.abdm.fhir.EncounterRecord.PrescriptionItem;
import com.syncra.hims.abdm.fhir.EncounterRecord.Vitals;
import com.syncra.hims.abdm.support.TestProperties;
import com.syncra.hims.common.config.HospitalTime;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The generated documents are valid FHIR R4 (validated against the base specification with
 * HAPI's instance validator), follow the NRCES document structure, and are self-contained.
 * The NRCES profiles themselves are not loaded here; conformance to them must be confirmed
 * in the ABDM sandbox.
 */
class HealthRecordBuilderTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Instant VISIT = Instant.parse("2026-11-18T04:30:00Z");
    static final UUID DOCTOR = UUID.fromString("00000000-0000-0000-0000-00000000d0c1");

    static FhirValidator validator;
    static FhirJson json = new FhirJson();
    final HealthRecordBuilder builder = new HealthRecordBuilder(TestProperties.abdm(false, true),
            new HospitalTime(Clock.fixed(Instant.parse("2026-11-20T06:00:00Z"), IST)));

    @BeforeAll
    static void validator() {
        FhirContext context = json.context();
        ValidationSupportChain support = new ValidationSupportChain(
                new DefaultProfileValidationSupport(context),
                new CommonCodeSystemsTerminologyService(context),
                new InMemoryTerminologyServerValidationSupport(context),
                new SnapshotGeneratingValidationSupport(context));
        FhirInstanceValidator module = new FhirInstanceValidator(support);
        module.setErrorForUnknownProfiles(false);
        module.setNoTerminologyChecks(false);
        validator = context.newValidator().registerValidatorModule(module);
    }

    @Test
    void opConsultationIsAValidNrcesStyleDocument() {
        Bundle bundle = builder.build(HiType.OP_CONSULTATION, opd()).orElseThrow();

        assertValid(bundle);
        Composition composition = (Composition) bundle.getEntryFirstRep().getResource();
        assertThat(composition.getType().getCodingFirstRep().getCode()).isEqualTo("371530004");
        assertThat(composition.getMeta().getProfile().get(0).getValue())
                .isEqualTo("https://nrces.in/ndhm/fhir/r4/StructureDefinition/OPConsultRecord");
        assertThat(composition.getSection()).extracting(s -> s.getCode().getCodingFirstRep().getCode())
                .containsExactly("422843007", "425044008", "722446000", "721963009", "721912009", "736271009");
        assertThat(bundle.getEntry()).extracting(e -> e.getResource().fhirType())
                .contains("Patient", "Practitioner", "Organization", "Encounter", "Condition", "Observation",
                        "AllergyIntolerance", "ServiceRequest", "MedicationRequest", "CarePlan", "Appointment");

        Observation bp = resources(bundle, Observation.class).stream()
                .filter(o -> o.getCode().getCodingFirstRep().getCode().equals("85354-9")).findFirst().orElseThrow();
        assertThat(bp.getComponent()).extracting(c -> c.getValueQuantity().getValue().intValue()).containsExactly(130, 84);
        assertThat(bp.getEffectiveDateTimeType().getValueAsString()).isEqualTo("2026-11-18T10:05:00+05:30");
    }

    @Test
    void prescriptionCarriesStructuredDosage() {
        Bundle bundle = builder.build(HiType.PRESCRIPTION, opd()).orElseThrow();

        assertValid(bundle);
        List<MedicationRequest> meds = resources(bundle, MedicationRequest.class);
        assertThat(meds).hasSize(2);
        MedicationRequest paracetamol = meds.get(0);
        assertThat(paracetamol.getMedicationCodeableConcept().getText()).isEqualTo("Tab Paracetamol 650 mg");
        assertThat(paracetamol.getDosageInstructionFirstRep().getText())
                .isEqualTo("1 tab, three times a day, after food, for 3 days");
        assertThat(paracetamol.getDosageInstructionFirstRep().getTiming().getRepeat().getFrequency()).isEqualTo(3);
        assertThat(paracetamol.getDosageInstructionFirstRep().getRoute().getCodingFirstRep().getCode()).isEqualTo("26643006");
        assertThat(paracetamol.getReasonReferenceFirstRep().getReference()).startsWith("urn:uuid:");
        assertThat(meds.get(1).getDosageInstructionFirstRep().getAsNeededBooleanType().booleanValue()).isTrue();
    }

    @Test
    void dischargeSummaryOnlyAfterDischarge() {
        EncounterRecord stay = ipd(Instant.parse("2026-11-19T07:00:00Z"));
        assertThat(builder.available(stay)).containsExactlyInAnyOrder(HiType.DISCHARGE_SUMMARY, HiType.PRESCRIPTION,
                HiType.DIAGNOSTIC_REPORT, HiType.WELLNESS_RECORD);

        Bundle bundle = builder.build(HiType.DISCHARGE_SUMMARY, stay).orElseThrow();
        assertValid(bundle);
        Composition composition = (Composition) bundle.getEntryFirstRep().getResource();
        assertThat(composition.getType().getCodingFirstRep().getCode()).isEqualTo("373942005");
        assertThat(composition.getSection()).extracting(Composition.SectionComponent::getTitle)
                .contains("Investigations", "Medications", "Document reference");
        org.hl7.fhir.r4.model.Encounter encounter = resources(bundle, org.hl7.fhir.r4.model.Encounter.class).get(0);
        assertThat(encounter.getClass_().getCode()).isEqualTo("IMP");
        assertThat(encounter.getHospitalization().getDischargeDisposition().getCodingFirstRep().getCode()).isEqualTo("home");

        EncounterRecord stillAdmitted = ipd(null);
        assertThat(builder.available(stillAdmitted)).doesNotContain(HiType.DISCHARGE_SUMMARY);
        assertThat(builder.build(HiType.DISCHARGE_SUMMARY, stillAdmitted)).isEmpty();
    }

    @Test
    void sameRecordYieldsSameResources() {
        Bundle first = builder.build(HiType.OP_CONSULTATION, opd()).orElseThrow();
        Bundle second = builder.build(HiType.OP_CONSULTATION, opd()).orElseThrow();
        assertThat(ids(first)).isEqualTo(ids(second));
        assertThat(first.getIdentifier().getValue()).isNotEqualTo(second.getIdentifier().getValue());
    }

    @Test
    void verifiedAbhaNumberIsAnIdentifierAndUnverifiedIsNot() {
        Bundle bundle = builder.build(HiType.OP_CONSULTATION, opd()).orElseThrow();
        org.hl7.fhir.r4.model.Patient patient = resources(bundle, org.hl7.fhir.r4.model.Patient.class).get(0);
        assertThat(patient.getIdentifier()).extracting(i -> i.getValue())
                .containsExactly("SYNC-20261118-0007", "91-1111-2222-3333");
    }

    @Test
    void diagnosticReportCarriesLabResultsAndTheImagingStudy() {
        EncounterRecord record = withReports(opd());
        assertThat(builder.available(record)).contains(HiType.DIAGNOSTIC_REPORT);

        Bundle bundle = builder.build(HiType.DIAGNOSTIC_REPORT, record).orElseThrow();
        assertValid(bundle);
        Composition composition = (Composition) bundle.getEntryFirstRep().getResource();
        assertThat(composition.getType().getCodingFirstRep().getCode()).isEqualTo("721981007");
        assertThat(composition.getMeta().getProfile().get(0).getValue())
                .isEqualTo("https://nrces.in/ndhm/fhir/r4/StructureDefinition/DiagnosticReportRecord");
        assertThat(composition.getSection()).extracting(Composition.SectionComponent::getTitle)
                .containsExactly("Laboratory reports", "Imaging reports");
        assertThat(composition.getAuthorFirstRep().getReference()).isEqualTo("urn:uuid:" + RADIOLOGIST);

        List<DiagnosticReport> reports = resources(bundle, DiagnosticReport.class);
        assertThat(reports).as("pending order and draft study left out").hasSize(2);
        DiagnosticReport lab = reports.stream().filter(r -> r.getCategoryFirstRep().getCodingFirstRep().getCode()
                .equals("LAB")).findFirst().orElseThrow();
        assertThat(lab.getConclusion()).isEqualTo("Random blood sugar 182 mg/dL");
        assertThat(lab.getCode().getText()).isEqualTo("Random Blood Sugar");
        assertThat(lab.getBasedOnFirstRep().getReference()).startsWith("urn:uuid:");
        assertThat(lab.getStatus()).isEqualTo(DiagnosticReport.DiagnosticReportStatus.FINAL);

        DiagnosticReport xray = reports.stream().filter(r -> r.getCategoryFirstRep().getCodingFirstRep().getCode()
                .equals("RAD")).findFirst().orElseThrow();
        assertThat(xray.getStatus()).isEqualTo(DiagnosticReport.DiagnosticReportStatus.AMENDED);
        assertThat(new String(xray.getPresentedFormFirstRep().getData(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(XRAY_REPORT);
        assertThat(xray.getResultsInterpreterFirstRep().getReference()).isEqualTo("urn:uuid:" + RADIOLOGIST);
        org.hl7.fhir.r4.model.ImagingStudy study = resources(bundle, org.hl7.fhir.r4.model.ImagingStudy.class).get(0);
        assertThat(xray.getImagingStudyFirstRep().getReference()).isEqualTo("urn:uuid:" + study.getIdPart());
        assertThat(study.getIdentifier()).extracting(i -> i.getValue())
                .containsExactly("urn:oid:1.2.826.0.1.3680043.8.498.10001", "RAD000123");
        assertThat(study.getModalityFirstRep().getCode()).isEqualTo("DX");
        assertThat(study.getNumberOfInstances()).isEqualTo(2);

        // The radiology report is not repeated in the consultation record
        Bundle consultation = builder.build(HiType.OP_CONSULTATION, record).orElseThrow();
        assertThat(resources(consultation, org.hl7.fhir.r4.model.DocumentReference.class)).isEmpty();
    }

    @Test
    void pendingResultsAndDraftReportsAreNotShared() {
        assertThat(builder.available(opd())).doesNotContain(HiType.DIAGNOSTIC_REPORT);
        assertThat(builder.build(HiType.DIAGNOSTIC_REPORT, opd())).isEmpty();
    }

    @Test
    void immunizationRecordShowsDosesGivenTranscribedAndDue() {
        EncounterRecord record = withImmunizations(opd());
        Bundle bundle = builder.build(HiType.IMMUNIZATION_RECORD, record).orElseThrow();
        assertValid(bundle);

        Composition composition = (Composition) bundle.getEntryFirstRep().getResource();
        assertThat(composition.getType().getCodingFirstRep().getCode()).isEqualTo("41000179103");
        assertThat(composition.getMeta().getProfile().get(0).getValue())
                .isEqualTo("https://nrces.in/ndhm/fhir/r4/StructureDefinition/ImmunizationRecord");
        assertThat(composition.getAuthorFirstRep().getReference()).isEqualTo("urn:uuid:" + NURSE);

        List<org.hl7.fhir.r4.model.Immunization> doses = resources(bundle, org.hl7.fhir.r4.model.Immunization.class);
        assertThat(doses).hasSize(3);
        org.hl7.fhir.r4.model.Immunization penta = doses.get(1);
        assertThat(penta.getVaccineCode().getText()).isEqualTo("Pentavalent (DPT-HepB-Hib)");
        assertThat(penta.getLotNumber()).isEqualTo("SII-PENTA-2611");
        assertThat(penta.getExpirationDateElement().getValueAsString()).isEqualTo("2027-06-30");
        assertThat(penta.getManufacturer().getDisplay()).isEqualTo("Serum Institute of India");
        assertThat(penta.getSite().getText()).isEqualTo("Left thigh");
        assertThat(penta.getRoute().getCodingFirstRep().getCode()).isEqualTo("78421000");
        assertThat(penta.getDoseQuantity().getValue()).isEqualByComparingTo("0.50");
        assertThat(penta.getPerformerFirstRep().getFunction().getCodingFirstRep().getCode()).isEqualTo("AP");
        assertThat(penta.getProtocolAppliedFirstRep().getDoseNumberPositiveIntType().getValue()).isEqualTo(1);
        assertThat(penta.getProtocolAppliedFirstRep().getSeriesDosesPositiveIntType().getValue()).isEqualTo(3);

        org.hl7.fhir.r4.model.Immunization bcg = doses.get(0);
        assertThat(bcg.getPrimarySource()).isFalse();
        assertThat(bcg.getReportOrigin().getText()).isEqualTo("MCP card");
        org.hl7.fhir.r4.model.Immunization opv = doses.get(2);
        assertThat(opv.getStatus()).isEqualTo(org.hl7.fhir.r4.model.Immunization.ImmunizationStatus.NOTDONE);
        assertThat(opv.getStatusReason().getText()).isEqualTo("Child unwell with fever");
        assertThat(opv.hasDoseQuantity()).isFalse();

        org.hl7.fhir.r4.model.ImmunizationRecommendation next =
                resources(bundle, org.hl7.fhir.r4.model.ImmunizationRecommendation.class).get(0);
        assertThat(next.getRecommendation()).hasSize(2);
        assertThat(next.getRecommendation().get(0).getDoseNumberPositiveIntType().getValue()).as("PENTA dose 2").isEqualTo(2);
        assertThat(next.getRecommendation().get(0).getDateCriterionFirstRep().getValueElement().getValueAsString())
                .isEqualTo("2026-12-16");
        assertThat(next.getRecommendation().get(1).getDoseNumberPositiveIntType().getValue()).as("OPV dose 2 again")
                .isEqualTo(2);
    }

    @Test
    void aVaccinationOnlyVisitSharesOnlyItsImmunizationRecord() {
        EncounterRecord base = opd();
        EncounterRecord clinicVisit = withImmunizations(new EncounterRecord(base.encounter(), base.patient(), null,
                List.of(), List.of(), List.of(), base.allergies(), List.of(), List.of(), List.of(), List.of(), List.of(),
                base.practitioners()));
        assertThat(builder.available(clinicVisit)).containsExactly(HiType.IMMUNIZATION_RECORD);
        assertThat(builder.available(base)).doesNotContain(HiType.IMMUNIZATION_RECORD);
    }

    @Test
    void validatorCatchesBrokenDocuments() {
        // Negative control: proves the validator above is really checking.
        Bundle bundle = builder.build(HiType.OP_CONSULTATION, opd()).orElseThrow();
        Observation broken = resources(bundle, Observation.class).get(0);
        broken.setStatus(null);
        broken.getCategoryFirstRep().getCodingFirstRep().setCode("vital-signs-typo");
        bundle.setIdentifier(null);
        List<SingleValidationMessage> errors = validator.validateWithResult(bundle).getMessages().stream()
                .filter(m -> m.getSeverity().ordinal() >= ResultSeverityEnum.ERROR.ordinal()).toList();
        assertThat(errors).extracting(SingleValidationMessage::getMessage)
                .anyMatch(message -> message.contains("status"))
                .anyMatch(message -> message.contains("bdl-9") || message.contains("identifier"));
    }

    @Test
    void labReportsCarryOneCodedObservationPerValue() {
        EncounterRecord record = withRenalPanel(opd());
        Bundle bundle = builder.build(HiType.DIAGNOSTIC_REPORT, record).orElseThrow();
        assertValid(bundle);

        DiagnosticReport panel = resources(bundle, DiagnosticReport.class).get(0);
        assertThat(panel.getCode().getCoding()).extracting(c -> c.getSystem() + "|" + c.getCode())
                .startsWith("http://loinc.org|24362-6");
        assertThat(panel.getEffectiveDateTimeType().getValueAsString()).as("effective when the sample was taken")
                .isEqualTo("2026-11-18T10:10:00+05:30");
        assertThat(panel.getResult()).as("the empty chloride left out").hasSize(6);

        List<Observation> values = resources(bundle, Observation.class);
        Map<String, Observation> byName = values.stream()
                .collect(Collectors.toMap(o -> o.getCode().getText(), o -> o));
        Observation potassium = byName.get("Potassium");
        assertThat(potassium.getCode().getCodingFirstRep().getCode()).isEqualTo("2823-3");
        assertThat(potassium.getCategoryFirstRep().getCodingFirstRep().getCode()).isEqualTo("laboratory");
        assertThat(potassium.getValueQuantity().getValue()).isEqualByComparingTo("6.6");
        assertThat(potassium.getValueQuantity().getCode()).isEqualTo("mmol/L");
        assertThat(potassium.getValueQuantity().getSystem()).isEqualTo("http://unitsofmeasure.org");
        assertThat(potassium.getInterpretationFirstRep().getCodingFirstRep().getCode()).isEqualTo("HH");
        assertThat(potassium.getReferenceRangeFirstRep().getLow().getValue()).isEqualByComparingTo("3.5");
        assertThat(potassium.getReferenceRangeFirstRep().getHigh().getValue()).isEqualByComparingTo("5.1");
        assertThat(byName.get("Sodium").getInterpretationFirstRep().getCodingFirstRep().getCode()).isEqualTo("L");
        assertThat(byName.get("Serum creatinine").getInterpretationFirstRep().getCodingFirstRep().getCode()).isEqualTo("H");
        // A bound written "< 40" is a high limit only; a value as text stays text.
        assertThat(byName.get("eGFR").getReferenceRangeFirstRep().hasLow()).isTrue();
        assertThat(byName.get("eGFR").getReferenceRangeFirstRep().hasHigh()).isFalse();
        assertThat(byName.get("Sample").getValueStringType().getValue()).isEqualTo("Mildly haemolysed");
        assertThat(byName.get("Sample").getCode().getCoding()).extracting(c -> c.getSystem())
                .doesNotContain("http://loinc.org");
        // A value with neither number nor text is not shared.
        assertThat(byName).doesNotContainKey("Chloride");
    }

    @Test
    void wellnessRecordGroupsEveryReadingByKind() {
        EncounterRecord record = opd();
        assertThat(builder.available(record)).contains(HiType.WELLNESS_RECORD)
                .doesNotContain(HiType.INVOICE, HiType.HEALTH_DOCUMENT_RECORD);
        Bundle bundle = builder.build(HiType.WELLNESS_RECORD, record).orElseThrow();
        assertValid(bundle);

        Composition composition = (Composition) bundle.getEntryFirstRep().getResource();
        assertThat(composition.getMeta().getProfile().get(0).getValue())
                .isEqualTo("https://nrces.in/ndhm/fhir/r4/StructureDefinition/WellnessRecord");
        assertThat(composition.getType().getText()).isEqualTo("Wellness Record");
        assertThat(composition.getType().hasCoding()).isFalse();
        assertThat(composition.getSection()).extracting(Composition.SectionComponent::getTitle)
                .containsExactly("Vital Signs", "Body Measurement", "General Assessment");
        assertThat(composition.getSection()).extracting(sec -> sec.getEntry().size()).containsExactly(5, 3, 2);
        Observation weight = resources(bundle, Observation.class).stream()
                .filter(o -> o.getCode().getCodingFirstRep().getCode().equals("29463-7")).findFirst().orElseThrow();
        assertThat(weight.getMeta().getProfile().get(0).getValue())
                .isEqualTo("https://nrces.in/ndhm/fhir/r4/StructureDefinition/ObservationBodyMeasurement");

        // A stay's readings all travel (the discharge summary keeps only the first and last).
        Bundle stay = builder.build(HiType.WELLNESS_RECORD, ipd(Instant.parse("2026-11-19T07:00:00Z"))).orElseThrow();
        assertValid(stay);
        assertThat(resources(stay, Observation.class).stream()
                .filter(o -> o.getCode().getCodingFirstRep().getCode().equals("85354-9"))).hasSize(3);
    }

    @Test
    void healthDocumentRecordCarriesAStaysCaseNotes() {
        Instant discharged = Instant.parse("2026-11-19T07:00:00Z");
        EncounterRecord stay = withCaseNotes(ipd(discharged));
        assertThat(builder.available(stay)).contains(HiType.HEALTH_DOCUMENT_RECORD);
        assertThat(builder.available(ipd(discharged))).as("discharge summary alone").doesNotContain(HiType.HEALTH_DOCUMENT_RECORD);
        assertThat(builder.available(withNotes(opd()))).as("OPD notes travel in the consultation")
                .doesNotContain(HiType.HEALTH_DOCUMENT_RECORD);

        Bundle bundle = builder.build(HiType.HEALTH_DOCUMENT_RECORD, stay).orElseThrow();
        assertValid(bundle);
        Composition composition = (Composition) bundle.getEntryFirstRep().getResource();
        assertThat(composition.getType().getCodingFirstRep().getCode()).isEqualTo("419891008");
        assertThat(composition.getSection()).extracting(Composition.SectionComponent::getTitle)
                .containsExactly("Health Document");
        List<org.hl7.fhir.r4.model.DocumentReference> documents =
                resources(bundle, org.hl7.fhir.r4.model.DocumentReference.class);
        assertThat(documents).extracting(org.hl7.fhir.r4.model.DocumentReference::getDescription)
                .containsExactly("Progress note", "Procedure note");
        assertThat(new String(documents.get(1).getContentFirstRep().getAttachment().getData(),
                java.nio.charset.StandardCharsets.UTF_8)).startsWith("Platelet transfusion");
    }

    @Test
    void invoiceRecordItemisesFinalBillsWithGstAndBatches() {
        EncounterRecord record = withBills(opd());
        assertThat(builder.available(record)).contains(HiType.INVOICE);
        Bundle bundle = builder.build(HiType.INVOICE, record).orElseThrow();
        assertValid(bundle);

        Composition composition = (Composition) bundle.getEntryFirstRep().getResource();
        assertThat(composition.getMeta().getProfile().get(0).getValue())
                .isEqualTo("https://nrces.in/ndhm/fhir/r4/StructureDefinition/InvoiceRecord");
        assertThat(composition.getType().getText()).isEqualTo("Invoice Record");
        assertThat(composition.getSection()).extracting(Composition.SectionComponent::getTitle)
                .containsExactly("OPD Invoice Details", "Pharmacy Invoice Details");

        List<org.hl7.fhir.r4.model.Invoice> invoices = resources(bundle, org.hl7.fhir.r4.model.Invoice.class);
        org.hl7.fhir.r4.model.Invoice opd = invoices.get(0);
        assertThat(opd.getType().getCodingFirstRep().getCode()).isEqualTo("03");
        assertThat(opd.getLineItem()).allSatisfy(item -> assertThat(item.getPriceComponent())
                .extracting(pc -> pc.getCode().getCodingFirstRep().getCode()).containsExactly("01"));
        assertThat(opd.getTotalGross().getValue()).isEqualByComparingTo("350.00");

        org.hl7.fhir.r4.model.Invoice pharmacy = invoices.get(1);
        assertThat(pharmacy.getIdentifierFirstRep().getValue()).isEqualTo("PHB/26-27/000003");
        assertThat(pharmacy.getType().getCodingFirstRep().getCode()).isEqualTo("01");
        org.hl7.fhir.r4.model.Invoice.InvoiceLineItemComponent taxim = pharmacy.getLineItemFirstRep();
        assertThat(taxim.getPriceComponent()).extracting(pc -> pc.getCode().getCodingFirstRep().getDisplay())
                .containsExactly("Rate", "MRP", "CGST", "SGST");
        assertThat(taxim.getPriceComponent().get(1).getAmount().getValue()).as("MRP x quantity").isEqualByComparingTo("65.40");
        BigDecimal cgst = taxim.getPriceComponent().get(2).getAmount().getValue();
        BigDecimal sgst = taxim.getPriceComponent().get(3).getAmount().getValue();
        assertThat(cgst).isEqualByComparingTo("3.51");
        assertThat(sgst).isEqualByComparingTo("3.50");
        assertThat(taxim.getPriceComponent().get(2).getFactor()).isEqualByComparingTo("0.06");
        assertThat(pharmacy.getTotalNet().getValue()).isEqualByComparingTo("78.30");
        assertThat(pharmacy.getTotalGross().getValue()).isEqualByComparingTo("88.00");
        assertThat(pharmacy.getNoteFirstRep().getText()).contains("rounded off");

        org.hl7.fhir.r4.model.ChargeItem charge = resources(bundle, org.hl7.fhir.r4.model.ChargeItem.class).stream()
                .filter(ci -> ci.getCode().getText().startsWith("Taxim-O")).findFirst().orElseThrow();
        assertThat(charge.getCode().getCodingFirstRep().getCode()).isEqualTo("05");
        assertThat(charge.getQuantity().getUnit()).isEqualTo("tablet");
        org.hl7.fhir.r4.model.Medication medication = resources(bundle, org.hl7.fhir.r4.model.Medication.class).get(0);
        assertThat(medication.getBatch().getLotNumber()).isEqualTo("TX5021");
        assertThat(medication.getBatch().getExpirationDateElement().getValueAsString()).isEqualTo("2027-01-31");
        assertThat(charge.getProductReference().getReference()).isEqualTo("urn:uuid:" + medication.getIdPart());
    }

    // ------------------------------------------------------------------ helpers

    static void assertValid(Bundle bundle) {
        List<SingleValidationMessage> errors = validator.validateWithResult(bundle).getMessages().stream()
                .filter(m -> m.getSeverity().ordinal() >= ResultSeverityEnum.ERROR.ordinal())
                .toList();
        assertThat(errors).as(() -> errors.stream().map(m -> m.getLocationString() + ": " + m.getMessage())
                .collect(Collectors.joining("\n"))).isEmpty();

        // Every reference resolves inside the document
        Set<String> fullUrls = bundle.getEntry().stream().map(Bundle.BundleEntryComponent::getFullUrl)
                .collect(Collectors.toSet());
        String encoded = json.encode(bundle);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"reference\":\"(urn:uuid:[^\"]+)\"").matcher(encoded);
        Set<String> references = new HashSet<>();
        while (m.find()) {
            references.add(m.group(1));
        }
        assertThat(fullUrls).containsAll(references);
        assertThat(bundle.getEntryFirstRep().getResource()).isInstanceOf(Composition.class);
    }

    static <T extends Resource> List<T> resources(Bundle bundle, Class<T> type) {
        return bundle.getEntry().stream().map(Bundle.BundleEntryComponent::getResource)
                .filter(type::isInstance).map(type::cast).toList();
    }

    static List<String> ids(Bundle bundle) {
        return bundle.getEntry().stream().map(Bundle.BundleEntryComponent::getFullUrl).toList();
    }

    static final UUID RADIOLOGIST = UUID.fromString("00000000-0000-0000-0000-00000000aad1");
    static final String XRAY_REPORT = "Chest PA view: no focal consolidation. Cardiac size normal.\n"
            + "ADDENDUM: mild blunting of the right costophrenic angle.";

    /** {@link #opd()} plus a reported blood sugar, an X-ray reported with an addendum, and a draft CT. */
    static EncounterRecord withReports(EncounterRecord base) {
        UUID sugar = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        UUID xray = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
        UUID ct = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
        List<Order> orders = new java.util.ArrayList<>(base.orders());
        orders.add(new Order(sugar, "LAB-RBS", "Random Blood Sugar", "LABORATORY", "REPORTED", "ROUTINE", null, DOCTOR,
                VISIT.plusSeconds(1300), VISIT.plusSeconds(4000), "Random blood sugar 182 mg/dL"));
        orders.add(new Order(xray, "RAD-XCH", "X-Ray Chest PA View", "RADIOLOGY", "COMPLETED", "ROUTINE", "Cough",
                DOCTOR, VISIT.plusSeconds(1300), VISIT.plusSeconds(5000), null));
        orders.add(new Order(ct, "RAD-CTH", "CT Head (Plain)", "RADIOLOGY", "COMPLETED", "ROUTINE", null,
                DOCTOR, VISIT.plusSeconds(1300), VISIT.plusSeconds(5000), null));
        List<EncounterRecord.Study> studies = List.of(
                new EncounterRecord.Study(UUID.fromString("00000000-0000-0000-0000-0000000005a1"), xray,
                        "1.2.826.0.1.3680043.8.498.10001", "RAD000123", "DX", LocalDate.of(2026, 11, 18),
                        "Chest PA", 1, 2, "ADDENDUM", XRAY_REPORT, RADIOLOGIST, VISIT.plusSeconds(9000)),
                new EncounterRecord.Study(UUID.fromString("00000000-0000-0000-0000-0000000005a2"), ct,
                        "1.2.826.0.1.3680043.8.498.10002", null, "CT", LocalDate.of(2026, 11, 18),
                        "CT Head", 3, 120, "DRAFT", null, null, null));
        Map<UUID, Practitioner> staff = new java.util.HashMap<>(base.practitioners());
        staff.put(RADIOLOGIST, new Practitioner(RADIOLOGIST, "Kavita Rao", "Dr", "MD (Radiodiagnosis)",
                "MMC/2015/55555", null));
        return new EncounterRecord(base.encounter(), base.patient(), base.admission(), base.vitals(), base.complaints(),
                base.diagnoses(), base.allergies(), base.prescriptions(), orders, studies, base.immunizations(), base.notes(), staff);
    }

    static final UUID NURSE = UUID.fromString("00000000-0000-0000-0000-0000000aa5e1");

    /** A transcribed BCG, a PENTA dose given here, and an OPV dose deferred, two with next due dates. */
    static EncounterRecord withImmunizations(EncounterRecord base) {
        Instant given = VISIT.plusSeconds(2400);
        List<EncounterRecord.Immunization> doses = List.of(
                new EncounterRecord.Immunization(UUID.fromString("00000000-0000-0000-0000-00000000b001"), "BCG", "BCG",
                        "Tuberculosis", 1, "COMPLETED", null, 1, Instant.parse("2026-09-01T04:00:00Z"), false,
                        "MCP card", null, null, null, "ID", null, null, null, null, null, given),
                new EncounterRecord.Immunization(UUID.fromString("00000000-0000-0000-0000-00000000b002"), "PENTA",
                        "Pentavalent (DPT-HepB-Hib)", "Diphtheria, pertussis, tetanus, hepatitis B, Hib disease", 3,
                        "COMPLETED", null, 1, given, true, null, "SII-PENTA-2611", "Serum Institute of India",
                        LocalDate.of(2027, 6, 30), "IM", "LEFT_THIGH", new BigDecimal("0.50"), NURSE,
                        LocalDate.of(2026, 12, 16), "No immediate reaction after 30 minutes", given),
                new EncounterRecord.Immunization(UUID.fromString("00000000-0000-0000-0000-00000000b003"), "OPV",
                        "Oral Polio Vaccine", "Poliomyelitis", 5, "NOT_DONE", "Child unwell with fever", 2, given, true,
                        null, null, null, null, "ORAL", "ORAL", null, null, LocalDate.of(2026, 11, 25), null, given));
        Map<UUID, Practitioner> staff = new java.util.HashMap<>(base.practitioners());
        staff.put(NURSE, new Practitioner(NURSE, "Sister Mary Thomas", null, "GNM", null, null));
        return new EncounterRecord(base.encounter(), base.patient(), base.admission(), base.vitals(), base.complaints(),
                base.diagnoses(), base.allergies(), base.prescriptions(), base.orders(), base.studies(), doses,
                base.notes(), staff);
    }

    /** {@link #opd()} plus a reported renal panel: coded values, a text value and an empty one. */
    static EncounterRecord withRenalPanel(EncounterRecord base) {
        UUID rft = UUID.fromString("00000000-0000-0000-0000-0000000000ad");
        List<EncounterRecord.ResultValue> values = List.of(
                new EncounterRecord.ResultValue("UREA", "Blood urea", new BigDecimal("68"), null, "mg/dL", "15-40", "H",
                        "3091-6", "Urea [Mass/volume] in Serum or Plasma"),
                new EncounterRecord.ResultValue("CREAT", "Serum creatinine", new BigDecimal("2.4"), null, "mg/dL",
                        "0.7-1.3", "H", "2160-0", "Creatinine [Mass/volume] in Serum or Plasma"),
                new EncounterRecord.ResultValue("SODIUM", "Sodium", new BigDecimal("133"), null, "mmol/L", "135-145", "L",
                        "2951-2", "Sodium [Moles/volume] in Serum or Plasma"),
                new EncounterRecord.ResultValue("POTASSIUM", "Potassium", new BigDecimal("6.6"), null, "mmol/L",
                        "3.5-5.1", "C", "2823-3", "Potassium [Moles/volume] in Serum or Plasma"),
                new EncounterRecord.ResultValue("CL", "Chloride", null, null, "mmol/L", "98-107", null, null, null),
                new EncounterRecord.ResultValue("EGFR", "eGFR", new BigDecimal("28"), null, "mL/min/1.73m2", "> 60", "L",
                        null, null),
                new EncounterRecord.ResultValue(null, "Sample", null, "Mildly haemolysed", null, null, null, null, null));
        List<Order> orders = List.of(new Order(rft, "LAB-RFT", "Renal Function Test", "LABORATORY", "REPORTED", "STAT",
                "CKD 3", DOCTOR, VISIT.plusSeconds(1200), VISIT.plusSeconds(6000), "Worsening renal function",
                "24362-6", "Renal function 2000 panel - Serum or Plasma", VISIT.plusSeconds(600), values));
        return new EncounterRecord(base.encounter(), base.patient(), base.admission(), base.vitals(), base.complaints(),
                base.diagnoses(), base.allergies(), base.prescriptions(), orders, base.studies(), base.immunizations(),
                base.notes(), base.practitioners());
    }

    /** A stay with a progress note and a procedure note besides its discharge summary. */
    static EncounterRecord withCaseNotes(EncounterRecord base) {
        List<Note> notes = new java.util.ArrayList<>(List.of(
                new Note(UUID.fromString("00000000-0000-0000-0000-0000000001d2"), "PROGRESS",
                        "Day 2: afebrile, platelets 32,000, no bleeding.", DOCTOR, Instant.parse("2026-11-16T05:00:00Z")),
                new Note(UUID.fromString("00000000-0000-0000-0000-0000000001d3"), "PROCEDURE",
                        "Platelet transfusion, 1 unit SDP, no reaction.", DOCTOR, Instant.parse("2026-11-16T09:00:00Z"))));
        notes.addAll(base.notes());
        return new EncounterRecord(base.encounter(), base.patient(), base.admission(), base.vitals(), base.complaints(),
                base.diagnoses(), base.allergies(), base.prescriptions(), base.orders(), base.studies(),
                base.immunizations(), notes, base.practitioners());
    }

    static EncounterRecord withNotes(EncounterRecord base) {
        return new EncounterRecord(base.encounter(), base.patient(), base.admission(), base.vitals(), base.complaints(),
                base.diagnoses(), base.allergies(), base.prescriptions(), base.orders(), base.studies(),
                base.immunizations(), List.of(new Note(UUID.fromString("00000000-0000-0000-0000-0000000000d1"),
                        "HISTORY", "Fever for 3 days.", DOCTOR, VISIT.plusSeconds(600))), base.practitioners());
    }

    /**
     * The OPD fee bill (exempt health services) and a pharmacy invoice: 6 Taxim-O at MRP 10.90
     * (taxable 9.73, GST 12%) and 6 Dolo 650 at MRP 2.24 (taxable 2.00), rounded to the rupee.
     */
    static EncounterRecord withBills(EncounterRecord base) {
        Instant billed = VISIT.plusSeconds(2400);
        LocalDate day = LocalDate.of(2026, 11, 18);
        EncounterRecord.Invoice opdBill = new EncounterRecord.Invoice(UUID.fromString("00000000-0000-0000-0000-00000000b111"),
                "OPB/26-27/000031", "OPD", VISIT.plusSeconds(60), new BigDecimal("350.00"), BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2), new BigDecimal("350.00"), BigDecimal.ZERO.setScale(2), new BigDecimal("350.00"),
                List.of(new EncounterRecord.InvoiceLine(UUID.fromString("00000000-0000-0000-0000-00000000b112"), 1,
                                "REG-OPD", "OPD Registration", "REGISTRATION", "OPD Registration", day, BigDecimal.ONE, null,
                                new BigDecimal("50.00"), new BigDecimal("50.00"), BigDecimal.ZERO.setScale(2),
                                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), new BigDecimal("50.00"), null, null,
                                null, null),
                        new EncounterRecord.InvoiceLine(UUID.fromString("00000000-0000-0000-0000-00000000b113"), 2,
                                "CON-NEW", "Consultation - New Visit", "CONSULTATION", "Consultation - New Visit", day,
                                BigDecimal.ONE, null, new BigDecimal("300.00"), new BigDecimal("300.00"),
                                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                                new BigDecimal("300.00"), null, null, null, null)));
        EncounterRecord.Invoice pharmacyBill = new EncounterRecord.Invoice(
                UUID.fromString("00000000-0000-0000-0000-00000000b121"), "PHB/26-27/000003", "PHARMACY", billed,
                new BigDecimal("78.38"), new BigDecimal("0.08"), new BigDecimal("9.40"), new BigDecimal("87.70"),
                new BigDecimal("0.30"), new BigDecimal("88.00"),
                List.of(new EncounterRecord.InvoiceLine(UUID.fromString("00000000-0000-0000-0000-00000000b122"), 1,
                                "PHM-DRUG", "Medicines", "PHARMACY", "Taxim-O 200 200 mg · batch TX5021 · exp 01/2027", day,
                                new BigDecimal("6"), "tablet", new BigDecimal("9.73"), new BigDecimal("58.38"),
                                BigDecimal.ZERO.setScale(2), new BigDecimal("12"), new BigDecimal("7.01"),
                                new BigDecimal("65.39"), new BigDecimal("10.90"), "TX5021", LocalDate.of(2027, 1, 31),
                                "Taxim-O 200 200 mg"),
                        new EncounterRecord.InvoiceLine(UUID.fromString("00000000-0000-0000-0000-00000000b123"), 2,
                                "PHM-DRUG", "Medicines", "PHARMACY", "Dolo 650 650 mg · batch DOL4417 · exp 05/2029", day,
                                new BigDecimal("10"), "tablet", new BigDecimal("2.00"), new BigDecimal("20.00"),
                                new BigDecimal("0.08"), new BigDecimal("12"), new BigDecimal("2.39"), new BigDecimal("22.31"),
                                new BigDecimal("2.24"), "DOL4417", LocalDate.of(2029, 5, 31), "Dolo 650 650 mg")));
        return new EncounterRecord(base.encounter(), base.patient(), base.admission(), base.vitals(), base.complaints(),
                base.diagnoses(), base.allergies(), base.prescriptions(), base.orders(), base.studies(),
                base.immunizations(), base.notes(), base.practitioners(), List.of(opdBill, pharmacyBill));
    }

    static Patient patient() {
        return new Patient(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "SYNC-20261118-0007", "Ramesh",
                null, "Kulkarni", "Ramesh Kulkarni", "M", LocalDate.of(1975, 5, 5), "9876543210", "91111122223333",
                "ramesh.k@sbx", true, "12 Shivaji Nagar", "Pune", "Pune", "Maharashtra", "411005");
    }

    static Map<UUID, Practitioner> doctors() {
        return Map.of(DOCTOR, new Practitioner(DOCTOR, "Anil Mehta", "Dr", "MBBS, MD (Medicine)", "MMC/2011/12345",
                "71-1234-5678-9012"));
    }

    static EncounterRecord opd() {
        UUID encounter = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        UUID rx = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
        return new EncounterRecord(
                new Encounter(encounter, patient().id(), "OPD", "FINISHED", VISIT, VISIT.plusSeconds(1800), "GMED",
                        "General Medicine", DOCTOR),
                patient(), null,
                List.of(new Vitals(UUID.fromString("00000000-0000-0000-0000-0000000000b1"), VISIT.plusSeconds(300),
                        new BigDecimal("38.6"), 104, 20, 130, 84, 97, new BigDecimal("72.50"), new BigDecimal("170.0"),
                        new BigDecimal("25.09"), 110, 4)),
                List.of(new Complaint(UUID.fromString("00000000-0000-0000-0000-0000000000c1"), "Fever", 3, "DAYS",
                        "MODERATE", "386661006", true, VISIT.plusSeconds(400))),
                List.of(new Diagnosis(UUID.fromString("00000000-0000-0000-0000-0000000000c2"), "A90", null,
                        "Dengue fever", "PROVISIONAL", true, VISIT.plusSeconds(900))),
                List.of(new Allergy(UUID.fromString("00000000-0000-0000-0000-0000000000c3"), "DRUG", "Sulfonamides",
                        "Rash", "MODERATE", VISIT.minusSeconds(86_400 * 30L))),
                List.of(new Prescription(rx, DOCTOR, VISIT.plusSeconds(1500), "Plenty of oral fluids; review with CBC",
                        LocalDate.of(2026, 11, 21), List.of(
                        new PrescriptionItem(UUID.fromString("00000000-0000-0000-0000-0000000000f2"), 1,
                                "Tab Paracetamol 650 mg", "1 tab", "ORAL", "TDS", "AFTER_FOOD", 3, new BigDecimal("9"),
                                null),
                        new PrescriptionItem(UUID.fromString("00000000-0000-0000-0000-0000000000f3"), 2,
                                "Tab Ondansetron 4 mg", "1 tab", "ORAL", "SOS", null, null, null, "For vomiting")))),
                List.of(new Order(UUID.fromString("00000000-0000-0000-0000-0000000000a9"), "LAB-CBC",
                        "Complete Blood Count", "LABORATORY", "ORDERED", "URGENT", "Fever with thrombocytopenia?",
                        DOCTOR, VISIT.plusSeconds(1200), null, null)),
                List.of(),
                List.of(),
                List.of(),
                doctors());
    }

    static EncounterRecord ipd(Instant dischargedAt) {
        UUID encounter = UUID.fromString("00000000-0000-0000-0000-0000000001e1");
        Instant admitted = Instant.parse("2026-11-15T10:00:00Z");
        return new EncounterRecord(
                new Encounter(encounter, patient().id(), "IPD", dischargedAt == null ? "IN_PROGRESS" : "FINISHED",
                        admitted, dischargedAt, "GMED", "General Medicine", DOCTOR),
                patient(),
                new Admission(UUID.fromString("00000000-0000-0000-0000-0000000001a1"), "ADM/26-27/000042",
                        dischargedAt == null ? "ADMITTED" : "DISCHARGED", admitted, dischargedAt, DOCTOR,
                        "Dengue with warning signs"),
                List.of(new Vitals(UUID.fromString("00000000-0000-0000-0000-0000000001b1"), admitted.plusSeconds(600),
                                new BigDecimal("39.1"), 112, 22, 100, 60, 95, null, null, null, null, null),
                        new Vitals(UUID.fromString("00000000-0000-0000-0000-0000000001b2"), admitted.plusSeconds(3600),
                                new BigDecimal("37.8"), 96, 18, 110, 70, 97, null, null, null, null, null),
                        new Vitals(UUID.fromString("00000000-0000-0000-0000-0000000001b3"), admitted.plusSeconds(86_400 * 3L),
                                new BigDecimal("36.9"), 82, 16, 118, 76, 99, null, null, null, null, null)),
                List.of(new Complaint(UUID.fromString("00000000-0000-0000-0000-0000000001c1"), "High grade fever", 5,
                        "DAYS", "SEVERE", null, true, admitted.plusSeconds(700))),
                List.of(new Diagnosis(UUID.fromString("00000000-0000-0000-0000-0000000001c2"), "A91", null,
                        "Dengue haemorrhagic fever", "FINAL", true, admitted.plusSeconds(86_400))),
                List.of(),
                List.of(new Prescription(UUID.fromString("00000000-0000-0000-0000-0000000001f1"), DOCTOR,
                        admitted.plusSeconds(86_400 * 4L), "Review in OPD after 1 week", LocalDate.of(2026, 11, 26),
                        List.of(new PrescriptionItem(UUID.fromString("00000000-0000-0000-0000-0000000001f2"), 1,
                                "Tab Paracetamol 500 mg", "1 tab", "ORAL", "SOS", null, 5, null, null)))),
                List.of(new Order(UUID.fromString("00000000-0000-0000-0000-0000000001a9"), "LAB-CBC",
                        "Complete Blood Count", "LABORATORY", "REPORTED", "STAT", null, DOCTOR,
                        admitted.plusSeconds(900), admitted.plusSeconds(7200), "Platelets 48,000/µL; Hct 46%")),
                List.of(),
                List.of(),
                dischargedAt == null ? List.of() : List.of(new Note(UUID.fromString("00000000-0000-0000-0000-0000000001d1"),
                        "DISCHARGE_SUMMARY", "Admitted with dengue fever with warning signs. Managed with IV fluids. "
                        + "Platelets recovered to 1.2 lakh. Discharged stable.", DOCTOR, dischargedAt.minusSeconds(600))),
                doctors());
    }
}
