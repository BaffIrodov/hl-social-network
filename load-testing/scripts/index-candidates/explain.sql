-- Выполнять после выбора кандидата, в social_network_load.
-- Префиксы совпадают с ручным сценарием Gatling: Ив / Ив.
SELECT current_database() AS database_name;

SELECT indexname, indexdef,
       pg_size_pretty(pg_relation_size(format('%I.%I', schemaname, indexname)::regclass)) AS index_size
FROM pg_indexes
WHERE schemaname = 'public' AND tablename = 'users'
ORDER BY indexname;

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT id, first_name, second_name, birthdate, gender, biography, city
FROM public.users
WHERE first_name LIKE 'Ив%' ESCAPE '!'
  AND second_name LIKE 'Ив%' ESCAPE '!'
ORDER BY id ASC;

