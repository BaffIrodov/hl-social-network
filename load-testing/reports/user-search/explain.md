# EXPLAIN для шести конфигураций

[Основной отчёт](README.md). Запрос и данные одинаковы; сохранён второй запуск EXPLAIN после ANALYZE и прогрева.

Ниже сохранены полные результаты второго EXPLAIN: активные индексы с размерами,
план, Planning Time, Execution Time и Buffers.

<a id="no-search-index"></a>

### Базовый: без поисковых индексов

[SQL переключения](../../scripts/index-candidates/00-no-search-index.sql)

<details>
<summary>Полный EXPLAIN и список индексов</summary>

```text
database_name
---------------------
 social_network_load
(1 row)

 indexname  |                            indexdef                             | index_size
------------+-----------------------------------------------------------------+------------
 users_pkey | CREATE UNIQUE INDEX users_pkey ON public.users USING btree (id) | 43 MB
(1 row)

                                                         QUERY PLAN
----------------------------------------------------------------------------------------------------------------------------
 Sort  (cost=24974.11..24974.11 rows=1 width=290) (actual time=41.422..47.860 rows=14 loops=1)
   Sort Key: id
   Sort Method: quicksort  Memory: 25kB
   Buffers: shared hit=10042 read=7685
   ->  Gather  (cost=1000.00..24974.10 rows=1 width=290) (actual time=8.397..47.814 rows=14 loops=1)
         Workers Planned: 2
         Workers Launched: 2
         Buffers: shared hit=10039 read=7685
         ->  Parallel Seq Scan on users  (cost=0.00..23974.00 rows=1 width=290) (actual time=16.003..37.659 rows=5 loops=3)
               Filter: (((first_name)::text ~~ 'Ив%'::text) AND ((second_name)::text ~~ 'Ив%'::text))
               Rows Removed by Filter: 333329
               Buffers: shared hit=10039 read=7685
 Planning:
   Buffers: shared hit=35
 Planning Time: 0.438 ms
 Execution Time: 47.937 ms
(16 rows)
```

</details>

<a id="btree-name-surname"></a>

### 1. B-tree: имя → фамилия

[SQL переключения](../../scripts/index-candidates/01-btree-name-surname.sql)

<details>
<summary>Полный EXPLAIN и список индексов</summary>

```text
database_name
---------------------
 social_network_load
(1 row)

       indexname       |                                                             indexdef                                                             | index_size
-----------------------+----------------------------------------------------------------------------------------------------------------------------------+------------
 users_name_prefix_idx | CREATE INDEX users_name_prefix_idx ON public.users USING btree (first_name varchar_pattern_ops, second_name varchar_pattern_ops) | 14 MB
 users_pkey            | CREATE UNIQUE INDEX users_pkey ON public.users USING btree (id)                                                                  | 43 MB
(2 rows)

                                                                                      QUERY PLAN
---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 Sort  (cost=215.86..215.87 rows=1 width=291) (actual time=0.235..0.236 rows=14 loops=1)
   Sort Key: id
   Sort Method: quicksort  Memory: 25kB
   Buffers: shared hit=32
   ->  Index Scan using users_name_prefix_idx on users  (cost=0.42..215.85 rows=1 width=291) (actual time=0.070..0.208 rows=14 loops=1)
         Index Cond: (((first_name)::text ~>=~ 'Ив'::text) AND ((first_name)::text ~<~ 'Иг'::text) AND ((second_name)::text ~>=~ 'Ив'::text) AND ((second_name)::text ~<~ 'Иг'::text))
         Filter: (((first_name)::text ~~ 'Ив%'::text) AND ((second_name)::text ~~ 'Ив%'::text))
         Buffers: shared hit=29
 Planning:
   Buffers: shared hit=90
 Planning Time: 0.714 ms
 Execution Time: 0.298 ms
(12 rows)
```

</details>

<a id="btree-surname-name"></a>

### 2. B-tree: фамилия → имя

[SQL переключения](../../scripts/index-candidates/02-btree-surname-name.sql)

<details>
<summary>Полный EXPLAIN и список индексов</summary>

