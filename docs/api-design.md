# URL Shortener — API Design (v1)

Status: draft for implementation. Base URL in examples: `https://sho.rt`
(locally: `http://localhost:8080`).

**v1 decisions:** DynamoDB · no auth · click analytics in scope · 7-char base58 codes · runs
locally first · every link expires · duplicates are fine · clicks stream through Kafka · no target
validation · plain JSON errors.

Two surfaces, deliberately separated:

| Surface | Path shape | Audience |
|---|---|---|
| Management API | `/api/v1/links…` | clients creating and managing links |
| Redirect | `/{code}` | end users clicking links |

Keeping the redirect at the root is what makes the short URL short. It also means `/{code}` shares a
namespace with every other root path, so reserved words are part of the contract (§5).

> **No auth in v1.** Every endpoint is open, including `PATCH` and `DELETE`. That is workable while
> this runs on localhost and is a security hole the moment it is reachable from the internet:
> anyone could delete or re-expire anyone's link. §6 lists exactly what has to land before a public
> deploy. The API shape below is chosen so that adding auth later is additive — no endpoint moves,
> no field is removed.

---

## 1. Resource

A **link** is the resource. `code` is its identity — the last path segment of the short URL.

```json
{
  "code": "aX9k2Qp",
  "shortUrl": "https://sho.rt/aX9k2Qp",
  "url": "https://example.com/a/very/long/path?utm_source=x",
  "custom": false,
  "createdAt": "2026-08-31T09:14:02Z",
  "expiresAt": "2026-09-07T09:14:02Z",
  "status": "ACTIVE"
}
```

`expiresAt` is **never null** — every link expires (§4). `status` ∈ `ACTIVE | EXPIRED | DELETED`,
derived at read time (`expiresAt <= now`) — never a stored value that a background job has to keep
truthful.

---

## 2. Endpoints

### 2.1 Create — `POST /api/v1/links`

```http
POST /api/v1/links
Content-Type: application/json

{
  "url": "https://example.com/a/very/long/path",
  "alias": "launch-2026",                 // optional custom code
  "expiresAt": "2026-09-07T09:14:02Z"     // optional; or "ttlSeconds": 604800
}
```

`201 Created`, `Location: https://sho.rt/launch-2026`, body = the link resource.

Rules:
- `url` — required and non-blank. **Nothing else is checked in v1** — no scheme allowlist, no
  length cap, no private-host rules. Whatever is sent is what gets stored and redirected to.

  Deferred rather than forgotten. What the checks were for, in the order they are worth adding
  back:
  1. **A 2048-char cap.** The one with an operational reason: a DynamoDB item is capped at 400 KB,
     so a pathological URL fails the write with an SDK error instead of a clean `400`.
  2. **`http`/`https` only.** Blocks `javascript:` and `data:` targets. Browsers already refuse to
     follow those from a `Location` header, so this is defence in depth rather than a live hole.
  3. **Public-host rules** (`localhost`, RFC1918, link-local, `.internal`). Never an SSRF control —
     the service redirects users to targets, it never fetches them — so this is about link quality
     and abuse, and it is the least urgent of the three.
- `alias` — optional, and **unvalidated in v1** beyond being non-blank (§5). `409` if taken.
- `expiresAt` and `ttlSeconds` are mutually exclusive; both is `400`. `expiresAt` must be in the
  future. **Absent = 30 days from now** (`app.default-ttl-days`). There is no "never expires" —
  §4.
- Reject bodies > 8 KB before parsing.

Errors: `400` (validation) · `409` (alias taken) · `413` · `429`.

**Shortening a short link is allowed**, and the target is **flattened at create time**: if `url`
points at our own domain and resolves to a known code, the new link stores that code's *target*,
not the short URL. So `sho.rt/B → sho.rt/A → example.com` is stored as `sho.rt/B → example.com`.
Recognising one of our own URLs is the only URL parsing v1 does.

Flattening rather than chaining is what makes this safe: a stored chain can cycle (`A → B → A`),
which browsers only stop by giving up after ~20 hops, and every extra hop is another lookup on the
hottest path. Flattening cannot cycle, by construction. The trade-off is that clicks on `B` never
touch `A`, so they are not counted there, and later changes to `A` do not propagate to `B`.

