-- PhilHealth QMS core schema.
-- Conventions: UUID primary keys (globally unique across sites so records can be
-- synchronized without renumbering), timestamptz in UTC, business_date in site time zone.

-- ---------------------------------------------------------------- organization
CREATE TABLE region (
    id          uuid PRIMARY KEY,
    code        varchar(20)  NOT NULL UNIQUE,
    name        varchar(150) NOT NULL,
    active      boolean      NOT NULL DEFAULT true,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE site (
    id          uuid PRIMARY KEY,
    code        varchar(20)  NOT NULL UNIQUE,
    name        varchar(150) NOT NULL,
    type        varchar(20)  NOT NULL CHECK (type IN ('CENTRAL_OFFICE', 'REGIONAL_OFFICE', 'LHIO')),
    region_id   uuid         NOT NULL REFERENCES region (id),
    area        varchar(100),
    timezone    varchar(50)  NOT NULL DEFAULT 'Asia/Manila',
    active      boolean      NOT NULL DEFAULT true,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_site_region ON site (region_id);

-- ---------------------------------------------------------------- identity & access
CREATE TABLE app_role (
    code        varchar(30)  PRIMARY KEY,
    name        varchar(100) NOT NULL,
    scope_type  varchar(10)  NOT NULL CHECK (scope_type IN ('NATIONAL', 'REGION', 'SITE')),
    level       int          NOT NULL   -- higher level may administer lower levels
);

CREATE TABLE role_permission (
    role_code   varchar(30) NOT NULL REFERENCES app_role (code) ON DELETE CASCADE,
    permission  varchar(40) NOT NULL,
    PRIMARY KEY (role_code, permission)
);

CREATE TABLE app_user (
    id                    uuid PRIMARY KEY,
    username              varchar(60)  NOT NULL,
    full_name             varchar(150) NOT NULL,
    email                 varchar(150),
    password_hash         varchar(100) NOT NULL,
    status                varchar(15)  NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    must_change_password  boolean      NOT NULL DEFAULT true,
    failed_attempts       int          NOT NULL DEFAULT 0,
    locked_until          timestamptz,
    last_login_at         timestamptz,
    password_changed_at   timestamptz,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    version               bigint       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_app_user_username ON app_user (lower(username));

CREATE TABLE user_role_assignment (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_code  varchar(30) NOT NULL REFERENCES app_role (code),
    region_id  uuid REFERENCES region (id),
    site_id    uuid REFERENCES site (id)
);
CREATE INDEX ix_ura_user ON user_role_assignment (user_id);

CREATE TABLE refresh_token (
    id          uuid PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash  varchar(64) NOT NULL UNIQUE,      -- SHA-256 hex; raw token never stored
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_token_user ON refresh_token (user_id);

-- ---------------------------------------------------------------- service catalog
CREATE TABLE service_item (
    id                        uuid PRIMARY KEY,
    code                      varchar(30)  NOT NULL UNIQUE,
    name                      varchar(250) NOT NULL,
    description               text,
    classification            varchar(25)  NOT NULL
        CHECK (classification IN ('SIMPLE', 'COMPLEX', 'HIGHLY_TECHNICAL_A', 'HIGHLY_TECHNICAL_B', 'OTHER')),
    client_type               varchar(5)   NOT NULL CHECK (client_type IN ('G2C', 'G2B', 'G2G')),
    service_type              varchar(10)  NOT NULL CHECK (service_type IN ('INTERNAL', 'EXTERNAL')),
    multi_stage               boolean      NOT NULL DEFAULT false,
    default_prefix            varchar(4)   NOT NULL,
    default_duration_minutes  int          NOT NULL DEFAULT 10 CHECK (default_duration_minutes > 0),
    required_documents        text,
    eligibility_notes         text,
    active                    boolean      NOT NULL DEFAULT true,
    sort_order                int          NOT NULL DEFAULT 0,
    version                   int          NOT NULL DEFAULT 1,
    effective_from            date,
    created_at                timestamptz  NOT NULL DEFAULT now(),
    updated_at                timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE site_service (
    id                          uuid PRIMARY KEY,
    site_id                     uuid        NOT NULL REFERENCES site (id),
    service_item_id             uuid        NOT NULL REFERENCES service_item (id),
    prefix                      varchar(4)  NOT NULL CHECK (prefix ~ '^[A-Z]{1,3}$'),
    active                      boolean     NOT NULL DEFAULT true,
    sort_order                  int         NOT NULL DEFAULT 0,
    estimated_duration_minutes  int         NOT NULL DEFAULT 10 CHECK (estimated_duration_minutes > 0),
    open_time                   time,
    close_time                  time,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, service_item_id),
    UNIQUE (site_id, prefix)
);

CREATE TABLE priority_category (
    id           uuid PRIMARY KEY,
    code         varchar(30)  NOT NULL UNIQUE,
    name         varchar(100) NOT NULL,
    description  text,
    rank         int          NOT NULL CHECK (rank >= 0),   -- lower rank is served first; regular = 100
    active       boolean      NOT NULL DEFAULT true,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- counters & devices
CREATE TABLE service_counter (
    id          uuid PRIMARY KEY,
    site_id     uuid        NOT NULL REFERENCES site (id),
    code        varchar(20) NOT NULL,
    name        varchar(60) NOT NULL,
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, code)
);

CREATE TABLE counter_service (
    counter_id       uuid NOT NULL REFERENCES service_counter (id) ON DELETE CASCADE,
    site_service_id  uuid NOT NULL REFERENCES site_service (id) ON DELETE CASCADE,
    PRIMARY KEY (counter_id, site_service_id)
);

CREATE TABLE device (
    id            uuid PRIMARY KEY,
    site_id       uuid        NOT NULL REFERENCES site (id),
    code          varchar(30) NOT NULL,
    name          varchar(100) NOT NULL,
    type          varchar(10) NOT NULL CHECK (type IN ('KIOSK', 'DISPLAY')),
    api_key_hash  varchar(64) NOT NULL UNIQUE,
    active        boolean     NOT NULL DEFAULT true,
    last_seen_at  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, code)
);

CREATE TABLE agent_session (
    id                uuid PRIMARY KEY,
    user_id           uuid        NOT NULL REFERENCES app_user (id),
    counter_id        uuid        NOT NULL REFERENCES service_counter (id),
    site_id           uuid        NOT NULL REFERENCES site (id),
    status            varchar(10) NOT NULL CHECK (status IN ('AVAILABLE', 'ON_BREAK', 'ENDED')),
    started_at        timestamptz NOT NULL,
    ended_at          timestamptz,
    break_started_at  timestamptz,
    break_reason      varchar(200),
    version           bigint      NOT NULL DEFAULT 0
);
-- One open session per counter and per agent; enforced by the database, not only by code.
CREATE UNIQUE INDEX ux_agent_session_counter_open ON agent_session (counter_id) WHERE status <> 'ENDED';
CREATE UNIQUE INDEX ux_agent_session_user_open ON agent_session (user_id) WHERE status <> 'ENDED';

CREATE TABLE agent_break (
    id          uuid PRIMARY KEY,
    session_id  uuid        NOT NULL REFERENCES agent_session (id),
    started_at  timestamptz NOT NULL,
    ended_at    timestamptz,
    reason      varchar(200)
);

-- ---------------------------------------------------------------- tickets
CREATE TABLE ticket_sequence (
    site_id        uuid        NOT NULL REFERENCES site (id),
    prefix         varchar(5)  NOT NULL,
    business_date  date        NOT NULL,
    last_value     int         NOT NULL,
    PRIMARY KEY (site_id, prefix, business_date)
);

CREATE TABLE ticket (
    id                        uuid PRIMARY KEY,
    site_id                   uuid        NOT NULL REFERENCES site (id),
    site_service_id           uuid        NOT NULL REFERENCES site_service (id),
    original_site_service_id  uuid        NOT NULL REFERENCES site_service (id),
    business_date             date        NOT NULL,
    prefix                    varchar(5)  NOT NULL,
    seq_no                    int         NOT NULL,
    display_number            varchar(12) NOT NULL,
    priority_category_id      uuid REFERENCES priority_category (id),
    priority_rank             int         NOT NULL DEFAULT 100,
    channel                   varchar(12) NOT NULL CHECK (channel IN ('PRINTED', 'ELECTRONIC')),
    device_id                 uuid REFERENCES device (id),
    status                    varchar(10) NOT NULL
        CHECK (status IN ('WAITING', 'CALLED', 'ON_HOLD', 'COMPLETED', 'REMOVED', 'VOIDED', 'EXPIRED')),
    stage_no                  int         NOT NULL DEFAULT 1,
    counter_id                uuid REFERENCES service_counter (id),
    agent_user_id             uuid,                 -- no FK: users are node-local, tickets replicate centrally
    agent_name                varchar(150),
    issued_at                 timestamptz NOT NULL,
    queued_at                 timestamptz NOT NULL, -- entered the current waiting line (resets on transfer)
    first_called_at           timestamptz,
    last_called_at            timestamptz,
    completed_at              timestamptz,
    status_changed_at         timestamptz NOT NULL,
    recall_count              int         NOT NULL DEFAULT 0,
    transfer_count            int         NOT NULL DEFAULT 0,
    wait_seconds              bigint      NOT NULL DEFAULT 0,
    service_seconds           bigint      NOT NULL DEFAULT 0,
    hold_seconds              bigint      NOT NULL DEFAULT 0,
    request_id                uuid UNIQUE,          -- kiosk idempotency key
    version                   bigint      NOT NULL DEFAULT 0,
    UNIQUE (site_id, business_date, display_number),
    UNIQUE (site_id, business_date, prefix, seq_no)
);
-- Call-next lookup: waiting tickets ordered by priority then queue entry.
CREATE INDEX ix_ticket_waiting ON ticket (site_id, site_service_id, priority_rank, queued_at) WHERE status = 'WAITING';
CREATE INDEX ix_ticket_site_date ON ticket (site_id, business_date);
CREATE INDEX ix_ticket_counter_open ON ticket (counter_id) WHERE status IN ('CALLED', 'ON_HOLD');
CREATE INDEX ix_ticket_business_date ON ticket (business_date);

CREATE TABLE ticket_event (
    id               uuid PRIMARY KEY,
    ticket_id        uuid        NOT NULL REFERENCES ticket (id),
    site_id          uuid        NOT NULL REFERENCES site (id),
    event_type       varchar(20) NOT NULL,
    from_status      varchar(10),
    to_status        varchar(10),
    at               timestamptz NOT NULL,
    actor_user_id    uuid,
    actor_name       varchar(150),
    counter_id       uuid,
    site_service_id  uuid,
    reason           varchar(500)
);
CREATE INDEX ix_ticket_event_ticket ON ticket_event (ticket_id, at);
CREATE INDEX ix_ticket_event_site_at ON ticket_event (site_id, at);

CREATE TABLE feedback (
    id            uuid PRIMARY KEY,
    ticket_id     uuid        NOT NULL UNIQUE REFERENCES ticket (id),
    site_id       uuid        NOT NULL REFERENCES site (id),
    rating        smallint    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment       varchar(1000),
    submitted_at  timestamptz NOT NULL
);

-- ---------------------------------------------------------------- CMS
CREATE TABLE content_item (
    id                uuid PRIMARY KEY,
    title             varchar(200) NOT NULL,
    type              varchar(15)  NOT NULL CHECK (type IN ('ANNOUNCEMENT', 'IMAGE', 'VIDEO')),
    body              text,
    media_path        varchar(300),
    mime_type         varchar(60),
    size_bytes        bigint,
    site_id           uuid REFERENCES site (id),
    region_id         uuid REFERENCES region (id),
    starts_at         timestamptz,
    ends_at           timestamptz,
    display_seconds   int          NOT NULL DEFAULT 15 CHECK (display_seconds BETWEEN 3 AND 600),
    status            varchar(20)  NOT NULL
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'PUBLISHED', 'REJECTED', 'ARCHIVED')),
    created_by        varchar(60)  NOT NULL,
    approved_by       varchar(60),
    approved_at       timestamptz,
    rejection_reason  varchar(500),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    version           bigint       NOT NULL DEFAULT 0,
    CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);
CREATE INDEX ix_content_status ON content_item (status);

-- ---------------------------------------------------------------- audit
CREATE TABLE audit_log (
    id              uuid PRIMARY KEY,
    at              timestamptz  NOT NULL,
    actor_user_id   uuid,
    actor_username  varchar(60),
    action          varchar(60)  NOT NULL,
    entity_type     varchar(40),
    entity_id       varchar(60),
    site_id         uuid,
    details         text,
    ip_address      varchar(45)
);
CREATE INDEX ix_audit_at ON audit_log (at);
CREATE INDEX ix_audit_actor ON audit_log (actor_username, at);
CREATE INDEX ix_audit_action ON audit_log (action, at);

-- ---------------------------------------------------------------- synchronization
CREATE TABLE sync_outbox (
    id               uuid PRIMARY KEY,
    idempotency_key  varchar(100) NOT NULL UNIQUE,
    event_type       varchar(40)  NOT NULL,
    aggregate_type   varchar(40)  NOT NULL,
    aggregate_id     varchar(60)  NOT NULL,
    site_code        varchar(20)  NOT NULL,
    payload          text         NOT NULL,          -- JSON
    payload_sha256   varchar(64)  NOT NULL,          -- integrity check on the receiving side
    status           varchar(10)  NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts         int          NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz  NOT NULL,
    last_error       varchar(1000),
    created_at       timestamptz  NOT NULL,
    sent_at          timestamptz
);
CREATE INDEX ix_outbox_due ON sync_outbox (status, next_attempt_at);

CREATE TABLE sync_inbox (
    idempotency_key  varchar(100) PRIMARY KEY,
    site_code        varchar(20)  NOT NULL,
    event_type       varchar(40)  NOT NULL,
    received_at      timestamptz  NOT NULL,
    status           varchar(10)  NOT NULL CHECK (status IN ('APPLIED', 'REJECTED')),
    error            varchar(1000)
);

-- Per-site keys used by site nodes to authenticate to the central node.
CREATE TABLE sync_site_key (
    site_id     uuid PRIMARY KEY REFERENCES site (id),
    key_hash    varchar(64) NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE sync_state (
    name        varchar(60) PRIMARY KEY,
    value       varchar(200),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
