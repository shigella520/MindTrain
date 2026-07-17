# Choice-question Training Workflow

## State machine

```text
create session
  -> select weighted topic
  -> select unseen/due published question
  -> generate current-session candidate when missing
  -> present stem and A-D
  -> wait for a valid selection
  -> exact grade and persist
  -> render structured explanation
  -> next / deeper follow-up / finish
```

## Commands

Create the default session:

```bash
python3 skill/java-interview-coach/scripts/new-session.py
```

Select a weighted topic and question:

```bash
python3 skill/java-interview-coach/scripts/select-question.py --seed 42
```

Record one valid answer and update attempts, mastery, and session state:

```bash
python3 skill/java-interview-coach/scripts/record-attempt.py \
  --question-file assets/questions/core-sample-questions.json \
  --question-id java.collections.hashmap.001 \
  --answer B \
  --session-file learning-data/sessions/<session-id>.json
```

For a deeper question, add `--attempt-type follow_up --parent-attempt-id <attempt-id>`.

Finish the session:

```bash
python3 skill/java-interview-coach/scripts/finish-session.py learning-data/sessions/<session-id>.json
```

## Presentation

Before the answer, show only the question number, type, knowledge area, difficulty, stem, and four options. After the answer, show:

1. correct/incorrect, selected options, and correct options;
2. the core conclusion;
3. analysis of A, B, C, and D;
4. mechanism or execution flow;
5. common interview traps;
6. Java or component version differences;
7. related topics and source links.

Invalid input does not consume a question. Follow-ups are recorded but do not count toward the main target. A session may finish early and still receive a summary.
