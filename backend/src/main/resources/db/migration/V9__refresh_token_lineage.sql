-- Refresh-token rotation lineage, so a lost refresh response (page reload, dropped connection) can be
-- told apart from token theft. See AuthService.refresh.
ALTER TABLE refresh_token ADD COLUMN replaced_by uuid REFERENCES refresh_token (id) ON DELETE SET NULL;
ALTER TABLE refresh_token ADD COLUMN revoked_reason varchar(20)
    CHECK (revoked_reason IN ('ROTATED', 'SUPERSEDED', 'LOGOUT', 'REVOKED', 'REUSE_DETECTED'));
