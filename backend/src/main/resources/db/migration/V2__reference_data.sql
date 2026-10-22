-- Reference data shared by every node. Central-owned rows use deterministic UUIDs
-- (md5 of a stable key) so the same row has the same id on every site and on central.

-- ---------------------------------------------------------------- roles
INSERT INTO app_role (code, name, scope_type, level) VALUES
    ('SUPER_ADMIN',  'Super Administrator',            'NATIONAL', 100),
    ('CO_ADMIN',     'Central Office Administrator',   'NATIONAL', 90),
    ('SYS_ADMIN',    'System Administrator',           'NATIONAL', 85),
    ('RO_ADMIN',     'Regional Office Administrator',  'REGION',   70),
    ('LHIO_ADMIN',   'LHIO Administrator',             'SITE',     50),
    ('REPORT_AUDIT', 'Reporting and Audit Officer',    'REGION',   40),
    ('TECH_SUPPORT', 'Authorized Technical Support',   'REGION',   40),
    ('SUPERVISOR',   'Service Manager / Supervisor',   'SITE',     30),
    ('AGENT',        'Servicing Agent',                'SITE',     10);
-- REPORT_AUDIT and TECH_SUPPORT are declared REGION-scoped but may also be assigned
-- nationally (no region) or to a single site; the assignment row determines scope.

INSERT INTO role_permission (role_code, permission)
SELECT 'SUPER_ADMIN', p FROM unnest(ARRAY[
    'REGION_MANAGE','SITE_MANAGE','USER_VIEW','USER_MANAGE','CATALOG_MANAGE','SITE_SERVICE_MANAGE',
    'COUNTER_MANAGE','DEVICE_MANAGE','PRIORITY_MANAGE','QUEUE_VIEW','QUEUE_SERVE','QUEUE_SUPERVISE',
    'TICKET_VOID','CONTENT_EDIT','CONTENT_APPROVE','REPORT_VIEW','REPORT_EXPORT','AUDIT_VIEW',
    'SYSTEM_MONITOR','SYNC_MANAGE']) AS p;

INSERT INTO role_permission (role_code, permission)
SELECT 'CO_ADMIN', p FROM unnest(ARRAY[
    'REGION_MANAGE','SITE_MANAGE','USER_VIEW','USER_MANAGE','CATALOG_MANAGE','SITE_SERVICE_MANAGE',
    'COUNTER_MANAGE','DEVICE_MANAGE','PRIORITY_MANAGE','QUEUE_VIEW','QUEUE_SUPERVISE','TICKET_VOID',
    'CONTENT_EDIT','CONTENT_APPROVE','REPORT_VIEW','REPORT_EXPORT','AUDIT_VIEW','SYSTEM_MONITOR']) AS p;

INSERT INTO role_permission (role_code, permission)
SELECT 'SYS_ADMIN', p FROM unnest(ARRAY[
    'USER_VIEW','USER_MANAGE','DEVICE_MANAGE','COUNTER_MANAGE','AUDIT_VIEW','SYSTEM_MONITOR','SYNC_MANAGE']) AS p;

INSERT INTO role_permission (role_code, permission)
SELECT 'RO_ADMIN', p FROM unnest(ARRAY[
    'SITE_MANAGE','USER_VIEW','USER_MANAGE','SITE_SERVICE_MANAGE','COUNTER_MANAGE','DEVICE_MANAGE',
    'QUEUE_VIEW','QUEUE_SUPERVISE','TICKET_VOID','CONTENT_EDIT','CONTENT_APPROVE','REPORT_VIEW',
    'REPORT_EXPORT','AUDIT_VIEW']) AS p;

INSERT INTO role_permission (role_code, permission)
SELECT 'LHIO_ADMIN', p FROM unnest(ARRAY[
    'USER_VIEW','USER_MANAGE','SITE_SERVICE_MANAGE','COUNTER_MANAGE','DEVICE_MANAGE','QUEUE_VIEW',
    'QUEUE_SUPERVISE','TICKET_VOID','CONTENT_EDIT','REPORT_VIEW','REPORT_EXPORT','AUDIT_VIEW']) AS p;

