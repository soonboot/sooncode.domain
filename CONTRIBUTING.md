# Contributing to sooncode.domain

Thank you for considering contributing to sooncode.domain! We welcome contributions of all kinds — bug reports, feature requests, documentation improvements, and code changes.

## Code of Conduct

This project adheres to a [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## How to Contribute

### Reporting Bugs

1. **Search existing issues** first to avoid duplicates.
2. Use the [Bug Report template](.github/ISSUE_TEMPLATE/bug_report.md).
3. Include:
   - Framework version
   - Java version
   - MongoDB version
   - Minimal reproducible example or test case
   - Expected vs actual behavior

### Suggesting Features

1. Open a [Feature Request](.github/ISSUE_TEMPLATE/feature_request.md).
2. Describe the problem you're solving, not just the solution.
3. Provide examples of how the API would look.

### Pull Requests

1. **Fork the repository** and create a branch from `main`.
2. **Write tests** for your changes when possible.
3. **Keep changes focused** — one feature/fix per PR.
4. **Update documentation** (README, JavaDoc) if your change affects the public API.
5. **Run tests** before submitting.
6. **Use conventional commits** — see below.

#### PR Checklist

- [ ] Code compiles (`mvn compile`)
- [ ] Tests pass (`mvn test`)
- [ ] New tests added for changes
- [ ] JavaDoc updated for new/changed public APIs
- [ ] PR description explains what and why

### Commit Style

We follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>: <short description>

[optional body]
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `perf`

Examples:
```
feat: add batch event processing
fix: concurrent modification check for empty stream
docs: add Finder aggregation examples
refactor: extract MongoDB query builder
```

## Development Setup

```bash
# Prerequisites: Java 8+, Maven 3.6+, MongoDB 4.4+

# Clone your fork
git clone https://github.com/YOUR_USERNAME/sooncode.domain.git
cd sooncode.domain

# Build
mvn clean install -DskipTests

# Run tests (requires MongoDB running on localhost:27017)
mvn test
```

### Test Configuration

Default test assumes MongoDB on `localhost:27017`. To customize:

```java
// In your test setup
MongoEventSourcingRepository repository = 
    new MongoEventSourcingRepository("your_host", your_port, "your_database");
```

## Project Structure

```
src/
├── main/java/com/sooncode/project/core/
│   ├── annotations/          # Runtime annotations (@EventBoot, @Lookup, etc.)
│   ├── finder/               # Fluent query API (Finder<T>)
│   ├── generic/              # Built-in events (BasicAddEvent, etc.)
│   ├── model/                # Core: DomainModel, DomainEvent, Repository
│   ├── monitor/              # Pub/sub event/entity notification
│   ├── repository/mongo/     # MongoDB persistence implementation
│   ├── session/              # Unit of work support
│   ├── utils/                # Reflection, type conversion, JSON
│   └── validator/            # Validation framework
└── test/java/com/sooncode/project/core/
    └── ...
```

## Getting Help

- Open a [Discussion](https://github.com/soonboot/sooncode.domain/discussions)
- Ask in issues tagged `question`

---

Thank you for your contribution! 🎉
