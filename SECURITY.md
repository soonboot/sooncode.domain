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

### Database Connections (MongoDB / PostgreSQL)

- Always use **authenticated connections** in production.
- Never hardcode credentials in source code — use environment variables or secure configuration.
- Restrict database network access to trusted hosts only.
- PostgreSQL: the auto-create path connects via the maintenance database (`postgres` / `template1`)
  and requires `CREATEDB`; grant the least privilege needed, or create the database manually
  and pass an existing `DataSource` to `PgConnection`.

### Event Data

- Event data is stored serialized as JSON via FastJSON (MongoDB documents / PostgreSQL JSONB).
- Ensure sensitive data is encrypted at rest in your MongoDB or PostgreSQL deployment.
- Consider event schema versioning for long-lived event streams.

### Concurrency

- The framework uses optimistic concurrency control. Handle `CheckForConcurrencyException` appropriately in your application layer.

Thank you for helping keep sooncode.domain and its users safe.
