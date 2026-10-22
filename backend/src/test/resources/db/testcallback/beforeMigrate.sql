-- Test only: create the application role so V3's grants are applied and can be verified.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'qms_app_test') THEN
        CREATE ROLE qms_app_test NOLOGIN;
    END IF;
END
$$;