INSERT INTO role_permission (role_code, permission)
SELECT 'REPORT_AUDIT', p FROM unnest(ARRAY['REPORT_VIEW','REPORT_EXPORT','AUDIT_VIEW','QUEUE_VIEW']) AS p;

INSERT INTO role_permission (role_code, permission)
SELECT 'TECH_SUPPORT', p FROM unnest(ARRAY['SYSTEM_MONITOR','DEVICE_MANAGE','QUEUE_VIEW']) AS p;

INSERT INTO role_permission (role_code, permission)
SELECT 'SUPERVISOR', p FROM unnest(ARRAY[
    'QUEUE_VIEW','QUEUE_SERVE','QUEUE_SUPERVISE','TICKET_VOID','REPORT_VIEW','CONTENT_EDIT']) AS p;

INSERT INTO role_permission (role_code, permission)
SELECT 'AGENT', p FROM unnest(ARRAY['QUEUE_SERVE','QUEUE_VIEW']) AS p;

-- ---------------------------------------------------------------- priority categories
-- Lower rank is served first. Regular clients have no category (rank 100).
-- Categories and their eligibility evidence must be confirmed against PhilHealth policy.
INSERT INTO priority_category (id, code, name, description, rank, active) VALUES
    (md5('priority:SENIOR')::uuid,   'SENIOR',   'Senior Citizen',          'Clients aged 60 and above (present ID at the counter).', 10, true),
    (md5('priority:PWD')::uuid,      'PWD',      'Person with Disability',  'Present PWD ID at the counter.',                          10, true),
    (md5('priority:PREGNANT')::uuid, 'PREGNANT', 'Pregnant Client',         'Self-declared; verified at the counter where required.',  10, true);

-- ---------------------------------------------------------------- initial service catalog
-- PROVISIONAL. Names follow the procurement specification. Classification, client type,
-- service type, prefixes and durations are placeholders that MUST be validated against the
-- approved PhilHealth Citizen's Charter before go-live (see docs/open-issues.md, OI-05).
INSERT INTO service_item (id, code, name, classification, client_type, service_type, multi_stage,
                          default_prefix, default_duration_minutes, active, sort_order, description)
SELECT md5('service:' || code)::uuid, code, name, classification, client_type, service_type, multi_stage,
       prefix, duration, active, sort_order,
       'Provisional catalog entry - validate against the approved Citizen''s Charter.'