**No idempotency keys.** Two identical POSTs create two distinct codes, and that is fine — storage
is not the constraint. Worth knowing what this gives up: `Idempotency-Key` existed to stop a client
*retrying a timed-out request* from silently creating a second link. Without it, a flaky network
produces duplicate codes pointing at the same target. Both work; the duplicate is just litter.

### 2.2 Redirect — `GET /{code}`

```http
GET /aX9k2Qp
→ 302 Found
  Location: https://example.com/a/very/long/path
  Cache-Control: private, no-store
  Referrer-Policy: no-referrer
```

- **302, not 301 — and this is the opposite of the intuition.** `301 Moved Permanently` is the one
  browsers cache aggressively and effectively forever: after the first visit the browser jumps
  straight to the target and *never contacts us again*. `302 Found` is the one that comes back
  every time. Since every requirement here depends on being asked again — counting clicks, honouring
  expiry, honouring deletion — the answer is 302 plus `Cache-Control: private, no-store`.
  301 is the right choice only when link equity for SEO outweighs all of that, and it costs
  per-click analytics: you would count the first click per browser and nothing after it.
- `HEAD /{code}` behaves identically, without a body.
- One key lookup, no joins, **no synchronous write**. The click is handed to an in-memory queue and
  the response returns immediately (§7).
- `404` unknown or deleted · `410` expired (during the grace window, §4) · `429` on abuse.
- Query strings on the short URL are **not** forwarded by default. Silently merging query params
  breaks targets that use them in signatures; forwarding can be a per-link opt-in later.
- Only ever **one** hop: targets are flattened at create time (§2.1), so the redirect never chases
  a chain.

### 2.3 Inspect — `GET /api/v1/links/{code}`

`200` with the link resource, including expired and (within grace) deleted ones — the creator needs
to see *why* a link stopped working, so this deliberately does not `410`. `404` if it never existed.

### 2.4 Update — `PATCH /api/v1/links/{code}`

Only expiration is mutable:

```json
{ "expiresAt": "2026-12-01T00:00:00Z" }   // extend or shorten
{ "expiresAt": null }                      // remove expiry
```

`expiresAt` cannot be null (§4) and must be in the future. Extending it on an expired or
soft-deleted link **renews** it: `deletedAt` is cleared and the link becomes `ACTIVE` again, with
its code, target and click history intact.

`200` with the updated resource. Deliberately **not** mutable: `url`. A short link that silently
repoints is a phishing primitive, and with no auth in v1 it would be an open one. Renewal is the
reason that matters: a link that can come back from the dead must come back pointing where it always
pointed.

### 2.5 Delete — `DELETE /api/v1/links/{code}`

`204 No Content`, idempotent (`204` even if already deleted). Soft delete — nothing is removed, and
a second call does not rewrite the original `deletedAt`. `GET /{code}` then returns `410`, and the
link can be renewed later (§2.4). Click history is retained.

### 2.6 List — `GET /api/v1/links?cursor=&limit=&status=`

Newest first, cursor-paginated (`limit` default 20, max 100). With no auth there is no owner to
scope by, so this lists *every* link in the system — an operator/dev convenience, not a user-facing
feature. When auth lands it becomes owner-scoped with no change to the request or response shape.

```json
{ "items": [ … ], "nextCursor": "eyJrIjoi…" }
```

Offset pagination is a trap here — new links land at the head constantly.

### 2.7 Stats — `GET /api/v1/links/{code}/stats?from=&to=`

Default range: last 30 days. `from`/`to` are dates (`YYYY-MM-DD`), inclusive, UTC.

```json
{
  "code": "aX9k2Qp",
  "totalClicks": 1420,
  "range": { "from": "2026-08-02", "to": "2026-08-31" },
  "clicksInRange": 611,
  "daily": [
    { "date": "2026-08-30", "clicks": 12 },
    { "date": "2026-08-31", "clicks": 47 }
  ],
  "topReferrers": [
    { "host": "twitter.com", "clicks": 830 },
    { "host": "(direct)", "clicks": 410 }
  ],
  "devices": { "desktop": 900, "mobile": 480, "tablet": 20, "bot": 20 }
}
```

