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
- Java version: [e.g. 11, 17]
- Framework version: [e.g. 1.7.2-SNAPSHOT]
- MongoDB version: [e.g. 5.0, 6.0]
- OS: [e.g. macOS, Linux]

**Additional context**
Add any other context about the problem here.
