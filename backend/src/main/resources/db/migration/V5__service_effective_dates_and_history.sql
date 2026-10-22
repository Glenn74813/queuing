-- Effective-dated, versioned service catalog (R-5.2.4).
ALTER TABLE service_item ADD COLUMN effective_to date;
ALTER TABLE service_item ADD CONSTRAINT ck_service_item_effective
    CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from);

-- Full snapshot of every catalog item version, written in the same transaction as the change.
CREATE TABLE service_item_history (
    id               uuid PRIMARY KEY,
    service_item_id  uuid        NOT NULL REFERENCES service_item (id),
    version          int         NOT NULL,
    snapshot         text        NOT NULL,   -- JSON of the item as saved
    changed_by       varchar(60),
    changed_at       timestamptz NOT NULL,
    UNIQUE (service_item_id, version)
);

-- Versions that existed before this migration (seeded catalog = version 1).
INSERT INTO service_item_history (id, service_item_id, version, snapshot, changed_by, changed_at)
SELECT md5('history:' || id || ':' || version)::uuid, id, version,
       json_build_object('code', code, 'name', name, 'classification', classification, 'clientType', client_type,
                         'serviceType', service_type, 'multiStage', multi_stage, 'defaultPrefix', default_prefix,
                         'defaultDurationMinutes', default_duration_minutes, 'active', active,
                         'sortOrder', sort_order, 'effectiveFrom', effective_from)::text,
       'migration', now()
FROM service_item;
