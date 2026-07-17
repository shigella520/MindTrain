# Choice-question Scoring Policy

- Single choice: accept exactly one option and award 100 only when it equals the stored correct option.
- Multiple choice: compare option sets without considering order and award 100 only for an exact set match. Any omission or incorrect extra option scores 0.
- Reject empty, unrecognizable, or multi-option input for a single-choice question. Do not create an Attempt for rejected input.
- Treat `A`, `AC`, `A,C`, `A、C`, `我选 A 和 C`, Chinese ordinal phrases, and an exact option text as valid formats when unambiguous.
- Derive correctness with code. AI may explain the result but must not override it.

Update each related topic deterministically after a valid attempt:

```text
new mastery = round(old mastery × 0.7 + score × 0.3)
```

Use 50 as the initial prior. Schedule a wrong answer for the next day. Schedule consecutive correct answers after 3, 7, 14, then 30 days.
