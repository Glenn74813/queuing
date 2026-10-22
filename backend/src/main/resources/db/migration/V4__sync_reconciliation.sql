-- Central node: per-site, per-day counts reported by each site, compared with what central holds.
CREATE TABLE sync_reconciliation (
    site_id              uuid        NOT NULL REFERENCES site (id),
    business_date        date        NOT NULL,
    site_ticket_count    bigint      NOT NULL,
    site_event_count     bigint      NOT NULL,
    site_feedback_count  bigint      NOT NULL,
    site_outbox_pending  bigint      NOT NULL,   -- unsent outbox entries at the site when it reported
    reported_at          timestamptz NOT NULL,
    PRIMARY KEY (site_id, business_date)
);
