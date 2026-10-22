-- Central → site distribution of CMS content (OI-12).
-- origin: LOCAL = created on this node; CENTRAL = replicated from the central node (read-only here).
ALTER TABLE content_item ADD COLUMN origin varchar(10) NOT NULL DEFAULT 'LOCAL'
    CHECK (origin IN ('LOCAL', 'CENTRAL'));
-- SHA-256 of the stored media file, verified by sites after download.
ALTER TABLE content_item ADD COLUMN media_sha256 varchar(64);
