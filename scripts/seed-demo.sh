#!/bin/sh
# Development only: loads a realistic OPD morning for demos and screenshots, through the API
# gateway with dev tokens (so every rule and audit entry applies as in real use).
#
#   General Medicine queue of six patients; a CKD patient in consultation with an allergy,
#   notes, a critical renal panel and open lab orders; lab samples waiting; a chest X-ray and a
#   CT head in the PACS (the CT without an accession number, so it waits to be matched); the
#   pharmacy stocked from a supplier delivery, with a signed prescription waiting at the counter.
#
# Needs: the stack up, `make seed-dev` done, an empty day (UHIDs are numbered per day).
set -eu
cd "$(dirname "$0")/.."
if grep -q '^SYNCRA_ENVIRONMENT=production' .env 2>/dev/null; then
  echo "refusing: production environment" >&2
  exit 1
fi

G=${SYNCRA_GATEWAY_URL:-http://localhost:8080}
ORTHANC=${SYNCRA_ORTHANC_URL:-http://127.0.0.1:8042}
tok() { python3 scripts/dev-token.py --sub "$1" --roles "$2"; }
RECEPTION=$(tok dev.reception RECEPTIONIST)
DOCTOR=$(tok dev.doctor DOCTOR)
NURSE=$(tok dev.nurse NURSE)
LAB=$(tok dev.lab LAB_TECHNICIAN)
PHARMACIST=$(tok dev.pharmacist PHARMACIST)

field() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }
EMPTY='{}'
post() {
  curl -sS -f -X POST "$G$1" -H "Authorization: Bearer $2" -H 'Content-Type: application/json' -d "${3:-$EMPTY}" \
    || { echo "seed-demo: POST $1 failed" >&2; exit 1; }
}
get() {
  curl -sS -f "$G$1" -H "Authorization: Bearer $2" || { echo "seed-demo: GET $1 failed" >&2; exit 1; }
}
patient() { post "/api/v1/patients?confirmNotDuplicate=true" "$RECEPTION" "$1" | field '["uhid"]'; }
visit() { post /api/v1/opd/visits "$RECEPTION" "{\"uhid\":\"$1\",\"departmentCode\":\"GMED\"}" | field '["encounterId"]'; }
order() { post "/api/v1/emr/encounters/$1/orders" "$DOCTOR" "$2"; }

A=$(patient '{"firstName":"Lakshmi","lastName":"Iyer","gender":"F","dateOfBirth":"1958-07-14","mobile":"9845012345"}'); EA=$(visit "$A")
B=$(patient '{"firstName":"Rohit","lastName":"Verma","gender":"M","dateOfBirth":"1981-02-11","mobile":"9845012346"}')
VISIT_B=$(post /api/v1/opd/visits "$RECEPTION" "{\"uhid\":\"$B\",\"departmentCode\":\"GMED\"}")
EB=$(echo "$VISIT_B" | field '["encounterId"]'); VB=$(echo "$VISIT_B" | field '["visitId"]')
C=$(patient '{"firstName":"Fatima","lastName":"Sheikh","gender":"F","dateOfBirth":"1990-09-23","mobile":"9845012347"}'); EC=$(visit "$C")
D=$(patient '{"firstName":"Suresh","lastName":"Patel","gender":"M","dateOfBirth":"1950-03-02","mobile":"9845012348"}'); ED=$(visit "$D")
E=$(patient '{"firstName":"Arjun","lastName":"Nair","gender":"M","dateOfBirth":"2023-05-19","mobile":"9845012349"}'); visit "$E" >/dev/null
F=$(patient '{"firstName":"Meera","lastName":"Pillai","gender":"F","dateOfBirth":"1996-12-01","mobile":"9845012350"}'); EF=$(visit "$F")

# Lakshmi Iyer: called (senior citizens first) and in consultation.
VA=$(post /api/v1/opd/queue/GMED/call-next "$DOCTOR" | field '["visitId"]')
post "/api/v1/opd/visits/$VA/start" "$DOCTOR" >/dev/null
post "/api/v1/emr/encounters/$EA/diagnoses" "$DOCTOR" '{"description":"Chronic kidney disease, stage 3","icd10Code":"N18.3","type":"PROVISIONAL","primary":true}' >/dev/null
post "/api/v1/emr/patients/$A/allergies" "$NURSE" '{"allergenType":"DRUG","allergenText":"Sulfamethoxazole","drugClass":"Sulfonamides","reaction":"Maculopapular rash","severity":"MODERATE"}' >/dev/null
post "/api/v1/emr/encounters/$EA/notes" "$NURSE" '{"noteType":"NURSING","content":"Ambulatory. Bilateral pedal oedema, pitting to mid-shin. BP 152/94 left arm sitting. Weight 58 kg.","sign":true}' >/dev/null
post "/api/v1/emr/encounters/$EA/notes" "$DOCTOR" '{"noteType":"HISTORY","content":"Known CKD stage 3 for 4 years, hypertensive on ramipril 5 mg OD. Breathless on one flight of stairs for 3 weeks; orthopnoea 2 pillows. Knee pain, taking OTC painkillers.","sign":true}' >/dev/null
RFT=$(order "$EA" '{"serviceCode":"LAB-RFT","priority":"STAT","clinicalIndication":"CKD 3 on ramipril, rising breathlessness"}' | field '["id"]')
post "/api/v1/emr/orders/$RFT/sample" "$NURSE" >/dev/null
post "/api/v1/emr/orders/$RFT/result" "$LAB" '{"resultSummary":"Worsening renal function with hyperkalaemia","values":[{"code":"UREA","name":"Blood urea","value":68},{"code":"CREAT","name":"Serum creatinine","value":2.4},{"code":"SODIUM","name":"Sodium","value":133},{"code":"POTASSIUM","name":"Potassium","value":6.6}]}' >/dev/null
order "$EA" '{"serviceCode":"LAB-CBC","clinicalIndication":"Anaemia of CKD?"}' >/dev/null
LFT=$(order "$EA" '{"serviceCode":"LAB-LFT","priority":"URGENT","clinicalIndication":"Pre-statin baseline"}' | field '["id"]')
post "/api/v1/emr/orders/$LFT/sample" "$NURSE" >/dev/null

# Work waiting at the lab and imaging desks.
order "$EB" '{"serviceCode":"LAB-CBC","clinicalIndication":"Fever 10 days"}' >/dev/null
XACC=$(order "$EB" '{"serviceCode":"RAD-XCH","priority":"URGENT","clinicalIndication":"Fever and cough 10 days, crackles right base"}' | field '["accessionNumber"]')
order "$EC" '{"serviceCode":"RAD-CTH","clinicalIndication":"Headache with vomiting, ?raised ICP"}' >/dev/null
order "$EF" '{"serviceCode":"RAD-USA","clinicalIndication":"Right upper quadrant pain after meals"}' >/dev/null

# Rohit Verma: seen, started on antibiotics, sent for his X-ray (token on hold) via the pharmacy.
post "/api/v1/opd/visits/$VB/call" "$RECEPTION" >/dev/null
post "/api/v1/opd/visits/$VB/start" "$DOCTOR" >/dev/null
post "/api/v1/emr/encounters/$EB/consultation" "$DOCTOR" '{"prescription":{"items":[
  {"drugText":"Taxim-O 200 (Cefixime 200 mg) tablet","dose":"1 tablet","route":"ORAL","frequency":"BD","timing":"AFTER_FOOD","durationDays":5},
  {"drugText":"Dolo 650 (Paracetamol 650 mg) tablet","dose":"1 tablet","route":"ORAL","frequency":"TDS","timing":"AFTER_FOOD","durationDays":3,"instructions":"Only for fever above 100 F"}],
  "advice":"Plenty of oral fluids. Come back at once if breathless, drowsy or not passing urine."},"completeVisit":false}' >/dev/null