Counts are **eventually consistent** — up to one flush interval (~2s) behind live. Days with zero
clicks are omitted from `daily` rather than zero-filled; the client renders the gaps.

---

## 3. Status codes

| Code | When |
|---|---|
| 200 | inspect, update, list, stats |
| 201 | link created |
| 204 | deleted |
| 302 | redirect resolved |
| 400 | malformed body, bad URL, bad alias format, past or null `expiresAt`, both `ttlSeconds` and `expiresAt` |
| 404 | unknown code — and only that |
| 409 | alias taken |
| 410 | expired or soft-deleted (renewable, §4) |
| 413 | body too large |
| 429 | rate limited (with `Retry-After`) |

No `401`/`403` in v1 — they arrive with auth (§6).

Errors are plain JSON — one string, with the status code carrying the meaning:

```json
{ "error": "alias is already taken: launch-2026" }
```

RFC 9457 `application/problem+json` was the v1 plan and is deferred. It buys machine-readable
`type` URIs and per-field validation detail, which matter once a client has to *branch* on the
failure rather than show it. Spring's `ProblemDetail` makes the switch small when that day comes;
until then the status code plus a sentence is what a human debugging with curl actually reads.

---

## 4. Lifecycle

**Every link expires.** `expiresAt` is mandatory; absent from the request it defaults to 30 days
out (`app.default-ttl-days`). There is no permanent link, which means there is no such thing as a
link nobody is accountable for.

The states, and how a link moves between them:

```
                 expiresAt <= now                sweeper
   ACTIVE  ─────────────────────────►  EXPIRED  ─────────►  DELETED
      ▲                                    │                   │
      └────────────────────────────────────┴───────────────────┘
                    PATCH expiresAt (renew) - 2.4
```

- **ACTIVE → EXPIRED** is derived, not written: a link is expired the instant `expiresAt <= now`,
  evaluated on read. Correctness never waits on a job, and never on DynamoDB TTL, which deletes
  *within 48 hours* of a timestamp rather than at it.
- **EXPIRED → DELETED** is a soft delete, by the sweeper or by `DELETE` (§2.5). Soft: `deletedAt` is
  set and nothing is removed.
- **Anything → ACTIVE** is a renewal — `PATCH` with a future `expiresAt` clears `deletedAt` and the
  link is live again, same code, same target, same click history. Being able to come back is why
  `url` is immutable (§2.4).

`DELETED` beats `EXPIRED` when both apply: they are two different reasons but the same answer over
the wire, and the model should say which one actually happened.

**Over the wire this is two answers, not four:** `ACTIVE` redirects, `EXPIRED` and `DELETED` both
return `410 Gone`, and only a code that never existed returns `404`. There is no grace window and no
`410`-then-`404` transition, because nothing is ever hard-deleted — a link that is gone stays
`410` forever, and might yet be renewed.

**Nothing is ever deleted from the `links` table, and codes are never reissued.** Reissuing a code
silently repoints every printed link and QR still carrying it. At ~300 bytes/item, 10⁸ links is
~30 GB — roughly $7/month to permanently rule out that class of bug.

Clock is UTC everywhere; `expiresAt` is ISO-8601 with an explicit offset.

---

## 5. The code namespace

One flat namespace shared by generated codes and custom aliases; uniqueness is enforced on the exact
`code`, so the two can never collide.

**Generated: 7 characters of base58.**

```
123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz
```

Base58 is base62 minus `0`, `O`, `I`, and `l` — the glyphs people transcribe wrongly from a screen,
a whiteboard, or a printed flyer. Losing four characters costs very little:

| | keyspace | at 10⁸ links |
|---|---|---|
| base62⁷ | 3.5 × 10¹² | 0.0029% collision on insert |
| **base58⁷** | **2.2 × 10¹²** | **0.0045% collision on insert** |

Both are noise against the conditional put that catches collisions anyway (§8.2), so the
transcription win is free.

