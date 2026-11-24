-- =============================================================================
--  DEVELOPMENT ONLY — sample staff whose login subjects match scripts/dev-token.py
--  (e.g. `python3 scripts/dev-token.py --sub dev.reception --roles RECEPTIONIST`).
--  Real staff come from HR/IAM. Idempotent. Run with: make seed-dev
-- =============================================================================
INSERT INTO core.staff (employee_code, iam_subject, title, full_name, staff_role, department_id, council_registration)
SELECT s.code, s.subject, s.title, s.name, s.role, d.id, s.registration
FROM (VALUES
    ('DEV-001', 'dev.doctor',    'Dr',  'Anil Mehta',     'DOCTOR',        'GMED', 'DEV/MMC/1001'),
    ('DEV-002', 'dev.surgeon',   'Dr',  'Priya Nair',     'DOCTOR',        'GSUR', 'DEV/MMC/1002'),
    ('DEV-003', 'dev.paeds',     'Dr',  'Farhan Qureshi', 'DOCTOR',        'PAED', 'DEV/MMC/1003'),
    ('DEV-004', 'dev.obgy',      'Dr',  'Lakshmi Rao',    'DOCTOR',        'OBGY', 'DEV/MMC/1004'),
    ('DEV-010', 'dev.nurse',     NULL,  'Sunita Pawar',   'NURSE',         'GMED', NULL),
    ('DEV-020', 'dev.reception', NULL,  'Rahul Verma',    'RECEPTIONIST',  NULL,   NULL),
    ('DEV-030', 'dev.billing',   NULL,  'Meera Joshi',    'BILLING_CLERK', NULL,   NULL),
    ('DEV-040', 'dev.lab',       NULL,  'Vikram Singh',   'LAB_TECHNICIAN', 'PATH', NULL),
    ('DEV-041', 'dev.radiographer', NULL, 'Asha Kulkarni', 'RADIOGRAPHER', 'RADI', NULL),
    ('DEV-042', 'dev.radiologist', 'Dr', 'Kavita Rao',    'RADIOLOGIST',   'RADI', 'DEV/MMC/1042')
) AS s (code, subject, title, name, role, dept, registration)
LEFT JOIN core.department AS d ON d.code = s.dept
ON CONFLICT (employee_code) DO NOTHING;

-- Sign-in accounts for the staff above (username = their token subject), plus an administrator.
-- DEVELOPMENT PASSWORD for all of them: Syncra-dev-2026 (never use these accounts on a hospital server).
INSERT INTO iam.user_account (username, password_hash, display_name, staff_id, roles, must_change_password, created_by)
SELECT st.iam_subject, '$2y$12$3nK08fuH2oFk2GV3b2Z3f.3OSryGgwIeF7Mb2pp7w5ylItTwVda1C',
       concat_ws(' ', st.title, st.full_name), st.id, ARRAY[st.staff_role], false, 'seed-dev'
FROM core.staff st
WHERE st.employee_code LIKE 'DEV-%' AND st.iam_subject IS NOT NULL
ON CONFLICT (username) DO NOTHING;

INSERT INTO iam.user_account (username, password_hash, display_name, roles, must_change_password, created_by)
VALUES ('dev.admin', '$2y$12$3nK08fuH2oFk2GV3b2Z3f.3OSryGgwIeF7Mb2pp7w5ylItTwVda1C', 'Development Administrator',
        ARRAY['ADMINISTRATOR'], false, 'seed-dev')
ON CONFLICT (username) DO NOTHING;

-- A pharmacist (development), with a sign-in account like the others.
INSERT INTO core.staff (iam_subject, employee_code, full_name, staff_role)
VALUES ('dev.pharmacist', 'DEV-050', 'Nandini Shetty', 'PHARMACIST')
ON CONFLICT (employee_code) DO NOTHING;
INSERT INTO iam.user_account (username, password_hash, display_name, staff_id, roles, must_change_password, created_by)
SELECT 'dev.pharmacist', '$2y$12$3nK08fuH2oFk2GV3b2Z3f.3OSryGgwIeF7Mb2pp7w5ylItTwVda1C', full_name, id, ARRAY['PHARMACIST'], false, 'seed-dev'
FROM core.staff WHERE employee_code = 'DEV-050'
ON CONFLICT (username) DO NOTHING;
