--liquibase formatted sql

--changeset social-network:001-create-gender
CREATE TYPE user_gender AS ENUM ('MALE', 'FEMALE', 'UNSPECIFIED');
--rollback DROP TYPE user_gender;

--changeset social-network:002-create-users
CREATE TABLE users (
    id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    first_name varchar(100) NOT NULL,
    second_name varchar(100) NOT NULL,
    birthdate date NOT NULL,
    gender user_gender NOT NULL DEFAULT 'UNSPECIFIED',
    biography text,
    city varchar(100),
    CONSTRAINT users_id_positive CHECK (id > 0),
    CONSTRAINT users_first_name_not_blank CHECK (char_length(btrim(first_name)) > 0),
    CONSTRAINT users_second_name_not_blank CHECK (char_length(btrim(second_name)) > 0),
    CONSTRAINT users_biography_length CHECK (char_length(biography) <= 2000)
);
--rollback DROP TABLE users;

--changeset social-network:003-create-credentials
CREATE TABLE user_credentials (
    user_id integer PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    password_hash text NOT NULL,
    CONSTRAINT user_credentials_hash_not_empty CHECK (password_hash <> '')
);
--rollback DROP TABLE user_credentials;

--changeset social-network:004-create-sessions
CREATE TABLE user_sessions (
    token_hash varchar(64) PRIMARY KEY,
    user_id integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT user_sessions_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT user_sessions_expiration CHECK (expires_at > created_at)
);
--rollback DROP TABLE user_sessions;
