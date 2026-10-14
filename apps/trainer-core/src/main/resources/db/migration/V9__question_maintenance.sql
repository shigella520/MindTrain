ALTER TABLE question ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE question ADD COLUMN deletion_reason TEXT;
ALTER TABLE assignment ADD COLUMN cancellation_reason VARCHAR(120);

CREATE TABLE question_lifecycle_event (
    id VARCHAR(120) PRIMARY KEY,
    question_id VARCHAR(240) NOT NULL REFERENCES question(id),
    user_id VARCHAR(120) NOT NULL REFERENCES app_user(id),
    question_version INTEGER NOT NULL,
    action VARCHAR(40) NOT NULL,
    reason TEXT,
    source_assignment_id VARCHAR(120) REFERENCES assignment(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
