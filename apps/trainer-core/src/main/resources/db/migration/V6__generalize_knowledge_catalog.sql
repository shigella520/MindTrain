ALTER TABLE catalog_import ALTER COLUMN library_id DROP NOT NULL;
ALTER TABLE catalog_import ADD COLUMN origin_type VARCHAR(40) NOT NULL DEFAULT 'local_reference';
ALTER TABLE catalog_import ADD COLUMN context_json TEXT NOT NULL DEFAULT '{}';

ALTER TABLE knowledge_domain ADD COLUMN origin_type VARCHAR(40) NOT NULL DEFAULT 'legacy';
ALTER TABLE knowledge_domain ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
ALTER TABLE knowledge_domain ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE topic ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
ALTER TABLE topic ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;

INSERT INTO knowledge_domain(id, user_id, name, content_json, created_at, origin_type, sort_order, updated_at)
SELECT historical.domain_id, users.id, historical.domain_id, '{}', CURRENT_TIMESTAMP, 'legacy', 0, CURRENT_TIMESTAMP
FROM (
    SELECT DISTINCT domain_id FROM topic
    UNION
    SELECT DISTINCT domain_id FROM training_session
) historical
CROSS JOIN app_user users
WHERE NOT EXISTS (
    SELECT 1 FROM knowledge_domain domain
    WHERE domain.id = historical.domain_id AND domain.user_id = users.id
);

UPDATE knowledge_domain SET updated_at = created_at WHERE updated_at IS NULL;
UPDATE topic SET updated_at = created_at WHERE updated_at IS NULL;

CREATE INDEX idx_knowledge_domain_user_sort ON knowledge_domain(user_id, sort_order, name);
CREATE INDEX idx_topic_domain_parent_sort ON topic(domain_id, parent_id, sort_order, name);
