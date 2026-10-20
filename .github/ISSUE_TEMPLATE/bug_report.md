---
name: Bug Report
about: Create a report to help us improve
title: '[BUG] '
labels: bug
assignees: ''
---

**Describe the bug**
A clear and concise description of what the bug is.

**To Reproduce**
Steps and code to reproduce the behavior:
```java
// Minimal code example
Finder<MyModel> finder = new Finder<>(MyModel.class);
finder.byField("field", value).list(); // What happens?
```

**Expected behavior**
What you expected to happen.

**Actual behavior**
What actually happened (error message, stack trace, wrong result).

**Environment:**
- Java version: [e.g. 21]
- Framework version: [e.g. 1.9.1]
- Storage backend: [MongoDB 4.4+ / PostgreSQL 12+]
- Database version: [e.g. MongoDB 6.0, PostgreSQL 16]
- OS: [e.g. macOS, Linux]

**Additional context**
Add any other context about the problem here.
