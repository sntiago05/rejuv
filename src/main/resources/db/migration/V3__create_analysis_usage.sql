CREATE TABLE analysis_usage (
    id              BIGSERIAL    PRIMARY KEY,
    discord_user_id BIGINT       NOT NULL,
    match_id        BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_analysis_usage_user_created ON analysis_usage (discord_user_id, created_at);
