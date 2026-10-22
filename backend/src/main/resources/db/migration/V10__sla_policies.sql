-- Service level agreements per region (agent performance is measured against these).
-- region_id NULL = the national default, used for regions without their own policy.
CREATE TABLE sla_policy (
    id                         uuid PRIMARY KEY,
    region_id                  uuid REFERENCES region (id),
    waiting_target_minutes     int          NOT NULL CHECK (waiting_target_minutes BETWEEN 1 AND 1440),
    processing_target_minutes  int          NOT NULL CHECK (processing_target_minutes BETWEEN 1 AND 1440),
    compliance_target_percent  numeric(5,2) NOT NULL DEFAULT 90 CHECK (compliance_target_percent BETWEEN 1 AND 100),
    version                    int          NOT NULL DEFAULT 1,
    updated_by                 varchar(60),
    created_at                 timestamptz  NOT NULL DEFAULT now(),
    updated_at                 timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_sla_policy_region ON sla_policy (region_id) WHERE region_id IS NOT NULL;
CREATE UNIQUE INDEX ux_sla_policy_national ON sla_policy ((region_id IS NULL)) WHERE region_id IS NULL;

-- Per-service overrides within a policy (e.g. longer processing for accreditation applications).
CREATE TABLE sla_service_target (
    id                         uuid PRIMARY KEY,
    policy_id                  uuid NOT NULL REFERENCES sla_policy (id) ON DELETE CASCADE,
    service_item_id            uuid NOT NULL REFERENCES service_item (id),
    waiting_target_minutes     int CHECK (waiting_target_minutes BETWEEN 1 AND 1440),
    processing_target_minutes  int CHECK (processing_target_minutes BETWEEN 1 AND 1440),
    UNIQUE (policy_id, service_item_id),
    CHECK (waiting_target_minutes IS NOT NULL OR processing_target_minutes IS NOT NULL)
);

-- New permission for maintaining SLA targets.
INSERT INTO role_permission (role_code, permission) VALUES
    ('SUPER_ADMIN', 'SLA_MANAGE'), ('CO_ADMIN', 'SLA_MANAGE'), ('RO_ADMIN', 'SLA_MANAGE');

-- A starting national default so every region has targets; tune per region in the SLA admin page.
INSERT INTO sla_policy (id, region_id, waiting_target_minutes, processing_target_minutes, compliance_target_percent,
                        updated_by)
VALUES (md5('sla:national')::uuid, NULL, 30, 15, 90, 'migration');
