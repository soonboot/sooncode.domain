# Security Policy

## Supported Versions

| Version | Supported          |
|---------|--------------------|
| 1.x     | ✅ Active support  |

## Reporting a Vulnerability

We take the security of sooncode.domain seriously. If you believe you've found a security vulnerability, please follow these steps:

1. **Do not** open a public issue on GitHub.
2. Send a report to the project maintainers via [GitHub Security Advisories](https://github.com/soonboot/sooncode.domain/security/advisories/new).
3. Include a detailed description of the issue, steps to reproduce, and potential impact.
4. Allow time for the issue to be investigated and addressed before public disclosure.

You should receive a response within 48 hours. We will keep you informed of the progress toward a fix and public disclosure timeline.

## Security Considerations

### MongoDB Connection

- Always use **authenticated connections** in production.
- Never hardcode credentials in source code — use environment variables or secure configuration.
- Restrict MongoDB network access to trusted hosts only.

### Event Data

- Event data is stored serialized as JSON via FastJSON in MongoDB.
- Ensure sensitive data is encrypted at rest in your MongoDB deployment.
- Consider event schema versioning for long-lived event streams.

### Concurrency

- The framework uses optimistic concurrency control. Handle `CheckForConcurrencyException` appropriately in your application layer.

Thank you for helping keep sooncode.domain and its users safe.
