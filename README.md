# shortenurl

A URL shortener: short codes, custom aliases, link expiration, and click analytics.

- **API design:** [docs/api-design.md](docs/api-design.md) — the contract, the DynamoDB data model,
  and the reasoning behind both. Read this before adding endpoints.
- **v1 decisions:** DynamoDB · no auth · click analytics in scope · 7-char base58 codes · local first.

## Running locally

Everything runs on the laptop. No AWS account is needed to build, run, or test.

Start DynamoDB Local:

```bash
docker compose up -d
```

Run the app (the `local` profile is active by default, and creates the three tables if missing):

```bash
./gradlew bootRun
```

Run the tests (Testcontainers starts its own throwaway DynamoDB — `docker compose` need not be up):

```bash
./gradlew test
```

Stop DynamoDB Local, keeping the data in `./.dynamodb-data`:

```bash
docker compose down
```

### Inspecting the local database

The container speaks the real DynamoDB API, so the AWS CLI works against it:

```bash
aws dynamodb list-tables --endpoint-url http://localhost:8000 --region us-east-1
```

Any credentials will do — DynamoDB Local ignores them, but the CLI insists on some being present.
To start from scratch, `docker compose down` and delete `./.dynamodb-data`.

## Layout

| Path | |
|---|---|
| `docs/api-design.md` | the design, kept in step with the code |
| `docker-compose.yml` | DynamoDB Local |
| `src/main/.../config/AppProperties.java` | every knob from design doc §11 |
| `src/main/.../config/DynamoDbConfig.java` | one switch between DynamoDB Local and real AWS |
| `src/main/.../config/TableBootstrap.java` | creates the tables from design doc §8 |

## Try it

```bash
curl -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/a/very/long/path"}'

curl -i http://localhost:8080/<code>
```

## What is not built yet

The list endpoint, click analytics and the stats endpoint, and rate limiting.
See [docs/api-design.md](docs/api-design.md).
