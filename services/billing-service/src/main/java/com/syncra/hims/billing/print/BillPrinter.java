package com.syncra.hims.billing.print;

import com.syncra.hims.billing.BillingProperties;
import com.syncra.hims.billing.api.BillView;
import com.syncra.hims.common.config.HospitalTime;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Print-ready HTML for a bill (A4/A5, browser print). Every value is HTML-escaped.
 * Non-final bills carry a watermark so they cannot pass for an issued bill.
 */
@Component
public class BillPrinter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);

    private final BillingProperties properties;
    private final HospitalTime time;

    public BillPrinter(BillingProperties properties, HospitalTime time) {
        this.properties = properties;
        this.time = time;
    }

    public String render(BillView bill) {
        BillingProperties.Hospital h = properties.hospital();
        StringBuilder html = new StringBuilder(8_192);
        html.append("""
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>""").append(e(bill.billNumber() == null ? bill.title() : bill.billNumber()))
                .append("""
                </title><style>
                  @page { size: A4; margin: 12mm; }
                  body { font: 12px/1.35 "Segoe UI", Arial, sans-serif; color: #111; margin: 0; }
                  .head { display: flex; justify-content: space-between; border-bottom: 2px solid #111; padding-bottom: 6px; }
                  .head h1 { font-size: 18px; margin: 0; } .muted { color: #555; } .right { text-align: right; }
                  h2 { font-size: 14px; text-align: center; letter-spacing: .05em; margin: 10px 0 6px; text-transform: uppercase; }
                  table { width: 100%; border-collapse: collapse; margin-top: 6px; }
                  th, td { border: 1px solid #999; padding: 3px 5px; vertical-align: top; }
                  th { background: #eee; font-size: 11px; text-align: left; } td.n, th.n { text-align: right; white-space: nowrap; }
                  .meta td { border: none; padding: 1px 4px; } .totals { width: 45%; margin-left: auto; }
                  .totals td { border: none; padding: 1px 4px; } .grand td { font-weight: bold; border-top: 1px solid #111; }
                  .watermark { position: fixed; top: 40%; left: 0; right: 0; text-align: center; font-size: 64px;
                               color: rgba(200,0,0,.12); transform: rotate(-25deg); pointer-events: none; }
                  .foot { margin-top: 14px; font-size: 10px; color: #555; }
                </style></head><body>
                """);
        if (!"FINAL".equals(bill.status())) {
            html.append("<div class=\"watermark\">").append(e(bill.status())).append("</div>");
        }
        html.append("<div class=\"head\"><div><h1>").append(e(h.name())).append("</h1>");
        line(html, h.address());
        if (h.phone() != null && !h.phone().isBlank()) {
            line(html, "Phone: " + h.phone());
        }
        html.append("</div><div class=\"right\">");
        if (h.gstin() != null && !h.gstin().isBlank()) {
            line(html, "GSTIN: " + h.gstin());
        }
        line(html, "Bill no: " + (bill.billNumber() == null ? "—" : bill.billNumber()));
        line(html, "Date: " + date(bill.finalizedAt() != null ? bill.finalizedAt() : bill.createdAt()));
        html.append("</div></div><h2>").append(e(bill.title())).append("</h2>");

        BillView.Patient p = bill.patient();
        html.append("<table class=\"meta\"><tr><td><b>Patient:</b> ").append(e(p.name()))
                .append("</td><td><b>UHID:</b> ").append(e(p.uhid())).append("</td><td><b>Age/Sex:</b> ")
                .append(p.ageYears() == null ? "—" : p.ageYears()).append(" / ").append(e(p.gender())).append("</td></tr>")
                .append("<tr><td><b>Bill type:</b> ").append(e(bill.billType())).append("</td><td><b>Tariff:</b> ")
                .append(e(bill.tariffPlan())).append("</td><td>");
        if (bill.admissionNumber() != null) {
            html.append("<b>Admission:</b> ").append(e(bill.admissionNumber()));
        }
        html.append("</td></tr>");
        if (p.address() != null) {
            html.append("<tr><td colspan=\"3\"><b>Address:</b> ").append(e(p.address())).append("</td></tr>");
        }
        html.append("</table>");

        html.append("<table><thead><tr><th>#</th><th>Description</th><th>SAC</th><th>Date</th><th class=\"n\">Qty</th>")
                .append("<th class=\"n\">Rate</th><th class=\"n\">Amount</th><th class=\"n\">Disc.</th>")
                .append("<th class=\"n\">GST %</th><th class=\"n\">GST</th><th class=\"n\">Total</th></tr></thead><tbody>");
        int index = 1;
        for (BillView.Line l : bill.lines()) {
            html.append("<tr><td>").append(index++).append("</td><td>").append(e(l.description()))
                    .append("</td><td>").append(e(l.sacCode())).append("</td><td>").append(l.serviceDate().format(DATE))
                    .append("</td><td class=\"n\">").append(qty(l.quantity())).append("</td><td class=\"n\">")
                    .append(money(l.unitPrice())).append("</td><td class=\"n\">").append(money(l.grossAmount()))
                    .append("</td><td class=\"n\">").append(money(l.discountAmount())).append("</td><td class=\"n\">")
                    .append(qty(l.gstRate())).append("</td><td class=\"n\">").append(money(l.taxAmount()))
                    .append("</td><td class=\"n\">").append(money(l.lineTotal())).append("</td></tr>");
        }
        html.append("</tbody></table>");

        BillView.Totals t = bill.totals();
        html.append("<table class=\"totals\">");
        total(html, "Gross", t.gross());
        total(html, "Discount", t.discount().negate());
        total(html, "GST", t.tax());
        total(html, "Round off", t.roundOff());
        html.append("<tr class=\"grand\"><td>Amount payable</td><td class=\"n\">Rs ").append(money(t.payable())).append("</td></tr>");
        total(html, "Received", t.received());
        if (t.refunded().signum() > 0) {
            total(html, "Refunded", t.refunded());
        }
        html.append("<tr class=\"grand\"><td>").append(t.balanceDue().signum() < 0 ? "Refund due" : "Balance due")
                .append("</td><td class=\"n\">Rs ").append(money(t.balanceDue().abs())).append("</td></tr></table>");
        html.append("<p><b>In words:</b> ").append(e(AmountInWords.rupees(t.payable()))).append("</p>");

        if (!bill.receipts().isEmpty()) {
            html.append("<table><thead><tr><th>Receipt</th><th>Type</th><th>Mode</th><th>Reference</th><th>Date</th>")
                    .append("<th class=\"n\">Amount</th></tr></thead><tbody>");
            for (BillView.Receipt r : bill.receipts()) {
                html.append("<tr><td>").append(e(r.receiptNumber())).append("VOIDED".equals(r.status()) ? " (void)" : "")
                        .append("</td><td>").append(e(r.type())).append("</td><td>").append(e(r.mode()))
                        .append("</td><td>").append(e(r.reference())).append("</td><td>")
                        .append(dateTime(r.receivedAt())).append("</td><td class=\"n\">").append(money(r.amount()))
                        .append("</td></tr>");
            }
            html.append("</tbody></table>");
        }

        html.append("<div class=\"foot\">Health care services by a clinical establishment are exempt from GST; GST is ")
                .append("charged only where shown. Computer-generated bill");
        if (bill.finalizedBy() != null) {
            html.append(", issued by ").append(e(bill.finalizedBy()));
        }
        html.append(".</div></body></html>");
        return html.toString();
    }

    private static void line(StringBuilder html, String text) {
        if (text != null && !text.isBlank()) {
            html.append("<div class=\"muted\">").append(e(text)).append("</div>");
        }
    }

    private static void total(StringBuilder html, String label, BigDecimal value) {
        html.append("<tr><td>").append(label).append("</td><td class=\"n\">").append(money(value)).append("</td></tr>");
    }

    private String date(Instant instant) {
        return instant == null ? "—" : instant.atZone(time.zone()).format(DATE);
    }

    private String dateTime(Instant instant) {
        return instant == null ? "—" : instant.atZone(time.zone()).format(DATE_TIME);
    }

    private static String money(BigDecimal value) {
        return value == null ? "" : AmountInWords.grouped(value);
    }

    private static String qty(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String e(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value, "UTF-8");
    }
}
