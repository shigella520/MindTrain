CREATE TABLE question_revision (
    id VARCHAR(120) PRIMARY KEY,
    question_id VARCHAR(240) NOT NULL REFERENCES question(id),
    from_version INTEGER NOT NULL,
    to_version INTEGER NOT NULL,
    user_id VARCHAR(120) NOT NULL REFERENCES app_user(id),
    source_assignment_id VARCHAR(120) REFERENCES assignment(id),
    change_reason TEXT NOT NULL,
    model VARCHAR(200),
    prompt_version VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (question_id, to_version)
);

CREATE INDEX idx_question_revision_question ON question_revision(question_id, created_at);