```text
database_name
---------------------
 social_network_load
(1 row)

           indexname           |                                                                 indexdef                                                                 | index_size
-------------------------------+------------------------------------------------------------------------------------------------------------------------------------------+------------
 users_pkey                    | CREATE UNIQUE INDEX users_pkey ON public.users USING btree (id)                                                                          | 43 MB
 users_surname_name_prefix_idx | CREATE INDEX users_surname_name_prefix_idx ON public.users USING btree (second_name varchar_pattern_ops, first_name varchar_pattern_ops) | 14 MB
(2 rows)

                                                                                      QUERY PLAN
---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 Sort  (cost=8.47..8.47 rows=1 width=290) (actual time=0.172..0.173 rows=14 loops=1)
   Sort Key: id
   Sort Method: quicksort  Memory: 25kB
   Buffers: shared hit=24
   ->  Index Scan using users_surname_name_prefix_idx on users  (cost=0.42..8.46 rows=1 width=290) (actual time=0.034..0.143 rows=14 loops=1)
         Index Cond: (((second_name)::text ~>=~ 'Ив'::text) AND ((second_name)::text ~<~ 'Иг'::text) AND ((first_name)::text ~>=~ 'Ив'::text) AND ((first_name)::text ~<~ 'Иг'::text))
         Filter: (((first_name)::text ~~ 'Ив%'::text) AND ((second_name)::text ~~ 'Ив%'::text))
         Buffers: shared hit=21
 Planning:
   Buffers: shared hit=90
 Planning Time: 0.746 ms
 Execution Time: 0.235 ms
(12 rows)
```

</details>

<a id="two-btree-indexes"></a>

### 3. Два отдельных B-tree

[SQL переключения](../../scripts/index-candidates/03-two-btree-indexes.sql)

<details>
<summary>Полный EXPLAIN и список индексов</summary>

```text
database_name
---------------------
 social_network_load
(1 row)

          indexname           |                                                indexdef                                                 | index_size
------------------------------+---------------------------------------------------------------------------------------------------------+------------
 users_first_name_prefix_idx  | CREATE INDEX users_first_name_prefix_idx ON public.users USING btree (first_name varchar_pattern_ops)   | 6968 kB
 users_pkey                   | CREATE UNIQUE INDEX users_pkey ON public.users USING btree (id)                                         | 43 MB
 users_second_name_prefix_idx | CREATE INDEX users_second_name_prefix_idx ON public.users USING btree (second_name varchar_pattern_ops) | 7248 kB
(3 rows)

                                                                       QUERY PLAN
--------------------------------------------------------------------------------------------------------------------------------------------------------
 Sort  (cost=213.31..213.35 rows=16 width=291) (actual time=1.441..1.443 rows=14 loops=1)
   Sort Key: id
   Sort Method: quicksort  Memory: 25kB
   Buffers: shared hit=31
   ->  Bitmap Heap Scan on users  (cost=157.96..212.99 rows=16 width=291) (actual time=1.298..1.411 rows=14 loops=1)
         Filter: (((first_name)::text ~~ 'Ив%'::text) AND ((second_name)::text ~~ 'Ив%'::text))
         Heap Blocks: exact=14
         Buffers: shared hit=28
         ->  BitmapAnd  (cost=157.96..157.96 rows=14 width=0) (actual time=1.263..1.264 rows=0 loops=1)
               Buffers: shared hit=14
               ->  Bitmap Index Scan on users_second_name_prefix_idx  (cost=0.00..23.10 rows=1467 width=0) (actual time=0.237..0.237 rows=1978 loops=1)
                     Index Cond: (((second_name)::text ~>=~ 'Ив'::text) AND ((second_name)::text ~<~ 'Иг'::text))
                     Buffers: shared hit=5
               ->  Bitmap Index Scan on users_first_name_prefix_idx  (cost=0.00..134.61 rows=9818 width=0) (actual time=0.927..0.927 rows=7220 loops=1)
                     Index Cond: (((first_name)::text ~>=~ 'Ив'::text) AND ((first_name)::text ~<~ 'Иг'::text))
                     Buffers: shared hit=9
 Planning:
   Buffers: shared hit=91
 Planning Time: 0.912 ms
 Execution Time: 1.553 ms
(20 rows)
```

</details>

<a id="btree-surname-only"></a>

### 4. B-tree только по фамилии

[SQL переключения](../../scripts/index-candidates/04-btree-surname-only.sql)

<details>
<summary>Полный EXPLAIN и список индексов</summary>

