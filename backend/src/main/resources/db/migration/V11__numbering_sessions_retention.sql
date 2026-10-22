-- Ticket numbering format and reset schedule per site (R-5.1.4).
ALTER TABLE site ADD COLUMN ticket_number_digits int NOT NULL DEFAULT 3
    CHECK (ticket_number_digits BETWEEN 2 AND 5);
ALTER TABLE site ADD COLUMN ticket_number_separator varchar(1) NOT NULL DEFAULT '-'
    CHECK (ticket_number_separator IN ('-', ''));
ALTER TABLE site ADD COLUMN ticket_number_reset varchar(10) NOT NULL DEFAULT 'DAILY'
    CHECK (ticket_number_reset IN ('DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'));
-- ticket_sequence.business_date now holds the first day of the numbering period (equal to the
-- business date for daily reset).
COMMENT ON COLUMN ticket_sequence.business_date IS 'First business date of the numbering period';

-- Phone e-ticket: SHA-256 of the random token in the ticket's QR code. Site-local; not replicated.
ALTER TABLE ticket ADD COLUMN public_token_hash varchar(64);
CREATE UNIQUE INDEX ux_ticket_public_token ON ticket (public_token_hash) WHERE public_token_hash IS NOT NULL;

-- Sign-in sessions: every rotated refresh token keeps the id of the login that started its chain,
-- so a session can be listed and revoked as one unit and access tokens can be checked against it.
ALTER TABLE refresh_token ADD COLUMN session_id uuid;
ALTER TABLE refresh_token ADD COLUMN session_started_at timestamptz;
UPDATE refresh_token SET session_id = id, session_started_at = created_at;
ALTER TABLE refresh_token ALTER COLUMN session_id SET NOT NULL;
ALTER TABLE refresh_token ALTER COLUMN session_started_at SET NOT NULL;
ALTER TABLE refresh_token ADD COLUMN ip_address varchar(45);
ALTER TABLE refresh_token ADD COLUMN user_agent varchar(200);
CREATE INDEX ix_refresh_token_session_live ON refresh_token (session_id) WHERE revoked_at IS NULL;

-- Retention (OI-13). audit_log and ticket_event are append-only for the application role, so purges
-- go through these owner-defined functions: the only way the application can delete that history,
-- and only older than a cutoff the caller supplies from configuration.
CREATE FUNCTION qms_purge_tickets(cutoff date, max_rows int) RETURNS int
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    ids uuid[];
BEGIN
    SELECT array_agg(id) INTO ids FROM (
        SELECT id FROM ticket WHERE business_date < cutoff
        AND status IN ('COMPLETED', 'REMOVED', 'VOIDED', 'EXPIRED') LIMIT max_rows) s;
    IF ids IS NULL THEN
        RETURN 0;
    END IF;
    DELETE FROM ticket_event WHERE ticket_id = ANY (ids);
    DELETE FROM feedback WHERE ticket_id = ANY (ids);
    DELETE FROM ticket WHERE id = ANY (ids);
    RETURN cardinality(ids);
END
$$;

CREATE FUNCTION qms_purge_audit(cutoff timestamptz, max_rows int) RETURNS int
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    n int;
BEGIN
    DELETE FROM audit_log WHERE id IN (SELECT id FROM audit_log WHERE at < cutoff LIMIT max_rows);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END
$$;

REVOKE ALL ON FUNCTION qms_purge_tickets(date, int), qms_purge_audit(timestamptz, int) FROM PUBLIC;
DO $$
DECLARE
    app text := '${appRole}';
BEGIN
    IF app <> current_user AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        EXECUTE format('GRANT EXECUTE ON FUNCTION qms_purge_tickets(date, int), qms_purge_audit(timestamptz, int) TO %I', app);
    END IF;
END
$$;
