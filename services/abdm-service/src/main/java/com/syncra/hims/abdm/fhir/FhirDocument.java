package com.syncra.hims.abdm.fhir;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import com.syncra.hims.abdm.abha.AbhaNumbers;
import com.syncra.hims.abdm.fhir.EncounterRecord.Practitioner;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

/**
 * One FHIR document under construction: a {@code document} Bundle whose first entry is the
 * Composition, with every other resource referenced by {@code urn:uuid}. Resource ids are
 * derived from the source rows, so the same record always yields the same resources.
 */
final class FhirDocument {

    private final Bundle bundle = new Bundle();
    private final Map<String, Resource> resources = new LinkedHashMap<>();
    private final Composition composition = new Composition();
    private final TimeZone zone;
    private final String identifierSystem;

    FhirDocument(String identifierSystem, ZoneId zone, Instant now, String compositionId) {
        this.identifierSystem = identifierSystem;
        this.zone = TimeZone.getTimeZone(zone);
        bundle.setId(UUID.randomUUID().toString());
        bundle.setMeta(new Meta()
                .setLastUpdatedElement(instant(now))
                .addProfile(Codes.NRCES + "DocumentBundle")
                .addSecurity(new Coding(Codes.CONFIDENTIALITY, "V", "very restricted")));
        bundle.setIdentifier(new Identifier().setSystem(identifierSystem + "/document").setValue(bundle.getId()));
        bundle.setType(Bundle.BundleType.DOCUMENT);
        bundle.setTimestampElement(instant(now));
        composition.setId(compositionId);
        add(composition);
    }

    Composition composition() {
        return composition;
    }

    /** Adds a resource (once per id) and returns a reference to it. */
    Reference add(Resource resource) {
        String key = resource.getResourceType().name() + "/" + resource.getIdElement().getIdPart();
        resources.putIfAbsent(key, resource);
        return reference(resource);
    }

    static Reference reference(Resource resource) {
        return new Reference("urn:uuid:" + resource.getIdElement().getIdPart());
    }

    Bundle bundle() {
        bundle.getEntry().clear();
        resources.values().forEach(r -> bundle.addEntry()
                .setFullUrl("urn:uuid:" + r.getIdElement().getIdPart())
                .setResource(r));
        return bundle;
    }

    // ------------------------------------------------------------------ shared resources

    Reference patient(EncounterRecord.Patient p) {
        Patient patient = new Patient();
        patient.setId(p.id().toString());
        patient.setMeta(new Meta().addProfile(Codes.NRCES + "Patient"));
        patient.addIdentifier()
                .setType(concept(Codes.V2_IDENTIFIER_TYPE, "MR", "Medical record number"))
                .setSystem(identifierSystem + "/uhid").setValue(p.uhid());
        if (p.abhaVerified() && p.abhaNumber() != null) {
            patient.addIdentifier()
                    .setType(concept(Codes.V2_IDENTIFIER_TYPE, "MR", "Medical record number"))
                    .setSystem(Codes.ABHA).setValue(AbhaNumbers.hyphenated(p.abhaNumber()));
        }
        HumanName name = patient.addName().setText(p.fullName());
        if (p.lastName() != null) {
            name.setFamily(p.lastName());
        }
        name.addGiven(p.firstName());
        if (p.middleName() != null) {
            name.addGiven(p.middleName());
        }
        patient.setGender(gender(p.gender()));
        if (p.dateOfBirth() != null) {
            patient.setBirthDateElement(new DateType(p.dateOfBirth().toString()));
        }
        if (p.mobile() != null) {
            patient.addTelecom().setSystem(ContactPoint.ContactPointSystem.PHONE)
                    .setUse(ContactPoint.ContactPointUse.MOBILE).setValue(p.mobile());
        }
        if (p.addressLine() != null || p.cityVillage() != null || p.pincode() != null) {
            Address address = patient.addAddress().setUse(Address.AddressUse.HOME).setCountry("IN");
            if (p.addressLine() != null) {
                address.addLine(p.addressLine());
            }
            address.setCity(p.cityVillage()).setDistrict(p.district()).setState(p.state()).setPostalCode(p.pincode());
        }
        return add(patient);
    }

    Reference practitioner(Practitioner p) {
        org.hl7.fhir.r4.model.Practitioner practitioner = new org.hl7.fhir.r4.model.Practitioner();
        practitioner.setId(p.id().toString());
        practitioner.setMeta(new Meta().addProfile(Codes.NRCES + "Practitioner"));
        if (p.hprId() != null) {
            practitioner.addIdentifier().setType(concept(Codes.V2_IDENTIFIER_TYPE, "MD", "Medical License number"))
                    .setSystem(Codes.HPR).setValue(p.hprId());
        }
        if (p.councilRegistration() != null) {
            practitioner.addIdentifier().setType(concept(Codes.V2_IDENTIFIER_TYPE, "MD", "Medical License number"))
                    .setSystem(identifierSystem + "/council-registration").setValue(p.councilRegistration());
        }
        HumanName name = practitioner.addName().setText(p.fullName());
        if (p.title() != null) {
            name.addPrefix(p.title());
        }
        if (p.qualification() != null) {
            practitioner.addQualification().setCode(new CodeableConcept().setText(p.qualification()));
        }
        return add(practitioner);
    }

