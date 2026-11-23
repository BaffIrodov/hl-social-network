\set ON_ERROR_STOP on
\encoding UTF8

WITH demo_profiles(first_name, second_name, password_hash) AS (
    VALUES
        ('Vera', 'Sokolova', '$argon2id$v=19$m=19456,t=2,p=1$wBquFql6AKj48v2pYyxbWg$xLqB8Z6pwrm2cBW/XIX0WTfaQ+uRw2oPI/zeDuUIbng'),
        ('Maria', 'Volkova', '$argon2id$v=19$m=19456,t=2,p=1$zSDLDB3efkMSOb0KezFSjA$pA5WTDVvJmUIt2G8kKCuJ5mIBUG4FU66LJcxpyUMLgY'),
        ('Ilya', 'Orlov', '$argon2id$v=19$m=19456,t=2,p=1$J08wsi0He1egO4LLGXu4Vw$oj1rrpbTy9EEF3yWyS3uMJj1fCUAMcdHaM8pCagtNZI'),
        ('Oleg', 'Lebedev', '$argon2id$v=19$m=19456,t=2,p=1$prOly4gZYH8MknrcjlSEDg$D4VDeRcr0Xao7Vm5k4cuGFhmag+J1K3nQzu2yT6Iqhs')
),
demo_users AS (
    INSERT INTO users (first_name, second_name, birthdate)
    SELECT first_name, second_name, DATE '1995-05-20'
    FROM demo_profiles
    RETURNING id, first_name, second_name
),
demo_credentials AS (
    INSERT INTO user_credentials (user_id, password_hash)
    SELECT demo_users.id, demo_profiles.password_hash
    FROM demo_users
    JOIN demo_profiles USING (first_name, second_name)
),
demo_friendships AS (
    INSERT INTO friendships (user_id, friend_id)
    SELECT reader.id, friend.id
    FROM demo_users AS reader
    CROSS JOIN demo_users AS friend
    WHERE reader.first_name = 'Vera' AND friend.first_name IN ('Maria', 'Ilya')
),
demo_post_texts(first_name, text, created_at) AS (
    VALUES
        ('Maria', 'Can anyone recommend a book for the weekend?', TIMESTAMPTZ '2025-10-01 10:00:00+00'),
        ('Ilya', 'I ran five kilometers for the first time today.', TIMESTAMPTZ '2025-10-01 11:00:00+00'),
        ('Maria', 'I found a new coffee shop near home.', TIMESTAMPTZ '2025-10-01 12:00:00+00'),
        ('Vera', 'I sorted through my trip photos.', TIMESTAMPTZ '2025-10-01 13:00:00+00'),
        ('Oleg', 'I am going to an exhibition on Saturday.', TIMESTAMPTZ '2025-10-01 14:00:00+00')
),
demo_posts AS (
    INSERT INTO posts (author_user_id, text, created_at)
    SELECT demo_users.id, demo_post_texts.text, demo_post_texts.created_at
    FROM demo_post_texts
    JOIN demo_users USING (first_name)
)
SELECT id AS user_id, first_name, second_name
FROM demo_users
ORDER BY id;
