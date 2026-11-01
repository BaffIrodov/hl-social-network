--liquibase formatted sql

--changeset social-network:005-user-search-prefix-index
CREATE INDEX users_name_prefix_idx
    ON users (first_name varchar_pattern_ops, second_name varchar_pattern_ops);
--rollback DROP INDEX users_name_prefix_idx;