post "/api/v1/opd/visits/$VB/hold" "$RECEPTION" >/dev/null

# The pharmacy: a supplier delivery into the main store (one Taxim-O batch close to expiry), the
# OPD counter stocked by an issued requisition, and a second requisition waiting to be issued.
DRUGS=$(get /api/v1/pharmacy/drugs "$PHARMACIST")
RECEIPT=$(DRUGS="$DRUGS" python3 - <<'PY'
import calendar, datetime, json, os, zoneinfo
ids = {d['code']: d['id'] for d in json.loads(os.environ['DRUGS'])}
today = datetime.datetime.now(zoneinfo.ZoneInfo('Asia/Kolkata')).date()
def expiry(months):
    m = today.month - 1 + months
    y, m = today.year + m // 12, m % 12 + 1
    return f'{y}-{m:02d}-{calendar.monthrange(y, m)[1]:02d}'
lines = [  # code, batch, months to expiry, quantity, free, purchase rate and MRP per unit (MRP includes GST)
    ('CFX-200', 'TX5021', 2, 40, 0, 7.80, 10.90),
    ('CFX-200', 'TX6114', 21, 200, 20, 7.95, 10.90),
    ('PCM-650', 'DOL4417', 30, 600, 60, 1.48, 2.24),
    ('AZI-500', 'AZ8830', 18, 100, 0, 16.10, 23.80),
    ('AMX-500', 'MX2702', 15, 150, 10, 6.40, 9.70),
    ('CTZ-10', 'OK1190', 24, 100, 0, 1.10, 1.80),
    ('PAN-40', 'PN7745', 20, 150, 15, 9.20, 14.90),
    ('ORS', 'EL3021', 12, 100, 0, 15.50, 21.00),
]
print(json.dumps({'supplier': 'Sri Balaji Pharma Distributors', 'invoiceNumber': f'SBP/{today:%y%m%d}/0457',
                  'invoiceDate': str(today),
                  'lines': [{'drugId': ids[c], 'batchNumber': b, 'expiryDate': expiry(mo), 'quantity': q, 'freeQuantity': f,
                             'purchaseRate': r, 'mrp': p} for c, b, mo, q, f, r, p in lines]}))
PY
)
post /api/v1/pharmacy/receipts "$PHARMACIST" "$RECEIPT" >/dev/null
requisition() {
  DRUGS="$DRUGS" python3 -c 'import json, os, sys
ids = {d["code"]: d["id"] for d in json.loads(os.environ["DRUGS"])}
print(json.dumps({"requestingStore": "DISPENSARY", "issuingStore": "MAIN",
                  "lines": [{"drugId": ids[a.split("=")[0]], "quantity": float(a.split("=")[1])} for a in sys.argv[1:]]}))' "$@"
}
REQ=$(post /api/v1/pharmacy/requisitions "$PHARMACIST" "$(requisition CFX-200=30 PCM-650=150 AZI-500=15 CTZ-10=30 ORS=20)" | field '["id"]')
post "/api/v1/pharmacy/requisitions/$REQ/issue" "$PHARMACIST" >/dev/null
post /api/v1/pharmacy/requisitions "$PHARMACIST" "$(requisition AMX-500=45 PAN-40=30)" >/dev/null
RBS=$(order "$ED" '{"serviceCode":"LAB-RBS","priority":"STAT","clinicalIndication":"Drowsy, known diabetic"}' | field '["id"]')
post "/api/v1/emr/orders/$RBS/sample" "$NURSE" >/dev/null

