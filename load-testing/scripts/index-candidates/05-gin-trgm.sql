-- Кандидат 5: составной GIN с триграммами pg_trgm.
-- Выполнять весь файл в DataGrip, только в social_network_load.
BEGIN;

DO $guard$
BEGIN
    IF current_database() <> 'social_network_load' THEN
        RAISE EXCEPTION 'Run this script only in social_network_load';
    END IF;
END
$guard$;

DROP INDEX IF EXISTS public.users_name_prefix_idx;
DROP INDEX IF EXISTS public.users_surname_name_prefix_idx;
DROP INDEX IF EXISTS public.users_first_name_prefix_idx;
DROP INDEX IF EXISTS public.users_second_name_prefix_idx;
DROP INDEX IF EXISTS public.users_name_trgm_idx;

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

CREATE INDEX users_name_trgm_idx
    ON public.users USING gin (first_name public.gin_trgm_ops, second_name public.gin_trgm_ops);

ANALYZE public.users;
COMMIT;

SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public' AND tablename = 'users'
ORDER BY indexname;

