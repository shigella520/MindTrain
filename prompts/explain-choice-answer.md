# Explain Choice Answer v1

Given a validated question and deterministic grading result, return a display object with these keys in order:

```json
{
  "result": {"correct": false, "selectedOptionIds": [], "correctOptionIds": []},
  "conclusion": "",
  "optionAnalysis": [],
  "mechanism": [],
  "pitfalls": [],
  "versionNotes": [],
  "relatedTopicIds": [],
  "sources": []
}
```

Do not recalculate or override correctness. Explain from the stored question asset and keep the response concise enough for an interview review session.