# Two studies in the PACS: the X-ray carries its accession number; the CT arrives without one.
OU=$(grep '^ORTHANC_USERNAME=' .env | cut -d= -f2-)
OP=$(grep '^ORTHANC_PASSWORD=' .env | cut -d= -f2-)
dicom() {
  curl -sf -u "$OU:$OP" -X POST "$ORTHANC/tools/create-dicom" -H 'Content-Type: application/json' -d "$1" >/dev/null
}
# A synthetic grayscale picture (no real patient image) so the viewer has pixels to show.
IMAGE=$(python3 - <<'PY'
import base64, math, struct, zlib
W = H = 384
rows = []
for y in range(H):
    row = bytearray([0])
    for x in range(W):
        dx, dy = (x - W / 2) / (W / 2), (y - H / 2) / (H / 2)
        v = 200 - 120 * min(1.0, dx * dx + dy * dy)          # soft vignette
        for cx in (-0.42, 0.42):                             # two darker fields
            if ((dx - cx) / 0.3) ** 2 + ((dy + 0.05) / 0.62) ** 2 < 1:
                v -= 90
        v += 12 * math.sin(y / 9.0) * (abs(dx) > 0.2)        # rib-like banding
        row.append(max(0, min(255, int(v))))
    rows.append(bytes(row))
chunk = lambda t, d: struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
png = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 0, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress(b''.join(rows))) + chunk(b'IEND', b''))
print('data:image/png;base64,' + base64.b64encode(png).decode())
PY
)
dicom "{\"Tags\":{\"PatientID\":\"$B\",\"PatientName\":\"VERMA^ROHIT\",\"AccessionNumber\":\"$XACC\",\"StudyDescription\":\"Chest PA\",\"Modality\":\"DX\"},\"Content\":\"$IMAGE\"}"
dicom "{\"Tags\":{\"PatientID\":\"$C\",\"PatientName\":\"SHEIKH^FATIMA\",\"StudyDescription\":\"CT Brain plain\",\"Modality\":\"CT\"},\"Content\":\"$IMAGE\"}"

echo "Demo day loaded: patients $A to $F in General Medicine, pharmacy stocked, Rohit Verma's prescription at the counter; studies reach the imaging desk in about 90 s."
