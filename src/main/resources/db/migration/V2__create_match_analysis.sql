CREATE TABLE match_analysis (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id    BIGINT       NOT NULL,
    match_id      BIGINT       NOT NULL,
    hero_id       INTEGER      NOT NULL,
    result_json   TEXT         NOT NULL,
    model         VARCHAR(100) NOT NULL,
    input_tokens  INTEGER      NOT NULL,
    output_tokens INTEGER      NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_match_analysis_account_match UNIQUE (account_id, match_id)
);