FROM (VALUES
    ('SVC-01', 'Enrollment of members using PhilHealth Member Registration Form (PMRF)', 'SIMPLE', 'G2C', 'EXTERNAL', false, 'A', 10, true, 1),
    ('SVC-02', 'Enrollment of members using six (6) or more PMRFs', 'COMPLEX', 'G2C', 'EXTERNAL', false, 'B', 30, true, 2),
    ('SVC-03', 'Enrollment and registration of employers', 'SIMPLE', 'G2B', 'EXTERNAL', false, 'C', 15, true, 3),
    ('SVC-04', 'Updating or amendment of employer data', 'SIMPLE', 'G2B', 'EXTERNAL', false, 'D', 15, true, 4),
    ('SVC-05', 'YAKAP registration', 'SIMPLE', 'G2C', 'EXTERNAL', false, 'E', 10, true, 5),
    ('SVC-06', 'Check releasing to members', 'SIMPLE', 'G2C', 'EXTERNAL', false, 'F', 10, true, 6),
    ('SVC-07', 'Check releasing to health facilities', 'SIMPLE', 'G2B', 'EXTERNAL', false, 'G', 10, true, 7),
    ('SVC-08', 'Receiving of directly filed claims', 'SIMPLE', 'G2C', 'EXTERNAL', false, 'H', 15, true, 8),
    ('SVC-09', 'Application for health facility accreditation', 'HIGHLY_TECHNICAL_A', 'G2B', 'EXTERNAL', true, 'J', 30, true, 9),
    ('SVC-10', 'Application for health facility re-accreditation', 'HIGHLY_TECHNICAL_A', 'G2B', 'EXTERNAL', true, 'K', 30, true, 10),
    ('SVC-11', 'Application for renewal / continuing accreditation of health facilities', 'COMPLEX', 'G2B', 'EXTERNAL', true, 'L', 20, true, 11),
    ('SVC-12', 'Application for accreditation of healthcare professionals', 'COMPLEX', 'G2C', 'EXTERNAL', false, 'M', 20, true, 12),
    ('SVC-13', 'Premium contribution payment of direct contributors', 'SIMPLE', 'G2C', 'EXTERNAL', false, 'N', 10, true, 13),
    ('SVC-14', 'Payment for accreditation of healthcare facilities', 'SIMPLE', 'G2B', 'EXTERNAL', false, 'P', 10, true, 14),
    ('SVC-15', 'Payment information adjustment / correction through TDEM', 'COMPLEX', 'G2C', 'EXTERNAL', false, 'Q', 20, true, 15),
    ('SVC-16', 'Employer refund request', 'COMPLEX', 'G2B', 'EXTERNAL', false, 'R', 20, true, 16),
    ('SVC-17', 'Employer request for compromise, waiver or release of interests, surcharges and premium arrears through installment', 'HIGHLY_TECHNICAL_B', 'G2B', 'EXTERNAL', true, 'S', 30, true, 17),
    ('SVC-18', 'Request for exemption from the EPRS Online Payment Facility', 'COMPLEX', 'G2B', 'EXTERNAL', false, 'T', 20, true, 18),
    ('SVC-19', 'Settlement of arrears of delinquent employers with pending billing statement / notice to comply', 'COMPLEX', 'G2B', 'EXTERNAL', false, 'U', 20, true, 19),
    ('SVC-20', 'Settlement of arrears of delinquent employers with cases endorsed to the Legal Unit', 'HIGHLY_TECHNICAL_A', 'G2B', 'EXTERNAL', true, 'V', 30, true, 20),
    ('SVC-21', 'Walk-in client inquiries', 'SIMPLE', 'G2C', 'EXTERNAL', false, 'W', 10, true, 21),
    ('SVC-22', 'Request for records', 'SIMPLE', 'G2C', 'EXTERNAL', false, 'X', 15, true, 22),
    ('SVC-23', 'Member Data Record (MDR) updating through online methods', 'SIMPLE', 'G2C', 'EXTERNAL', false, 'Y', 10, true, 23),
    ('SVC-24', 'MDR updating and declaration of dependents', 'SIMPLE', 'G2C', 'EXTERNAL', false, 'Z', 15, true, 24),
    ('SVC-25', 'Public Assistance Services', 'SIMPLE', 'G2C', 'EXTERNAL', false, 'AA', 10, true, 25),
    ('SVC-26', 'Approval of request for orientation', 'SIMPLE', 'G2G', 'EXTERNAL', false, 'AB', 15, true, 26),
    ('SVC-27', 'Approval / denial of stakeholder requests by authorized Regional Vice Presidents', 'COMPLEX', 'G2G', 'INTERNAL', true, 'AC', 30, true, 27),
    ('SVC-28', 'Generation of Authorization to Transact Code (ATC)', 'SIMPLE', 'G2B', 'EXTERNAL', false, 'AD', 10, true, 28),
    -- Email-received inquiries are tracked but not offered at kiosks by default.
    ('SVC-29', 'Processing of inquiries / complaints received through email by Regional Offices', 'SIMPLE', 'G2C', 'INTERNAL', false, 'AE', 15, false, 29)
) AS s(code, name, classification, client_type, service_type, multi_stage, prefix, duration, active, sort_order);