    Reference organization(String hipId, String name) {
        Organization organization = new Organization();
        organization.setId(UUID.nameUUIDFromBytes(("organization:" + hipId).getBytes(StandardCharsets.UTF_8)).toString());
        organization.setMeta(new Meta().addProfile(Codes.NRCES + "Organization"));
        organization.addIdentifier().setType(concept(Codes.V2_IDENTIFIER_TYPE, "PRN", "Provider number"))
                .setSystem(Codes.HFR).setValue(hipId);
        organization.setName(name);
        return add(organization);
    }

    Encounter encounter(EncounterRecord record, Reference patient, Reference organization, Reference attending) {
        EncounterRecord.Encounter e = record.encounter();
        Encounter encounter = new Encounter();
        encounter.setId(e.id().toString());
        encounter.setMeta(new Meta().addProfile(Codes.NRCES + "Encounter"));
        encounter.addIdentifier().setSystem(identifierSystem + "/encounter").setValue(e.id().toString());
        if (record.admission() != null) {
            encounter.addIdentifier().setSystem(identifierSystem + "/admission")
                    .setValue(record.admission().admissionNumber());
        }
        encounter.setStatus(switch (e.status()) {
            case "PLANNED" -> Encounter.EncounterStatus.PLANNED;
            case "ARRIVED" -> Encounter.EncounterStatus.ARRIVED;
            case "FINISHED" -> Encounter.EncounterStatus.FINISHED;
            case "CANCELLED" -> Encounter.EncounterStatus.CANCELLED;
            default -> Encounter.EncounterStatus.INPROGRESS;
        });
        encounter.setClass_(switch (e.type()) {
            case "IPD" -> new Coding(Codes.ACT_CODE, "IMP", "inpatient encounter");
            case "EMERGENCY" -> new Coding(Codes.ACT_CODE, "EMER", "emergency");
            case "DAYCARE" -> new Coding(Codes.ACT_CODE, "SS", "short stay");
            default -> new Coding(Codes.ACT_CODE, "AMB", "ambulatory");
        });
        encounter.addType(new CodeableConcept().setText(e.departmentName()));
        encounter.setSubject(patient);
        Period period = new Period().setStartElement(dateTime(e.startedAt()));
        Instant end = record.admission() != null && record.admission().dischargedAt() != null
                ? record.admission().dischargedAt() : e.endedAt();
        if (end != null) {
            period.setEndElement(dateTime(end));
        }
        encounter.setPeriod(period);
        if (attending != null) {
            encounter.addParticipant().setIndividual(attending);
        }
        encounter.setServiceProvider(organization);
        if (record.isDischarged()) {
            encounter.getHospitalization().setDischargeDisposition(switch (record.admission().status()) {
                case "LAMA" -> concept(Codes.DISCHARGE_DISPOSITION, "aadvice", "Left against advice");
                case "EXPIRED" -> concept(Codes.DISCHARGE_DISPOSITION, "exp", "Expired");
                case "TRANSFERRED_OUT" -> concept(Codes.DISCHARGE_DISPOSITION, "other-hcf", "Other healthcare facility");
                case "ABSCONDED" -> concept(Codes.DISCHARGE_DISPOSITION, "oth", "Other");
                default -> concept(Codes.DISCHARGE_DISPOSITION, "home", "Home");
            });
        }
        add(encounter);
        return encounter;
    }

    // ------------------------------------------------------------------ helpers

    void narrative(String title, String subtitle) {
        Narrative narrative = new Narrative().setStatus(Narrative.NarrativeStatus.GENERATED);
        narrative.setDivAsString("<div xmlns=\"http://www.w3.org/1999/xhtml\"><p><b>" + xml(title) + "</b></p><p>"
                + xml(subtitle) + "</p></div>");
        composition.setText(narrative);
    }

    Composition.SectionComponent section(String title, String snomedCode, String display) {
        return composition.addSection().setTitle(title).setCode(concept(Codes.SNOMED, snomedCode, display));
    }

    /** A section known by its title only (the NRCES Wellness, HealthDocument and Invoice records). */
    Composition.SectionComponent section(String title) {
        return composition.addSection().setTitle(title);
    }

    String id(UUID source, String discriminator) {
        return UUID.nameUUIDFromBytes((source + ":" + discriminator).getBytes(StandardCharsets.UTF_8)).toString();
    }

    DateTimeType dateTime(Instant instant) {
        return new DateTimeType(Date.from(instant), TemporalPrecisionEnum.SECOND, zone);
    }

    InstantType instant(Instant instant) {
        return new InstantType(Date.from(instant), TemporalPrecisionEnum.MILLI, zone);
    }

    static CodeableConcept concept(String system, String code, String display) {
        return new CodeableConcept().addCoding(new Coding(system, code, display)).setText(display);
    }

    static AdministrativeGender gender(String code) {
        return switch (code) {
            case "M" -> AdministrativeGender.MALE;
            case "F" -> AdministrativeGender.FEMALE;
            case "O" -> AdministrativeGender.OTHER;
            default -> AdministrativeGender.UNKNOWN;
        };
    }

    static String xml(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
