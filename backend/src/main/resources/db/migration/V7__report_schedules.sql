-- Scheduled report generation (R-8.3.2). Files are stored on the node's disk (QMS_REPORTS_DIR).
CREATE TABLE report_schedule (
    id           uuid PRIMARY KEY,
    name         varchar(120) NOT NULL,
    frequency    varchar(10)  NOT NULL CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    group_by     varchar(20)  NOT NULL,
    format       varchar(5)   NOT NULL CHECK (format IN ('CSV', 'XLSX', 'PDF')),
    site_id      uuid REFERENCES site (id),
    region_id    uuid REFERENCES region (id),
    active       boolean      NOT NULL DEFAULT true,
    created_by   varchar(60)  NOT NULL,
    next_run_at  timestamptz  NOT NULL,
    last_run_at  timestamptz,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CHECK (site_id IS NULL OR region_id IS NULL)
);
CREATE INDEX ix_report_schedule_due ON report_schedule (next_run_at) WHERE active;

CREATE TABLE report_run (
    id            uuid PRIMARY KEY,
    schedule_id   uuid        NOT NULL REFERENCES report_schedule (id),
    period_from   date        NOT NULL,
    period_to     date        NOT NULL,
    trigger       varchar(10) NOT NULL CHECK (trigger IN ('SCHEDULED', 'MANUAL')),
    status        varchar(10) NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    file_name     varchar(100),
    size_bytes    bigint,
    sha256        varchar(64),
    error         varchar(1000),
    generated_at  timestamptz NOT NULL
);
CREATE INDEX ix_report_run_schedule ON report_run (schedule_id, generated_at DESC);