```text
database_name
---------------------
 social_network_load
(1 row)

          indexname           |                                                indexdef                                                 | index_size
------------------------------+---------------------------------------------------------------------------------------------------------+------------
 users_pkey                   | CREATE UNIQUE INDEX users_pkey ON public.users USING btree (id)                                         | 43 MB
 users_second_name_prefix_idx | CREATE INDEX users_second_name_prefix_idx ON public.users USING btree (second_name varchar_pattern_ops) | 7248 kB
(2 rows)

                                                                 QUERY PLAN
---------------------------------------------------------------------------------------------------------------------------------------------
 Sort  (cost=8.46..8.47 rows=1 width=290) (actual time=5.143..5.144 rows=14 loops=1)
   Sort Key: id
   Sort Method: quicksort  Memory: 25kB
   Buffers: shared hit=1878
   ->  Index Scan using users_second_name_prefix_idx on users  (cost=0.42..8.45 rows=1 width=290) (actual time=0.825..5.096 rows=14 loops=1)
         Index Cond: (((second_name)::text ~>=~ 'Ив'::text) AND ((second_name)::text ~<~ 'Иг'::text))
         Filter: (((first_name)::text ~~ 'Ив%'::text) AND ((second_name)::text ~~ 'Ив%'::text))
         Rows Removed by Filter: 1964
         Buffers: shared hit=1875
 Planning:
   Buffers: shared hit=90
 Planning Time: 0.671 ms
 Execution Time: 5.298 ms
(13 rows)
```

</details>

<a id="gin-trgm"></a>

### 5. Составной GIN с pg_trgm

[SQL переключения](../../scripts/index-candidates/05-gin-trgm.sql)

<details>
<summary>Полный EXPLAIN и список индексов</summary>

```text
database_name
---------------------
 social_network_load
(1 row)

      indexname      |                                                    indexdef                                                    | index_size
---------------------+----------------------------------------------------------------------------------------------------------------+------------
 users_name_trgm_idx | CREATE INDEX users_name_trgm_idx ON public.users USING gin (first_name gin_trgm_ops, second_name gin_trgm_ops) | 37 MB
 users_pkey          | CREATE UNIQUE INDEX users_pkey ON public.users USING btree (id)                                                | 43 MB
(2 rows)

                                                             QUERY PLAN
------------------------------------------------------------------------------------------------------------------------------------
 Sort  (cost=80.02..80.03 rows=1 width=291) (actual time=2.014..2.016 rows=14 loops=1)
   Sort Key: id
   Sort Method: quicksort  Memory: 25kB
   Buffers: shared hit=48
   ->  Bitmap Heap Scan on users  (cost=76.00..80.01 rows=1 width=291) (actual time=1.863..1.945 rows=14 loops=1)
         Recheck Cond: (((first_name)::text ~~ 'Ив%'::text) AND ((second_name)::text ~~ 'Ив%'::text))
         Heap Blocks: exact=14
         Buffers: shared hit=45
         ->  Bitmap Index Scan on users_name_trgm_idx  (cost=0.00..75.99 rows=1 width=0) (actual time=1.835..1.836 rows=14 loops=1)
               Index Cond: (((first_name)::text ~~ 'Ив%'::text) AND ((second_name)::text ~~ 'Ив%'::text))
               Buffers: shared hit=31
 Planning:
   Buffers: shared hit=48
 Planning Time: 0.833 ms
 Execution Time: 2.305 ms
(15 rows)
```

</details>

## План победителя после миграции

```text
database_name    
---------------------
 social_network_load
(1 row)

           indexname           |                                                                 indexdef                                                                 | index_size 
-------------------------------+------------------------------------------------------------------------------------------------------------------------------------------+------------
 users_pkey                    | CREATE UNIQUE INDEX users_pkey ON public.users USING btree (id)                                                                          | 43 MB
 users_surname_name_prefix_idx | CREATE INDEX users_surname_name_prefix_idx ON public.users USING btree (second_name varchar_pattern_ops, first_name varchar_pattern_ops) | 14 MB
(2 rows)

                                                                                      QUERY PLAN                                                                                       
---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 Sort  (cost=8.47..8.47 rows=1 width=290) (actual time=0.169..0.170 rows=14 loops=1)
   Sort Key: id
   Sort Method: quicksort  Memory: 25kB
   Buffers: shared hit=24
   ->  Index Scan using users_surname_name_prefix_idx on users  (cost=0.42..8.46 rows=1 width=290) (actual time=0.038..0.144 rows=14 loops=1)
         Index Cond: (((second_name)::text ~>=~ 'Ив'::text) AND ((second_name)::text ~<~ 'Иг'::text) AND ((first_name)::text ~>=~ 'Ив'::text) AND ((first_name)::text ~<~ 'Иг'::text))
         Filter: (((first_name)::text ~~ 'Ив%'::text) AND ((second_name)::text ~~ 'Ив%'::text))
         Buffers: shared hit=21
 Planning:
   Buffers: shared hit=93
 Planning Time: 0.632 ms
 Execution Time: 0.227 ms
(12 rows)
```
