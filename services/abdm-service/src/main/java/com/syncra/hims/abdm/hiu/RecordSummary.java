package com.syncra.hims.abdm.hiu;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;
import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CarePlan;
import org.hl7.fhir.r4.model.ChargeItem;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.ImagingStudy;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.ImmunizationRecommendation;
import org.hl7.fhir.r4.model.Invoice;
import org.hl7.fhir.r4.model.Medication;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Procedure;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;

/**
 * A received FHIR document as plain lines a doctor can read: the Composition's sections, each
 * entry summarised (a value with its unit and flag, a medicine with its dosage, a diagnosis…).
 * Any FHIR R4 document from any HIP is accepted; what is not recognised shows as its type. Text
 * attachments are shown; binary ones (PDF, images) are listed, not opened.
 */
public final class RecordSummary {

    public record Section(String title, List<String> lines) {
    }

    public record Summary(String title, String date, String author, String custodian, List<Section> sections) {
    }

    private static final int MAX_TEXT = 4000;

    private final Map<String, Resource> byUrl = new HashMap<>();
    private final String zone;

    private RecordSummary(Bundle bundle, String zone) {
        this.zone = zone;
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (entry.getResource() == null) {
                continue;
            }
            if (entry.hasFullUrl()) {
                byUrl.put(entry.getFullUrl(), entry.getResource());
            }
            Resource r = entry.getResource();
            if (r.hasIdElement() && r.getIdElement().getIdPart() != null) {
                byUrl.put(r.fhirType() + "/" + r.getIdElement().getIdPart(), r);
                byUrl.put("urn:uuid:" + r.getIdElement().getIdPart(), r);
            }
        }
    }

    public static Summary of(Bundle bundle, String zone) {
        return new RecordSummary(bundle, zone).summary(bundle);
    }

    private Summary summary(Bundle bundle) {
        Composition composition = bundle.getEntryFirstRep().getResource() instanceof Composition c ? c : null;
        if (composition == null) {
            List<String> lines = bundle.getEntry().stream().map(Bundle.BundleEntryComponent::getResource)
                    .filter(r -> r != null).flatMap(r -> describe(r).stream()).toList();
            return new Summary("Health record", bundle.hasTimestamp() ? date(bundle.getTimestamp()) : null, null, null,
                    List.of(new Section("Entries", lines)));
        }
        List<Section> sections = new ArrayList<>();
        for (Composition.SectionComponent section : composition.getSection()) {
            List<String> lines = new ArrayList<>();
            for (Reference entry : section.getEntry()) {
                Resource resource = resolve(entry);
                lines.addAll(resource == null ? List.of(display(entry)) : describe(resource));
            }
            if (!lines.isEmpty()) {
                sections.add(new Section(section.hasTitle() ? section.getTitle() : text(section.getCode()), lines));
            }
        }
        String author = composition.getAuthor().stream().map(this::nameOf).filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(", "));
        return new Summary(composition.hasTitle() ? composition.getTitle() : text(composition.getType()),
                composition.hasDate() ? date(composition.getDate()) : null, author.isBlank() ? null : author,
                composition.hasCustodian() ? nameOf(composition.getCustodian()) : null, sections);
    }

    private List<String> describe(Resource resource) {
        return switch (resource) {
            case Observation o -> List.of(observation(o));
            case Condition c -> List.of(join(text(c.getCode()), c.hasClinicalStatus() ? text(c.getClinicalStatus()) : null));
            case MedicationRequest m -> List.of(join(medicine(m.hasMedicationReference() ? m.getMedicationReference() : null,
                    m.hasMedicationCodeableConcept() ? m.getMedicationCodeableConcept() : null),
                    m.getDosageInstruction().stream().map(d -> d.getText()).filter(t -> t != null).collect(Collectors.joining("; "))));
            case MedicationStatement m -> List.of(join(medicine(m.hasMedicationReference() ? m.getMedicationReference() : null,
                    m.hasMedicationCodeableConcept() ? m.getMedicationCodeableConcept() : null),
                    m.getDosage().stream().map(d -> d.getText()).filter(t -> t != null).collect(Collectors.joining("; "))));
            case AllergyIntolerance a -> List.of(join("Allergy: " + text(a.getCode()), a.getReaction().stream()
                    .flatMap(r -> r.getManifestation().stream()).map(RecordSummary::text).collect(Collectors.joining(", "))));
            case DiagnosticReport d -> report(d);
            case ImagingStudy i -> List.of(join("Imaging study", i.hasDescription() ? i.getDescription() : null,
                    i.hasNumberOfInstances() ? i.getNumberOfInstances() + " image(s)" : null));
            case DocumentReference d -> document(d);
            case Immunization i -> List.of(join(text(i.getVaccineCode()),
                    i.hasOccurrenceDateTimeType() ? date(i.getOccurrenceDateTimeType().getValue()) : null,
                    i.getStatus() == null ? null : i.getStatus().toCode().equals("completed") ? null : i.getStatus().getDisplay(),
                    i.getProtocolApplied().isEmpty() || !i.getProtocolAppliedFirstRep().hasDoseNumberPositiveIntType() ? null
                            : "dose " + i.getProtocolAppliedFirstRep().getDoseNumberPositiveIntType().getValue()));
            case ImmunizationRecommendation r -> r.getRecommendation().stream().map(rec -> join("Due: "
                    + rec.getVaccineCode().stream().map(RecordSummary::text).collect(Collectors.joining(", ")),
                    rec.getDateCriterion().isEmpty() ? null : date(rec.getDateCriterionFirstRep().getValue()))).toList();
            case ServiceRequest s -> List.of(join("Ordered: " + text(s.getCode()),
                    s.getStatus() == null ? null : s.getStatus().getDisplay()));
            case Procedure p -> List.of(join("Procedure: " + text(p.getCode()),
                    p.hasPerformedDateTimeType() ? date(p.getPerformedDateTimeType().getValue()) : null));
            case CarePlan c -> List.of(join(c.hasTitle() ? c.getTitle() : "Care plan", c.getDescription()));
            case Appointment a -> List.of(join(a.hasDescription() ? a.getDescription() : "Appointment",
                    a.hasStart() ? date(a.getStart()) : null));
            case Invoice i -> invoice(i);
            default -> List.of(resource.fhirType());
        };
    }

    private String observation(Observation o) {
        String value;
        if (o.hasValueQuantity()) {
            value = quantity(o.getValueQuantity());
        } else if (o.hasValueStringType()) {
            value = o.getValueStringType().getValue();
        } else if (o.hasValueCodeableConcept()) {
            value = text(o.getValueCodeableConcept());
        } else if (o.hasComponent()) {
            value = o.getComponent().stream().filter(c -> c.hasValueQuantity())
                    .map(c -> c.getValueQuantity().getValue().stripTrailingZeros().toPlainString())
                    .collect(Collectors.joining("/"));
            String unit = o.getComponentFirstRep().hasValueQuantity() ? o.getComponentFirstRep().getValueQuantity().getUnit() : null;
            value = unit == null ? value : value + " " + unit;
        } else {
            value = null;
        }
        String flag = o.getInterpretation().stream().map(c -> c.getCodingFirstRep().getCode())
                .filter(code -> code != null && !"N".equals(code)).findFirst().orElse(null);
        String range = o.getReferenceRange().isEmpty() ? null : range(o.getReferenceRangeFirstRep());
        return join(text(o.getCode()) + (value == null ? "" : ": " + value + (flag == null ? "" : " (" + flag + ")")),
                range == null ? null : "ref " + range,
                o.hasEffectiveDateTimeType() ? date(o.getEffectiveDateTimeType().getValue()) : null);
    }

    private List<String> report(DiagnosticReport d) {
        List<String> lines = new ArrayList<>();
        lines.add(join(text(d.getCode()), d.hasIssued() ? date(d.getIssued()) : null, d.getConclusion()));
        for (Reference result : d.getResult()) {
            Resource r = resolve(result);
            lines.add("  " + (r instanceof Observation o ? observation(o) : display(result)));
        }
        for (Attachment form : d.getPresentedForm()) {
            lines.add("  " + attachment(form));
        }
        return lines;
    }

    private List<String> document(DocumentReference d) {
        List<String> lines = new ArrayList<>();
        String title = d.hasDescription() ? d.getDescription() : text(d.getType());
        lines.add(join(title, d.hasDate() ? date(d.getDate()) : null));
        d.getContent().forEach(c -> lines.add("  " + attachment(c.getAttachment())));
        return lines;
    }

    private List<String> invoice(Invoice i) {
        List<String> lines = new ArrayList<>();
        lines.add(join("Invoice " + (i.hasIdentifier() ? i.getIdentifierFirstRep().getValue() : ""),
                i.hasDate() ? date(i.getDate()) : null,
                i.hasTotalGross() ? "total ₹" + i.getTotalGross().getValue().toPlainString() : null));
        for (Invoice.InvoiceLineItemComponent item : i.getLineItem()) {
            Resource charge = item.hasChargeItemReference() ? resolve(item.getChargeItemReference()) : null;
            String what = charge instanceof ChargeItem c ? join(text(c.getCode()),
                    c.hasQuantity() ? "× " + quantity(c.getQuantity()) : null)
                    : item.hasChargeItemCodeableConcept() ? text(item.getChargeItemCodeableConcept()) : "Item";
            String amount = item.getPriceComponent().stream()
                    .filter(pc -> pc.getType() == Invoice.InvoicePriceComponentType.BASE && pc.hasAmount())
                    .map(pc -> "₹" + pc.getAmount().getValue().toPlainString()).findFirst().orElse(null);
            lines.add("  " + join(what, amount));
        }
        return lines;
    }

    private String attachment(Attachment a) {
        String title = a.hasTitle() ? a.getTitle() : "Attachment";
        String type = a.hasContentType() ? a.getContentType() : "";
        if (a.hasData() && type.startsWith("text/")) {
            String text = new String(a.getData(), StandardCharsets.UTF_8);
            return title + ": " + (text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text);
        }
        return title + " (" + (type.isBlank() ? "file" : type) + (a.hasUrl() ? ", " + a.getUrl() : "") + ")";
    }

    private String medicine(Reference reference, CodeableConcept concept) {
        if (concept != null) {
            return text(concept);
        }
        Resource r = reference == null ? null : resolve(reference);
        return r instanceof Medication m ? text(m.getCode()) : reference == null ? "Medicine" : display(reference);
    }

    private Resource resolve(Reference reference) {
        return reference == null || !reference.hasReference() ? null : byUrl.get(reference.getReference());
    }

    private String nameOf(Reference reference) {
        Resource r = resolve(reference);
        if (r instanceof org.hl7.fhir.r4.model.Practitioner p && p.hasName()) {
            return p.getNameFirstRep().getNameAsSingleString();
        }
        if (r instanceof org.hl7.fhir.r4.model.Organization o && o.hasName()) {
            return o.getName();
        }
        return reference.hasDisplay() ? reference.getDisplay() : null;
    }

    private static String display(Reference reference) {
        return reference.hasDisplay() ? reference.getDisplay() : "(not included)";
    }

    private static String range(Observation.ObservationReferenceRangeComponent range) {
        if (range.hasText()) {
            return range.getText();
        }
        String low = range.hasLow() ? range.getLow().getValue().stripTrailingZeros().toPlainString() : null;
        String high = range.hasHigh() ? range.getHigh().getValue().stripTrailingZeros().toPlainString() : null;
        return low != null && high != null ? low + "–" + high : low != null ? "≥ " + low : high != null ? "≤ " + high : null;
    }

    private static String quantity(Quantity q) {
        String value = q.hasValue() ? q.getValue().stripTrailingZeros().toPlainString() : "";
        String unit = q.hasUnit() ? q.getUnit() : q.hasCode() ? q.getCode() : "";
        return (value + " " + unit).trim();
    }

    static String text(CodeableConcept concept) {
        if (concept == null) {
            return "";
        }
        if (concept.hasText()) {
            return concept.getText();
        }
        return concept.getCoding().stream().map(c -> c.hasDisplay() ? c.getDisplay() : c.getCode())
                .filter(s -> s != null).findFirst().orElse("");
    }

    private static String join(String first, String... rest) {
        Set<String> parts = new LinkedHashSet<>();
        if (first != null && !first.isBlank()) {
            parts.add(first.trim());
        }
        for (String part : rest) {
            if (part != null && !part.isBlank()) {
                parts.add(part.trim());
            }
        }
        return String.join(" · ", parts);
    }

    private String date(Date value) {
        if (value == null) {
            return null;
        }
        SimpleDateFormat format = new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ENGLISH);
        format.setTimeZone(TimeZone.getTimeZone(zone));
        return format.format(value);
    }
}
