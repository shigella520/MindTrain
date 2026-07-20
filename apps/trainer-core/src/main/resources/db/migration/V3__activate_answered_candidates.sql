UPDATE question
SET status = 'active', session_eligible_id = NULL
WHERE status = 'candidate'
  AND EXISTS (
    SELECT 1 FROM attempt
    WHERE attempt.question_id = question.id
  );

UPDATE question SET status = 'active' WHERE status = 'published';

DELETE FROM interaction_event
WHERE assignment_id IN (
  SELECT a.id
  FROM assignment a
  JOIN question q ON q.id = a.question_id
  JOIN training_session s ON s.id = a.session_id
  WHERE a.status = 'pending'
    AND q.status = 'candidate'
    AND s.status = 'completed'
);

DELETE FROM assignment
WHERE id IN (
  SELECT a.id
  FROM assignment a
  JOIN question q ON q.id = a.question_id
  JOIN training_session s ON s.id = a.session_id
  WHERE a.status = 'pending'
    AND q.status = 'candidate'
    AND s.status = 'completed'
);

DELETE FROM question_version
WHERE question_id IN (
  SELECT q.id
  FROM question q
  JOIN training_session s ON s.id = q.session_eligible_id
  WHERE q.status = 'candidate'
    AND s.status = 'completed'
    AND NOT EXISTS (SELECT 1 FROM assignment a WHERE a.question_id = q.id)
    AND NOT EXISTS (SELECT 1 FROM attempt at WHERE at.question_id = q.id)
);

DELETE FROM question
WHERE status = 'candidate'
  AND session_eligible_id IN (SELECT id FROM training_session WHERE status = 'completed')
  AND NOT EXISTS (SELECT 1 FROM assignment a WHERE a.question_id = question.id)
  AND NOT EXISTS (SELECT 1 FROM attempt at WHERE at.question_id = question.id);