Codes are drawn from a **CSPRNG, not a counter**. Sequential codes let anyone walk the entire link
table — with no auth in v1, that is the difference between a private link and a public directory.
Insert with a bounded retry (~3 attempts) on collision.

**Custom aliases: taken as given in v1.** No format rule, no case rule, no reserved list — the
alias is whatever the caller sends. Uniqueness is still absolute, because it comes from the
conditional put (§8.2) rather than from any validation.

Two things this lets through, both of which produce a link that is stored but does not work:

- **An alias containing `/`, whitespace or `#`.** `GET /{code}` matches a single path segment, so
  `my/alias` can be created and can never be resolved.
- **An alias that shadows a real route.** Anything mapped more specifically wins — `/api/v1/links`
  and `/actuator/health` are routes before they are codes.

Neither corrupts data; both make a link that silently 404s. The fix, when it is worth it, is a
format rule plus a reserved list in config — see the git history for the version that had one.

---

## 6. Cross-cutting

**Auth — none in v1.** Before this is reachable from anything but localhost:

1. An identity on create (API key header), stored as `ownerId` on the link.
2. `PATCH`/`DELETE`/stats scoped to the owner — non-owner gets `404`, not `403`, so the API does not
   leak which codes exist.
3. `GET /api/v1/links` (§2.6) scoped to the owner, or removed. As specified it enumerates every link
   in the system.

All three are additive against the shape above.

**Rate limits:** per IP, since there is no key to count against — on creates (e.g. 60/min) and on
`/{code}`. Respond `429` with `Retry-After` and `RateLimit-*` headers. This is the *only* thing
standing between a public v1 and an abuse queue, so it is not optional even without auth.

**Versioning:** path-based `/api/v1`. The redirect path is unversioned and must never break.

**Observability:** actuator is on the classpath — expose `/actuator/health` only, and keep
`actuator` on the reserved list.

---

## 7. Click analytics

Three constraints shape this: the redirect must not get slower, a viral link must not melt a single
DynamoDB item, and one click must not cost one write.

### 7.1 Path of a click

1. Redirect handler resolves the code and returns `302`. Done — nothing below is on the response path.
2. It publishes a small click record to a **Kafka topic, partitioned by `code`**. Publishing is
   fire-and-forget from the request's point of view: a local buffer absorbs the call and a
   publisher thread drains it, so a slow or unavailable stream can never add latency to a redirect.
3. A consumer reads the stream, **aggregates by (code, day, dimension)** over a short window, and
   issues one `UpdateItem … ADD clicks :n` per bucket.

Step 3 is the whole design. A link taking 5,000 clicks in a window becomes **one** write instead of
5,000 — which matters twice over: DynamoDB caps a single item at roughly 1,000 WCU/s, so the naive
per-click counter both costs a fortune and falls over exactly when a link succeeds.

Partitioning by `code` is what makes Kafka worth its weight here: every click on one link lands in
one consumer's batch, so a viral link's clicks collapse into a single `ADD` instead of one per
consumer instance. That is the same hot-item problem as §8.3, solved on the way in. Ordering is
irrelevant — the aggregation is commutative addition — so partitioning is bought purely for
locality.

Retention is the other reason: if the aggregation logic is ever wrong, stats are recomputed by
replaying the topic rather than written off.

**At-least-once means over-counting, and that is the choice made here.** `ADD clicks :n` is not
idempotent, so a redelivered batch counts twice. Offsets are committed *after* the DynamoDB write,
which loses nothing and occasionally counts a batch twice — the right way round for analytics, where
a missing click is worse than a duplicated one. Committing first would invert it. If exact counts
are ever needed, dedupe on a record id within a window; do not switch the commit order.

The remaining exposure is the local publish buffer — a process killed mid-flight drops whatever it
had not yet published.

**The seam:** the redirect path depends on a `ClickRecorder` interface, nothing more. v1 runs an
in-process implementation (buffer + aggregate + write) so the whole thing works on a laptop with no
extra infrastructure; swapping in the Kafka producer is one bean, and §7.3 does not change either
way.

### 7.2 What is recorded

