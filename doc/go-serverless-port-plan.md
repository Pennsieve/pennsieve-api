# Plan: Porting `pennsieve-api` to Go Serverless

Status: **Planning / Exploratory** — scoping and investigation only, no code changes.
Last updated 2026-08-06.

This is a living document. It is expected to evolve as decisions are made and as
endpoint usage data comes in; the open questions at the bottom are the parts most
likely to change. Findings here were measured against this repo on 2026-08-05.

The effort itself is cross-cutting: it touches this repo, `gateway`,
`datasets-service`, `packages-service`, and `pennsieve-go-core`.

---

## Goal

Re-implement this Scala monolith as Go serverless functions, peeling off one or two
controllers at a time into the Go services that already own those domains.

---

## Repo inventory (measured, not estimated)

| Module | Scala LOC | Endpoints |
|---|---:|---:|
| `api/` | 48,351 | **188** |
| `core/` | 35,895 | — |
| `core-models/` | 10,534 | — |
| `jobs/` | 3,509 | — |
| `admin/` | 3,389 | — |
| `authorization-service/` | 2,590 | — |
| tests (all modules) | 46,802 | 134 files |

`core/` breaks down as managers 11.3k, Slick db tables 5.9k, aws 2.5k, core 1.6k.

Endpoint distribution is heavily skewed:

| Controller | LOC | Endpoints |
|---|---:|---:|
| `DataSetsController` | 5,274 | 63 |
| `OrganizationsController` | 1,746 | 27 |
| `TimeSeriesController` | 1,645 | 21 |
| `PackagesController` | 1,172 | 12 |
| `UserController` | 752 | 10 |

Whole-repo effort estimate: **~12–24 engineer-months.**

### Two findings that make this tractable

Both contradict what this repo appears to say at first glance. Re-verify rather than
re-litigating them from the README:

1. **There is no graph store.** The README describes `core` as "middleware for
   interacting with the graph store." That is **stale**. There are zero references to
   Neo4j, Neptune, Gremlin, or Cypher anywhere in the repo — it is all Slick against
   Postgres. This removes the single largest feasibility risk.
2. **There are no WebSockets in the API.** Checked specifically, because timeseries is
   usually where a Lambda port dies. `TimeSeriesController` uses Akka `Source` only for
   internal aggregation windows, not for client streaming. Nothing here structurally
   rejects a request/response Lambda model.

(The README is also stale on branching — it references `DEVELOPMENT` and `master`
branches that no longer exist on the remote. Worth a separate cleanup.)

### The main cost driver the LOC count understates

The codebase is `EitherT[Future, CoreError, A]` monad-transformer-stacked throughout.
There is no mechanical translation to Go's `(T, error)`; every function body is
rewritten by hand, and the error taxonomy in `core/domain/` must be re-expressed as
sentinel errors or a typed error interface.

Separately, the **46.8k lines of tests are not portable** — they use
`testcontainers-scala`. They must be rewritten, and without them there is no way to
prove the Go port is behavior-identical to the Scala one. Budget this as real work,
comparable in size to the API port itself.

---

## Scope decisions — NOT being ported

Decided 2026-08-06.

- **DataCanvas — dropped.** 1,053 LOC / 16 endpoints. Dormant; never fully realized,
  and the necessary frontend work was never scoped, designed, or implemented. A
  similar, newer effort is in the ideation stage with the PI.
- **`etl-data-cli` — dropped.** Obsolete. 2,088 LOC.
- **`migrations` and the migration modules — dropped.** Schema changes are now managed
  in the `pennsieve-db-migrations` repo. ~1,103 LOC.

That is ~4.2k LOC removed before any work starts. **More controllers are expected to be
deprecated and left behind** — the usage telemetry below is how that list gets decided
rather than guessed.

---

## Step 1 (do first): endpoint usage telemetry

This gates everything else and requires no new infrastructure. The data is already
being logged; it only needs to be queried.

- `gateway` is nginx + njs. `templates/nginx.conf.tmpl` (~line 127) already defines
  `log_format plain escape=json` and a second `log_format audit`, emitting
  `request.method`, `request.uri`, `response.statusCode`, `traceId`, `time_iso8601`,
  and upstream timings.
- `js/audit_logging.js` decodes the JWT payload via `extractJwtPayload`, which provides
  **user and org attribution** on top of the raw route.

**Task:** confirm gateway access logs are reaching CloudWatch Logs, then write a
CloudWatch Logs Insights query that normalizes `request.uri` into route templates and
counts by route × method × org over 30–90 days. Publish the top-N to a dashboard.

**Normalization gotcha — budget about a day.** The query must handle:

- Pennsieve node IDs — `/datasets/N:dataset:<uuid>/banner` → `/datasets/:id/banner`
- plain integer IDs
- the regex-routed presign endpoint, `^\/(.*)/files/(.*)/presign/(.*)`
  (`PackagesController.scala:1000`)

Get this wrong and the long tail looks far busier than it is.

