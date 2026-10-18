ALTER TABLE match_analysis ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE match_analysis ADD CONSTRAINT uq_match_analysis_public_id UNIQUE (public_id);
