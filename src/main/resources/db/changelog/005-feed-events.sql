--liquibase formatted sql

--changeset social-network:009-create-feed-events
CREATE TABLE feed_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type text NOT NULL,
    user_id integer NOT NULL CHECK (user_id > 0),
    post_id uuid,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at timestamptz,
    CONSTRAINT feed_events_payload CHECK (
        (event_type IN ('POST_CREATED', 'POST_UPDATED', 'POST_DELETED') AND post_id IS NOT NULL)
        OR (event_type = 'FRIENDSHIPS_CHANGED' AND post_id IS NULL)
    )
);

CREATE INDEX feed_events_pending_idx ON feed_events (id) WHERE processed_at IS NULL;
--rollback DROP TABLE feed_events;
