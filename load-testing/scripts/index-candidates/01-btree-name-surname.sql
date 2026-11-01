-- Кандидат 1: составной B-tree, имя затем фамилия.
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

CREATE INDEX users_name_prefix_idx
    ON public.users (first_name varchar_pattern_ops, second_name varchar_pattern_ops);

ANALYZE public.users;
COMMIT;

SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public' AND tablename = 'users'
ORDER BY indexname;

