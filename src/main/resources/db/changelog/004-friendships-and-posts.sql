--liquibase formatted sql

--changeset social-network:007-create-friendships
CREATE TABLE friendships (
    user_id integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    friend_id integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, friend_id),
    CONSTRAINT friendships_no_self CHECK (user_id <> friend_id)
);

CREATE INDEX friendships_friend_user_idx ON friendships (friend_id, user_id);
--rollback DROP TABLE friendships;

--changeset social-network:008-create-posts
CREATE TABLE posts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    author_user_id integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    text text NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT posts_text_not_blank CHECK (text ~ '[^[:space:]]')
);

CREATE INDEX posts_author_created_id_idx ON posts (author_user_id, created_at DESC, id DESC);
--rollback DROP TABLE posts;