| Captured | Reduced to | Why |
|---|---|---|
| timestamp | UTC date bucket | daily series is what a dashboard shows |
| `Referer` | registrable host, or `(direct)` | full referrer URLs carry query params and private paths |
| `User-Agent` | `desktop` / `mobile` / `tablet` / `bot` | the class is the useful part |
| IP | **not stored** | v1 has no consent flow; geo can come later, hashed and coarse |

Bots are **counted separately, not dropped**, so `totalClicks` stays explainable when someone asks
why the number jumped.

### 7.3 Table `clicks`

| Attribute | Type | Notes |
|---|---|---|
| `code` | S | **partition key** |
| `stat` | S | **sort key** — `TOTAL`, `DAY#2026-08-31`, `REF#twitter.com`, `UA#mobile` |
| `clicks` | N | atomic counter |

`UpdateItem` with `ADD clicks :n` needs no prior read and creates the item if absent — the whole
write path is one idempotent-shaped operation per bucket.

Reads for §2.7: one `Query` on `code` with `stat BETWEEN DAY#<from> AND DAY#<to>` for the series,
one on `begins_with(stat, "REF#")` for referrers, one on `UA#` for devices, plus `TOTAL`.

Click history outlives the link (§2.5) — deleting a link does not erase what already happened.

---

## 8. DynamoDB data model

Two tables, on-demand capacity: `links` and `clicks` (§7.3). Single-table design buys nothing here —
the access patterns are few and the two tables have completely different write shapes.

### 8.1 Table `links`

| Attribute | Type | Notes |
|---|---|---|
| `code` | S | **partition key**, no sort key |
| `targetUrl` | S | ≤ 2048 |
| `createdAt` | S | ISO-8601 UTC — lexicographic order == chronological order |
| `expiresAt` | S | **not null** — every link expires (§4) |
| `deletedAt` | S | nullable, soft delete |
| `custom` | BOOL | alias vs generated |
| `listPk` | S | GSI partition key, `LINKS#<0-9>` (see below) |

**GSI `all-links-index`** — PK `listPk`, SK `createdAt`, projection `INCLUDE (targetUrl, expiresAt,
deletedAt, custom)`. Backs §2.6 with `ScanIndexForward=false`.

`listPk` is a random shard `LINKS#0`…`LINKS#9` assigned at write time. A constant value would be
simpler but funnels every write in the system into one index partition; ten shards means the list
query fans out to ten `Query` calls and merges, which is fine at v1 volume and does not have a
ceiling built into it. Nothing else needs an index.

**No TTL attribute anywhere.** Per §4, expiry is semantic and codes are retired forever. TTL would
delete items behind the API's back on DynamoDB's schedule, free codes for reuse, and make renewal
(§2.4) impossible — there would be nothing left to renew.

### 8.2 Access patterns

| # | Pattern | Operation |
|---|---|---|
| 1 | resolve `code` → target (hot path) | `GetItem(code)` |
| 2 | create, code must be unused | `PutItem` + `ConditionExpression: attribute_not_exists(code)` |
| 3 | inspect / patch / delete | `GetItem` / `UpdateItem` |
| 4 | list all links, newest first | `Query` × 10 shards on `all-links-index`, merged |
| 5 | renew | `UpdateItem` setting `expiresAt`, removing `deletedAt` |
| 6 | record clicks | `UpdateItem … ADD clicks :n` on `clicks` (§7.3) |
| 7 | read stats | `Query` on `clicks` by `stat` prefix |

Pattern 2 is the entire uniqueness story — the conditional put *is* the unique constraint, and it is
atomic. A rejected condition means "alias taken" → `409`, or "regenerate" → bounded retry. There is
no read-then-write race to defend against.

`code` is a high-cardinality random string, so partitions spread evenly by construction.

### 8.3 Read consistency on the redirect path

`GetItem` on the redirect is **eventually consistent** — half the RCU cost on by far the hottest
operation. The one visible gap is create-then-immediately-click, so: on a miss, do a single
strongly-consistent retry before returning `404`. Misses are rare, the extra cost is noise, and
nobody ever sees a `404` on a link they just made.

