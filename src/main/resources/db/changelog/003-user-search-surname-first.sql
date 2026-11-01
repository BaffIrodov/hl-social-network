--liquibase formatted sql

--changeset social-network:006-user-search-surname-first
DROP INDEX IF EXISTS users_name_prefix_idx;
DROP INDEX IF EXISTS users_surname_name_prefix_idx;

CREATE INDEX users_surname_name_prefix_idx
    ON users (second_name varchar_pattern_ops, first_name varchar_pattern_ops);
--rollback DROP INDEX users_surname_name_prefix_idx;
--rollback CREATE INDEX users_name_prefix_idx ON users (first_name varchar_pattern_ops, second_name varchar_pattern_ops);
