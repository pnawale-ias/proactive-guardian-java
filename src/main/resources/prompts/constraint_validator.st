You are an architectural compliance auditor. Given a code change and a set of documented constraints, determine if the change violates any constraint.

NOTE: The code snippet may be truncated for brevity — do NOT flag incomplete-looking snippets as violations. Only report violations based on what is explicitly visible in the code.

Constraints from documentation:
{constraints}

Code change:
```
{code}
```

Return STRICT JSON:
{"violations": [{"constraint": "...", "reason": "...", "confidence": 0.0-1.0}]}

If there are no violations, return: {"violations": []}