A viral link is a hot partition. Adaptive capacity absorbs moderate skew; beyond that, cache in
front (in-process LRU first, DAX only if that is not enough). Link rows are immutable except for
expiry, so a short cache TTL is safe.

## 9. Running locally

Everything runs on the laptop first — no AWS account needed to build or test v1.

**DynamoDB Local** (`amazon/dynamodb-local`) over docker-compose on port 8000. Not LocalStack: we
need exactly one AWS service, and the DynamoDB Local image boots in about a second where LocalStack
takes several — that difference is felt on every test run.

```
app.base-url=http://localhost:8080
aws.dynamodb.endpoint=http://localhost:8000
aws.region=us-east-1                    # DynamoDB Local ignores it; the SDK requires it
```

Credentials: DynamoDB Local accepts anything, but the SDK refuses to start without a provider —
static dummy credentials in the `local` profile.

**Table bootstrap:** an `ApplicationRunner` creates `links` and `clicks` (plus the GSI) if absent,
gated on `app.bootstrap-tables` — true in the `local` profile and in tests, false
everywhere else. A property rather than `@Profile("local")` so tests can reuse it without pretending
to be the local profile. In a deployed environment tables come from IaC: an app that creates its own
tables will eventually create the wrong one.

**Tests:** Testcontainers with the same `amazon/dynamodb-local` image, wired through
`TestcontainersConfiguration`, running `-inMemory` so each run starts empty. Tests activate a `test`
profile, not `local` — the endpoint has to come from the container's mapped port, never a fixed
`localhost:8000`. Same engine as local dev and as production, so conditional puts and `ADD` counters
behave identically in all three.

**Kafka, when step 8 lands:** Redpanda in the same docker-compose — one Kafka-compatible container,
about a second to boot, no ZooKeeper. Tests use `spring-kafka-test`'s embedded broker rather than a
container, since the producer and consumer logic is what is under test, not the broker.

**One caveat that bites:** DynamoDB Local has no throttling, no partition limits, and no
eventual-consistency lag. It will never reproduce a hot-partition or stale-read problem, so §7.1 and
§8.3 have to be reasoned about rather than discovered by running the thing.

---

## 10. Worked examples

```bash
# create with a generated code
curl -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/a/very/long/path"}'

# custom alias that expires in a week
curl -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/launch","alias":"launch-2026","ttlSeconds":604800}'

# follow the redirect
curl -i http://localhost:8080/launch-2026

# extend expiry
curl -X PATCH http://localhost:8080/api/v1/links/launch-2026 \
  -H 'Content-Type: application/json' \
  -d '{"expiresAt":"2026-12-01T00:00:00Z"}'

# stats
curl 'http://localhost:8080/api/v1/links/launch-2026/stats?from=2026-08-01&to=2026-08-31'
```

---

## 11. Config knobs

| Key | Default | §|
|---|---|---|
| `app.base-url` | `http://localhost:8080` | 1 |
| `app.code-length` | `7` | 5 |
| `app.code-alphabet` | base58 | 5 |
| `app.default-ttl-days` | `30` | 4 |
| `app.reserved-codes` | see §5 | 5 |
| `app.list-shards` | `10` | 8.1 |
| `app.analytics.flush-interval` | `2s` | 7.1 |
| `app.analytics.queue-capacity` | `10000` | 7.1 |
| `app.ratelimit.creates-per-minute` | `60` | 6 |

---

## 12. Open questions

1. **What runs Kafka in production** — MSK, Confluent Cloud, or self-managed. Local dev is settled
   (Redpanda, §9), and v1 runs the in-process `ClickRecorder`, so this is only due when clicks
   actually move to the topic. Worth knowing the floor is tens of dollars a month either way.
2. **Who runs the sweeper** that moves `EXPIRED` to `DELETED` (§4)? A scheduled task in the app is
   simplest and fine for one instance; it needs a lock or a leader the moment there are two.
3. What is the real short domain, for when this leaves localhost?
4. Does `GET /api/v1/links` (§2.6) survive to production, or is it a local-only debug endpoint?
   With no auth it is a full directory of every link in the system.
5. Table provisioning when the time comes — CDK, Terraform, or console?
6. Any UI in scope, or is v1 API-only?