**Why it comes first:** with 63 dataset endpoints, expect a substantial tail at
effectively zero calls in 90 days. Those get **deleted, not ported** — the cheapest
possible way to shrink the largest file in this repo, and the evidence base for
deprecating further controllers.

---

## Destination services: ready, with one gap

**`datasets-service`** — `lambda/service/handler/` plus `api/{models,service,store,logging}`,
APIGatewayV2 events, `authorizer.ParseClaims`. Currently handles `/shared-datasets`,
`/trashcan`, `/manifest`.

**`packages-service`** — same layout, plus CloudFront signed-URL handling with tests.
Currently handles assets, download, restore.

`pennsieve-go-core` (~7.3k LOC) already provides `pkg/queries/pgdb/` covering datasets,
packages, files, organizations, users, tokens, contributors, teams, storage, and
feature flags — with tests. The port target is not a blank page.

### Per-org schema isolation

Slick parameterizes the Postgres schema per organization at query-construction time —
`new DatasetsTable(organization.schemaId, _)` (`core/.../db/DatasetsTable.scala:100`).
Go has no equivalent, and getting this wrong is a **cross-tenant data leak**, which
matters directly for the HIPAA work.

`datasets-service/api/store/cross_org_postgres.go` appears to already address this.
**Caveat: this was inferred from the filename and service structure — the
implementation has not been reviewed.** Read it before relying on it as the tenant
isolation argument, and give it a dedicated test suite either way.

### Gap — first code task

`datasets-service` routes by string comparison:

```go
// lambda/service/handler/handler.go:46
if path == "/shared-datasets" {
```

That is fine for three routes and will not survive forty. **Build a real path-template
router with parameter extraction in both services before porting a single endpoint.**

---

## How the 63 `/datasets` endpoints cluster

| Cluster | Count | Destination |
|---|---:|---|
| Core CRUD (`/`, `/:id`, `/paginated`, `/:id/packageTypeCounts`) | 7 | `datasets-service` |
| Collaborators (users / teams / orgs / owner / permission / role) | 15 | **Blocked — see below** |
| Publication (request, cancel, reject, accept, release, preview, DOI, data-use-agreement) | 14 | Own service; entangled with `discover-publish` + `doi-service` |
| Contributors + collections | 8 | Existing contributor / collections services |
| Assets (banner, readme, changelog) | 6 | `datasets-service` |
| Changelog (timeline, events, trigger) | 4 | **Defer** — collides with the changelog-events Go migration |
| Webhooks | 3 | `integration-service` |
| Misc (status-log, ignore-files, packages listing) | 6 | `datasets-service` |

### Key dependency: the collaborators cluster

Those 15 endpoints **are** authorization logic, and Phase II of the auth refactor will
redefine exactly that. Porting them now means porting them twice. Sequence them last
among the dataset work, or fold them explicitly into the authZ effort.

Note that `PUT /:nodeId/collaborators/external` (`DataSetsController.scala:2103`) is the
Guest/Viewer external-collaborator path already analyzed as part of the share-link
design work.

---

## Proposed sequence

1. **Gateway-log usage telemetry** — ~1 week. Produces the delete-list.
2. **Path-template router in both Go services** — ~1 week.
3. **`/packages` pilot** — 12 endpoints, ~4–6 weeks. Small enough to finish and learn
   from; proves the pattern end-to-end including gateway cutover and rollback.
   `download-manifest` and `presign` overlap what `packages-service` already does.
4. **Dataset core CRUD + assets** — ~13 endpoints, ~6–8 weeks.
5. **Publication cluster** — own service, coordinated with discover/doi, ~8–12 weeks.
6. **Collaborators** — gated on Phase II authZ.

**Cutover mechanism:** strangler-fig at the `gateway`. Each cluster's cutover is an
nginx `location` block change in the gateway templates, so rollback is a config revert
rather than a redeploy. Same pattern as the `integration-service` Go port and the
changelog-events migration.

**Process:** steps 1–2 are small enough to simply do. The `/packages` pilot sets the
pattern for everything after it, so it warrants its own design doc and PR for team
review before code.

---

## Open question — decide before the pilot

**Is this a faithful port, or a v2 API contract?**

The `/datasets/:id/collaborators` family has three parallel shapes (users, teams,
organizations) with inconsistent request bodies, and the presign endpoint uses a regex
route. A faithful port carries all of that forward permanently. This decision changes
whether the pilot's rule is "match Scala exactly" or "clean up while moving," so it
needs an answer before step 3 starts.

---

## Other notes

- **Lambda + Postgres means RDS Proxy is mandatory.**
- Some work genuinely does not want to be serverless: `DeleteJob` (811 LOC), the
  storage cache population job, and bulk dataset operations. Expect an ECS/Fargate tail
  alongside the Lambda majority.
- Toolchain: Pennsieve build executors cap at Go 1.23 (not 1.24).
- `jobs/` is the best serverless fit in the repo, but coordinate with the
  changelog-events Go migration so effort isn't spent porting something already
  intended for retirement.
