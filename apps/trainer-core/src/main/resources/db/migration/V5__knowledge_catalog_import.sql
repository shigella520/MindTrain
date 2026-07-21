CREATE TABLE knowledge_domain (
    id VARCHAR(120) NOT NULL,
    user_id VARCHAR(120) NOT NULL REFERENCES app_user(id),
    name VARCHAR(300) NOT NULL,
    content_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, id)
);

CREATE TABLE source_asset (
    id VARCHAR(300) NOT NULL,
    user_id VARCHAR(120) NOT NULL REFERENCES app_user(id),
    source_type VARCHAR(80) NOT NULL,
    library_id VARCHAR(120),
    relative_path VARCHAR(1000),
    content_hash CHAR(64),
    metadata_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, id)
);

CREATE TABLE topic_relation (
    id VARCHAR(240) NOT NULL,
    user_id VARCHAR(120) NOT NULL REFERENCES app_user(id),
    from_topic_id VARCHAR(200) NOT NULL,
    to_topic_id VARCHAR(200) NOT NULL,
    relation_type VARCHAR(80) NOT NULL,
    content_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, id)
);

CREATE TABLE catalog_import (
    id VARCHAR(120) PRIMARY KEY,
    user_id VARCHAR(120) NOT NULL REFERENCES app_user(id),
    library_id VARCHAR(120) NOT NULL,
    proposal_hash CHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    proposal_json TEXT NOT NULL,
    validation_json TEXT NOT NULL,
    diff_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    applied_at TIMESTAMP WITH TIME ZONE,
    rejected_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_catalog_import_user_created ON catalog_import(user_id, created_at);
CREATE INDEX idx_source_asset_library ON source_asset(user_id, library_id, relative_path);
CREATE INDEX idx_topic_relation_topics ON topic_relation(user_id, from_topic_id, to_topic_id);
