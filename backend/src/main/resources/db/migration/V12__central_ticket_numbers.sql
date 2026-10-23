-- Central stores site-owned tickets by id. A site node restored from an older backup can issue a
-- number again that central already received for another client (found in the backup/restore
-- drill), and central must keep both records. Number uniqueness therefore stays a site-node rule.
DO $$
BEGIN
    IF '${nodeRole}' = 'CENTRAL' THEN
        ALTER TABLE ticket DROP CONSTRAINT IF EXISTS ticket_site_id_business_date_display_number_key;
        ALTER TABLE ticket DROP CONSTRAINT IF EXISTS ticket_site_id_business_date_prefix_seq_no_key;
        CREATE INDEX IF NOT EXISTS ix_ticket_site_date_number ON ticket (site_id, business_date, display_number);
    END IF;
END
$$;
