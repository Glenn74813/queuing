-- Multi-stage routing (R-5.2.3): the default next service a client proceeds to after this one.
ALTER TABLE site_service ADD COLUMN next_site_service_id uuid REFERENCES site_service (id);
ALTER TABLE site_service ADD CONSTRAINT ck_site_service_next_not_self CHECK (next_site_service_id <> id);
