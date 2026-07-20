CREATE TABLE app_user (
    id VARCHAR(120) PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE topic (
    id VARCHAR(200) PRIMARY KEY,
    domain_id VARCHAR(120) NOT NULL,
    parent_id VARCHAR(200),
    name VARCHAR(300) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    importance INTEGER NOT NULL,
    content_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE question (
    id VARCHAR(240) PRIMARY KEY,
    status VARCHAR(40) NOT NULL,
    current_version INTEGER NOT NULL,
    session_eligible_id VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE question_version (
    question_id VARCHAR(240) NOT NULL REFERENCES question(id),
    version INTEGER NOT NULL,
    type VARCHAR(40) NOT NULL,
    topic_ids_json TEXT NOT NULL,
    content_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (question_id, version)
);

CREATE TABLE training_session (
    id VARCHAR(120) PRIMARY KEY,
    user_id VARCHAR(120) NOT NULL REFERENCES app_user(id),
    domain_id VARCHAR(120) NOT NULL,
    scheduler_provider VARCHAR(80) NOT NULL,
    status VARCHAR(40) NOT NULL,
    target_count INTEGER NOT NULL,
    completed_main INTEGER NOT NULL,
    follow_up_count INTEGER NOT NULL,
    introduced_new_count INTEGER NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE,
    summary_json TEXT
);

CREATE TABLE assignment (
    id VARCHAR(120) PRIMARY KEY,
    session_id VARCHAR(120) NOT NULL REFERENCES training_session(id),
    question_id VARCHAR(240) NOT NULL REFERENCES question(id),
    question_version INTEGER NOT NULL,
    attempt_type VARCHAR(40) NOT NULL,
    parent_attempt_id VARCHAR(120),
    source_kind VARCHAR(40) NOT NULL,
    status VARCHAR(40) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    answered_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE attempt (
    id VARCHAR(120) PRIMARY KEY,
    assignment_id VARCHAR(120) NOT NULL UNIQUE REFERENCES assignment(id),
    session_id VARCHAR(120) NOT NULL REFERENCES training_session(id),
    user_id VARCHAR(120) NOT NULL REFERENCES app_user(id),
    question_id VARCHAR(240) NOT NULL,
    question_version INTEGER NOT NULL,
    raw_answer TEXT NOT NULL,
    selected_option_ids_json TEXT NOT NULL,
    correct_option_ids_json TEXT NOT NULL,
    correct BOOLEAN NOT NULL,
    score INTEGER NOT NULL,
    answered_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE mistake (
    id VARCHAR(120) PRIMARY KEY,
    attempt_id VARCHAR(120) NOT NULL UNIQUE REFERENCES attempt(id),
    user_id VARCHAR(120) NOT NULL REFERENCES app_user(id),
    question_id VARCHAR(240) NOT NULL,
    resolved BOOLEAN NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE interaction_event (
    id VARCHAR(120) PRIMARY KEY,
    session_id VARCHAR(120) NOT NULL REFERENCES training_session(id),
    assignment_id VARCHAR(120),
    user_id VARCHAR(120) NOT NULL REFERENCES app_user(id),
    event_type VARCHAR(80) NOT NULL,
    content TEXT NOT NULL,
    model VARCHAR(200),
    prompt_version VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE review_state (
    user_id VARCHAR(120) NOT NULL REFERENCES app_user(id),
    question_id VARCHAR(240) NOT NULL REFERENCES question(id),
    correct_count INTEGER NOT NULL,
    wrong_count INTEGER NOT NULL,
    consecutive_correct INTEGER NOT NULL,
    interval_days INTEGER NOT NULL,
    last_answered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_review_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, question_id)
);

CREATE TABLE topic_mastery (
    user_id VARCHAR(120) NOT NULL REFERENCES app_user(id),
    topic_id VARCHAR(200) NOT NULL,
    mastery_score INTEGER NOT NULL,
    correct_count INTEGER NOT NULL,
    wrong_count INTEGER NOT NULL,
    last_answered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, topic_id)
);

CREATE TABLE idempotency_record (
    user_id VARCHAR(120) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    operation VARCHAR(120) NOT NULL,
    response_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, idempotency_key, operation)
);

CREATE TABLE prototype_import (
    id VARCHAR(120) PRIMARY KEY,
    user_id VARCHAR(120) NOT NULL,
    dry_run BOOLEAN NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    report_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_question_status ON question(status);
CREATE INDEX idx_assignment_session_status ON assignment(session_id, status);
CREATE INDEX idx_attempt_user_answered ON attempt(user_id, answered_at);
CREATE INDEX idx_review_state_due ON review_state(user_id, next_review_at);
CREATE INDEX idx_interaction_session ON interaction_event(session_id, created_at);
