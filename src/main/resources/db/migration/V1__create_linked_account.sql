CREATE TABLE linked_account (
    discord_user_id BIGINT      PRIMARY KEY,
    account_id      BIGINT      NOT NULL,
    linked_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
