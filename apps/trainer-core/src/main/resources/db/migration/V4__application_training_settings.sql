CREATE TABLE application_training_settings (
    id VARCHAR(80) PRIMARY KEY,
    question_count INTEGER NOT NULL CHECK (question_count BETWEEN 1 AND 100),
    new_budget INTEGER NOT NULL CHECK (new_budget >= 0 AND new_budget <= question_count),
    backlog_pause_threshold INTEGER NOT NULL CHECK (backlog_pause_threshold BETWEEN 0 AND 10000),
    overdue_pause_days INTEGER NOT NULL CHECK (overdue_pause_days BETWEEN 1 AND 365),
    pending_candidate_ttl_hours INTEGER NOT NULL CHECK (pending_candidate_ttl_hours BETWEEN 1 AND 8760),
    reporting_time_zone VARCHAR(120) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(120)
);

INSERT INTO application_training_settings(
    id, question_count, new_budget, backlog_pause_threshold, overdue_pause_days,
    pending_candidate_ttl_hours, reporting_time_zone, updated_at, updated_by
) VALUES (
    'default', 10, 2, 20, 3, 24, 'Asia/Shanghai', CURRENT_TIMESTAMP, NULL
);
