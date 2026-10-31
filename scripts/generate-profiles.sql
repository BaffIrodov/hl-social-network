-- Заменяет тестовые анкеты только в базе social_network_load.
\set ON_ERROR_STOP on

BEGIN;
SET LOCAL TIME ZONE 'Europe/Moscow';

DO $$
DECLARE
    profile_count constant integer := 1000000;
    birthdate_from constant date := (CURRENT_DATE - INTERVAL '51 years')::date + 1;
    birthdate_to constant date := (CURRENT_DATE - INTERVAL '20 years')::date;
    dictionary jsonb;
    first_names text[];
    second_names text[];
BEGIN
    IF current_database() <> 'social_network_load' THEN
        RAISE EXCEPTION 'Run this script only in social_network_load';
    END IF;

    dictionary := pg_read_file('/load/profile-names.json')::jsonb;
    SELECT array_agg(value) INTO first_names
    FROM jsonb_array_elements_text(dictionary -> 'first_names');
    SELECT array_agg(value) INTO second_names
    FROM jsonb_array_elements_text(dictionary -> 'second_names');

    IF COALESCE(cardinality(first_names), 0) = 0 OR COALESCE(cardinality(second_names), 0) = 0 THEN
        RAISE EXCEPTION 'Both name lists must be nonempty';
    END IF;
    IF EXISTS (
        SELECT 1 FROM unnest(first_names || second_names) AS names(value)
        WHERE value IS NULL OR btrim(value) = '' OR char_length(value) > 100
    ) THEN
        RAISE EXCEPTION 'Names must contain between 1 and 100 characters';
    END IF;

    TRUNCATE users, user_credentials, user_sessions RESTART IDENTITY;

    INSERT INTO users (first_name, second_name, birthdate)
    SELECT
        first_names[1 + floor(random() * cardinality(first_names))::integer],
        second_names[1 + floor(random() * cardinality(second_names))::integer],
        birthdate_from + floor(random() * (birthdate_to - birthdate_from + 1))::integer
    FROM generate_series(1, profile_count);

    RAISE NOTICE 'Generated % profiles; birthdates from % to %',
        profile_count, birthdate_from, birthdate_to;
END $$;

COMMIT;
ANALYZE users;

SELECT count(*) AS profiles, min(birthdate) AS first_birthdate, max(birthdate) AS last_birthdate
FROM users;
