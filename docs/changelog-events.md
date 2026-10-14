# Changelog Events — Reference

pennsieve-api (and other Pennsieve services) emit **Changelog Events** for most mutating actions: dataset metadata edits, file/package operations, model/record changes, permission changes, the publishing workflow, custom integration-defined events — and, per the design in Part 2 of this doc, organization- and team-level administrative actions. These events are the data User Notifications (integration-service + Pennsieve App) and the existing webhook-delivery system both consume.

This doc has two parts:
- **Part 1 — Dataset-scoped events (shipped).** The existing `ChangelogEvent` system: every event name, its category, its payload shape, and how it reaches SNS. Source of truth for what's live today.
- **Part 2 — Organization-scoped events (design, not yet implemented).** A parallel capability for actions that have no dataset to be scoped to — org/team membership, and a few dataset-adjacent-but-workspace-wide notification needs. Open for team review; see `pennsieve-api#405`.

A prior Google Sheet (linked in a code comment above `ChangelogEventDetail`) served as the event catalog historically; it has since been deleted. This doc replaces it.

---

# Part 1: Dataset-scoped events (shipped)

**Source of truth — keep this doc in sync with the code, not the other way around:**
- `core-models/src/main/scala/com/pennsieve/models/ChangelogEventName.scala` — the enum of event names + their category.
- `core-models/src/main/scala/com/pennsieve/models/ChangelogEventCategory.scala` — the 6 categories.
- `core-models/src/main/scala/com/pennsieve/models/ChangelogEventDetail.scala` — one case class per event name, carrying that event's payload (the `eventDetail` field on the wire).
- `core/src/main/scala/com/pennsieve/managers/ChangelogManager.scala` — `logEvent` (DB write + SNS publish), `eventCategory` (category remap for the wire format), `formatMessageForSNS` (SNS message shape).

## How an event reaches SNS — and the full producer/consumer topology

**There are two structurally independent messaging graphs that happen to converge on the same application code (`ChangelogManagerImpl`) — not one unified pub/sub system.** This is easy to get backwards, so here's the confirmed topology (every edge below was verified against actual Terraform, not inferred from application code alone):

```
┌─────────────────────────────────────────────────────────────────────────┐
│ GRAPH 1 — the notification/fanout graph (ONE SNS topic, ONE subscriber) │
└─────────────────────────────────────────────────────────────────────────┘

  pennsieve-api controllers                 jobs worker's
  (DataSetsController, etc.)                DatasetChangelogEvent.run
         │                                          │
         │  ChangelogManagerImpl.logEvent(s)        │  ChangelogManagerImpl.logEvents
         │  (direct call, synchronous)               │  (constructed fresh, per message)
         ▼                                          ▼
  ┌──────────────────────────────────────────────────────┐
  │        ChangelogManagerImpl (same class, 2 callers)   │
  │   (1) Slick INSERT → per-org `changelog_events` table │
  │   (2) sns.publish(topic = "integration-events")       │
  └──────────────────────────────────────────────────────┘
                           │
                           ▼
            SNS topic: integration-events-sns-topic
            (owned by integration-service's own terraform)
                           │
                           │  aws_sns_topic_subscription
                           │  (the ONLY subscription this topic has)
                           ▼
            SQS: event-integration-queue
                           │
                           │  lambda event source mapping
                           ▼
            event_integration_consumer_lambda  (integration-service)
            → webhooks today; User Notifications tomorrow


┌─────────────────────────────────────────────────────────────────────────┐
│ GRAPH 2 — the work-dispatch graph (NO SNS involved, direct SQS only)    │
└─────────────────────────────────────────────────────────────────────────┘

  packages-service restore-lambda
  (pennsieve-go-core/pkg/changelog.EmitEvents)
         │
         │  SQS SendMessage (direct — no SNS, no fanout)
         ▼
  SQS: jobs_queue  (aka "background_job_queue")
  (plain queue, NO aws_sqs_queue_policy granting any SNS topic delivery rights)
         │
         │  Akka Streams consumer, long-poll
         ▼
  pennsieve-api's `jobs` worker  (ProcessJob.scala dispatch)
         │
         │  for DatasetChangelogEventJob messages:
         ▼
  constructs ChangelogManagerImpl ──────────► feeds into Graph 1 above
```

**Answers to the questions this topology raises:**

- **Is there one SNS topic?** Yes — exactly one, `integration-events-sns-topic`, owned by integration-service's own Terraform. Both pennsieve-api's controllers *and* the `jobs` worker publish to it (both resolve the same `pennsieve.changelog.sns_topic` config key via the shared `INTEGRATION_EVENTS_SNS_TOPIC` env var in real deployments — their local-dev config defaults differ cosmetically, which doesn't matter outside local/docker-compose).
- **Is the SQS queue (`jobs_queue`) fed from that topic?** **No.** `jobs_queue` is NOT an SNS subscription target — no `aws_sns_topic_subscription` resource references it, and it has no resource policy granting the SNS topic delivery rights. It only ever receives messages via direct `SendMessage` calls from producers (today: packages-service's restore-lambda). These are two structurally separate pipes that happen to converge on the same application code, not one fanout graph.
- **Does the `jobs` worker see every event?** **No.** It only sees messages explicitly, directly sent to `jobs_queue` by an external producer. It has no visibility into pennsieve-api's own synchronously-written events (those go DB+SNS directly, no SQS involved on that side at all).
- **Does integration-service care which path produced an event?** **No** — by construction. Both paths funnel through the identical `ChangelogManagerImpl.logEvent(s)`, which is the only thing that ever publishes to the SNS topic integration-service listens to. From integration-service's perspective there is exactly one inbound stream, regardless of origin.
- **Why does this matter for new producers** (a new service, or the organization-events design in Part 2)? A new cross-service producer should follow Graph 2's shape — send a `BackgroundJob`-wrapped message to the `jobs_queue`, let the existing `jobs` worker's dispatch table route it to the right manager/table — rather than inventing a third mechanism. See Part 2's open questions for how this applies to organization events specifically.

The SNS message body (produced by whichever path calls `ChangelogManagerImpl`):

```json
{
  "datasetId": "<int>",
  "organizationId": "<int>",
  "eventCategory": "<wire category — see remap below>",
  "eventType": "<granular ChangelogEventName, e.g. REQUEST_PUBLICATION>",
  "eventDetail": { /* the ChangelogEventDetail case class, JSON-encoded */ }
}
```

**Category remap** (`ChangelogManager.eventCategory`) — the *wire* category differs from the internal `ChangelogEventCategory` enum for two categories:

| Internal `ChangelogEventCategory` | Wire `eventCategory` |
|---|---|
| `DATASET` | `METADATA` |
| `PACKAGES` | `FILES` |
| `PERMISSIONS` | `PERMISSIONS` |
| `MODELS_AND_RECORDS` | `RECORDS_AND_MODELS` |
| `PUBLISHING` | `PUBLISHING` |
| `CUSTOM` | `CUSTOM` |

Webhook subscriptions (`webhook_event_subscriptions.targetEvents`) key on the **wire** category, not the granular `eventType` — see [integration-service's webhook guide](https://github.com/Pennsieve/integration-service/blob/main/docs/webhooks-feature-guide.md) for that consumer's contract. This doc catalogs the producer side: every event that can exist, independent of who subscribes to what.

---

## Category: METADATA (internal `DATASET`)

Dataset-level metadata and lifecycle changes.

| Event | Detail payload | Notes |
|---|---|---|
| `CREATE_DATASET` | `{}` | No fields — event type alone is the signal. |
| `UPDATE_METADATA` | `{}` | **Deprecated.** Legacy/backwards-compatible only; no longer emitted for new events (superseded by the more specific `UPDATE_*` events below). |
| `UPDATE_NAME` | `{oldName: String, newName: String}` | |
| `UPDATE_DESCRIPTION` | `{oldDescription: Option[String], newDescription: Option[String]}` | |
| `UPDATE_LICENSE` | `{oldLicense: Option[License], newLicense: Option[License]}` | |
| `ADD_TAG` | `{name: String}` | |
| `REMOVE_TAG` | `{name: String}` | |
| `UPDATE_README` | `{oldReadme: Option[String], newReadme: Option[String]}` | |
| `UPDATE_BANNER_IMAGE` | `{oldBanner: Option[String], newBanner: Option[String]}` | |
| `ADD_COLLECTION` | `{id: Int, name: String}` | |
| `REMOVE_COLLECTION` | `{id: Int, name: String}` | |
| `ADD_CONTRIBUTOR` | `{id: Int, firstName: String, middleInitial: Option[String], lastName: String, degree: Option[Degree]}` | |
| `REMOVE_CONTRIBUTOR` | same shape as `ADD_CONTRIBUTOR` | |
| `ADD_EXTERNAL_PUBLICATION` | `{doi: Doi, relationshipType: RelationshipType}` | |
| `REMOVE_EXTERNAL_PUBLICATION` | same shape as `ADD_EXTERNAL_PUBLICATION` | |
| `UPDATE_IGNORE_FILES` | `{totalCount: Int}` | |
| `UPDATE_STATUS` | `{oldStatus: StatusDetail, newStatus: StatusDetail}` where `StatusDetail = {id: Int, name: String, displayName: String}` | Wire category is `METADATA`, **not** `STATUS` — there is no `STATUS` wire category that anything is ever emitted under (see integration-service's webhook guide §8.2 for the historical confusion this caused). |

## Category: PERMISSIONS

| Event | Detail payload | Notes |
|---|---|---|
| `UPDATE_PERMISSION` | `{oldRole: Option[Role], newRole: Option[Role], userId: Option[Int], teamId: Option[Int], organizationId: Option[Int]}` | Exactly one of `userId`/`teamId`/`organizationId` is set, depending on who the permission change targets — see "The three dataset-collaborator grant shapes" below, which Part 2 relies on. |
| `UPDATE_OWNER` | `{oldOwner: Int, newOwner: Int}` | User ids. Always a single, direct, individual grant by construction — ownership cannot be assigned to a team or the whole org. |

### The three dataset-collaborator grant shapes (relied on by Part 2)

`UPDATE_PERMISSION` is fired from exactly 3 endpoint pairs in `DataSetsController.scala`, cleanly discriminated by which of `userId`/`teamId`/`organizationId` is set:

| Endpoint pair | Grant shape | Discriminator |
|---|---|---|
| `PUT\|DELETE /:id/collaborators/users` (`addUserCollaborator`/`deleteUserCollaborator`, lines 1968/2026) | **Direct-to-user** | `userId = Some`, `teamId = None`, `organizationId = None` |
| `PUT\|DELETE /:id/collaborators/teams` (`addTeamCollaborator`/`deleteTeamCollaborator`, lines 2281/2340) | **Team grant** | `teamId = Some` |
| `PUT /:id/collaborators/organizations` (`setOrganizationCollaboratorRole`, lines 2429-2512) | **Workspace-wide share** | `organizationId = Some` |

Within the direct-to-user shape, `oldRole: Option[Role]` further distinguishes a *new* grant (`oldRole = None`) from a *role change* on access the user already had (`oldRole = Some(...)`) — different notification-worthy moments, used in Part 2's `ADDED_TO_DATASET` event.

**A fourth, related endpoint:** `PUT /:nodeId/collaborators/external` (`addExternalCollaborator`, `DataSetsController.scala:2116-2225`) is a distinct code path for sharing a dataset with someone who may not yet have — or even need — a Pennsieve account. It discriminates on `userExists: Boolean` (via `UserManager.emailExists`):
- **Existing Pennsieve account** (`userExists = true`): looked up directly, sent an `inviteExternalExistingUserToDataset` email.
- **Brand-new account** (`userExists = false`): a full account is silently created (`createExternalUser` → Cognito invite + `UserManager.createExternalUser`), no separate "welcome" email (the Cognito invite serves that purpose).

In both cases, the endpoint then calls `OrganizationManager.addGuestUser` **twice** — once for the inviting organization, and once for a separate, fixed `welcome_to_pennsieve` organization — before adding the dataset-level collaborator role. This dual-org Guest-provisioning is existing onboarding machinery (see [[dataset-proposal-onboarding-flow]]) that Auth Refactor has flagged for review; Part 2 treats it as an open question rather than assuming it should surface as its own notification event.

## Category: FILES (internal `PACKAGES`)

Package/file tree operations. All four share a `PackageDetail = {id: Int, nodeId: Option[String], name: Option[String]}` sub-shape for parent references.

| Event | Detail payload | Notes |
|---|---|---|
| `CREATE_PACKAGE` | `{id: Int, nodeId: Option[String], name: Option[String], parent: Option[PackageDetail]}` | |
| `RENAME_PACKAGE` | `{id: Int, nodeId: Option[String], oldName: String, newName: String, parent: Option[PackageDetail]}` | |
| `MOVE_PACKAGE` | `{id: Int, nodeId: Option[String], name: Option[String], oldParent: Option[PackageDetail], newParent: Option[PackageDetail]}` | |
| `DELETE_PACKAGE` | `{id: Int, nodeId: Option[String], name: Option[String], parent: Option[PackageDetail]}` | |
| `RESTORE_PACKAGE` | `{id: Int, nodeId: Option[String], name: Option[String], originalName: Option[String], parent: Option[PackageDetail]}` | Produced by **packages-service** (Go, external repo) via Graph 2 above — the only event in this table whose producer isn't pennsieve-api itself. |

## Category: RECORDS_AND_MODELS (internal `MODELS_AND_RECORDS`)

| Event | Detail payload | Notes |
|---|---|---|
| `CREATE_MODEL` | `{id: UUID, name: String}` | |
| `UPDATE_MODEL` | `{id: UUID, name: String}` | |
| `DELETE_MODEL` | `{id: UUID, name: String}` | |
| `CREATE_MODEL_PROPERTY` | `{modelId: UUID, modelName: String, propertyName: String}` | |
| `UPDATE_MODEL_PROPERTY` | same shape as `CREATE_MODEL_PROPERTY` | |
| `DELETE_MODEL_PROPERTY` | same shape as `CREATE_MODEL_PROPERTY` | |
| `CREATE_RECORD` | `{id: UUID, name: Option[String], modelId: Option[UUID]}` | |
| `UPDATE_RECORD` | `{id: UUID, name: Option[String], modelId: Option[UUID], properties: Option[List[PropertyDiff]]}` where `PropertyDiff = {name: String, dataType: Option[Json], oldValue: Json, newValue: Json}` | |
| `DELETE_RECORD` | same shape as `CREATE_RECORD` | |

## Category: CUSTOM

| Event | Detail payload | Notes |
|---|---|---|
| `CUSTOM_EVENT` | `{event_type: String, message: String}` | Triggered via `POST /datasets/{id}/event`. Consumer-defined `event_type`/`message` — not a fixed schema. |

---

## Category: PUBLISHING

The publishing workflow's changelog events all derive from a single Postgres table, `dataset_publication_log` (model: `DatasetPublicationStatus`), which records `(publicationStatus, publicationType)` transitions. `ChangelogEventDetail.fromPublicationStatus` maps each transition to (at most) one event — see that function for the authoritative mapping; this table summarizes it.

**`PublicationStatus`** (7 values): `Draft, Requested, Cancelled, Rejected, Accepted, Failed, Completed`. `Completed`/`Failed` are `systemStatuses` — set by the automated publish/release job (discover-service), not a human action.

**`PublicationType`** (5 values): `Publication, Embargo, Removal, Revision, Release`.

Every `PublicationType` except `Release` follows the same 6-event shape: **Request → Accept → Reject|Cancel → Complete|Fail** (all human-initiated except Complete/Fail). All `COMPLETE_*`/`FAIL_*` events (added 2026-09, PR #401) carry a `PublicationArtifact`:

```scala
case class PublicationArtifact(
  publishedDatasetId: Option[Int] = None,  // Discover's public dataset id
  publishedVersion: Option[Int] = None,    // the specific version number just completed
  doi: Option[String] = None
)
```
populated from discover-service's `PUT /datasets/:id/publication/complete` callback on success; all `None` on failure (not knowable at that point). **Note:** `publishedVersion` is *not* the same as the pre-existing `publishedVersionCount` field on the underlying request — the latter is a running count of successful versions for the dataset, the former is the version number of the publish job that just completed. See the `PublishCompleteRequest` TODO comments in both `pennsieve-api` and `discover-service` for the cross-repo contract this rides on.

### Publication (`REQUEST_PUBLICATION` → `PUT /:id/publication` etc.)

| Event | `(PublicationStatus, PublicationType)` | Detail payload | Trigger |
|---|---|---|---|
| `REQUEST_PUBLICATION` | `(Requested, Publication)` | `{publicationStatusId: Int}` | Dataset owner requests publication (`POST /:id/publication/request`). |
| `ACCEPT_PUBLICATION` | `(Accepted, Publication)` | `{publicationStatusId: Int}` | Publisher/curator accepts (`POST /:id/publication/accept`) — kicks off discover-service's publish job. |
| `REJECT_PUBLICATION` | `(Rejected, Publication)` | `{publicationStatusId: Int}` | Publisher rejects. |
| `CANCEL_PUBLICATION` | `(Cancelled, Publication)` | `{publicationStatusId: Int}` | Owner cancels the request. |
| `COMPLETE_PUBLICATION` | `(Completed, Publication)` | `{publicationStatusId: Int} + PublicationArtifact` | discover-service's publish job succeeds; signaled via `PUT /:id/publication/complete`. |
| `FAIL_PUBLICATION` | `(Failed, Publication)` | `{publicationStatusId: Int} + PublicationArtifact` (all `None`) | discover-service's publish job fails. |

### Embargo (`REQUEST_EMBARGO` → files copied to the private embargo S3 bucket)

| Event | `(PublicationStatus, PublicationType)` | Detail payload | Trigger |
|---|---|---|---|
| `REQUEST_EMBARGO` | `(Requested, Embargo)` | `{publicationStatusId: Int}` | Owner requests embargo publication (with a release date). |
| `ACCEPT_EMBARGO` | `(Accepted, Embargo)` | `{publicationStatusId: Int}` | Publisher accepts — kicks off discover-service's publish job targeting the **embargo** bucket. If the requested release date has already passed by this point, discover-service silently publishes straight to the public bucket instead (`PublishHandler.validEmbargoRequest`/`shouldEmbargo`) — the changelog sequence is unaffected either way, since it's still recorded as `(..., Embargo)` throughout. |
| `REJECT_EMBARGO` | `(Rejected, Embargo)` | `{publicationStatusId: Int}` | |
| `CANCEL_EMBARGO` | `(Cancelled, Embargo)` | `{publicationStatusId: Int}` | |
| `COMPLETE_EMBARGO` | `(Completed, Embargo)` | `{publicationStatusId: Int} + PublicationArtifact` | discover-service finishes copying files to the embargo bucket. |
| `FAIL_EMBARGO` | `(Failed, Embargo)` | `{publicationStatusId: Int} + PublicationArtifact` (all `None`) | |

### Release (embargo expiry — files copied from the embargo bucket to the public bucket)

This is the automatic follow-on to a successful embargo, once the release date arrives. `Requested`/`Accepted` here are **machine-originated bookkeeping**, not human actions — both rows are written synchronously, back-to-back, by pennsieve-api's `POST /:id/publication/release` handler the moment discover-service's automatic embargo-expiry scanner (`releaseEmbargoedDatasets`) fires. There is no human "reject"/"cancel" action in this flow, so unlike the other 4 types, `Release` has no `REJECT_RELEASE`/`CANCEL_RELEASE`.

| Event | `(PublicationStatus, PublicationType)` | Detail payload | Trigger |
|---|---|---|---|
| `REQUEST_RELEASE` | `(Requested, Release)` | `{publicationStatusId: Int}` | Written automatically when discover-service's release-date scanner triggers a release. |
| `ACCEPT_RELEASE` | `(Accepted, Release)` | `{publicationStatusId: Int}` | Written immediately after, same request — auto-accepted (no human approval step). |
| `RELEASE_EMBARGO` | `(Completed, Release)` | `{publicationStatusId: Int}` | discover-service's Release Step Function finishes copying files from the embargo bucket to the public bucket, updating each file's S3 bucket/key/version. Signaled via the same `PUT /:id/publication/complete` callback. **This is the "release succeeded" event** — note the name doesn't follow the `COMPLETE_*` pattern (pre-existing name, kept as-is to avoid a breaking rename). |
| `FAIL_RELEASE` | `(Failed, Release)` | `{publicationStatusId: Int} + PublicationArtifact` (all `None`) | discover-service's Release Step Function fails; same callback, `success=false`. Added 2026-09 (PR #402) — this is a real, reachable failure path that previously produced no changelog event at all. |

### Removal / "unpublish" (`REQUEST_REMOVAL` → dataset withdrawn from Discover)

| Event | `(PublicationStatus, PublicationType)` | Detail payload | Trigger |
|---|---|---|---|
| `REQUEST_REMOVAL` | `(Requested, Removal)` | `{publicationStatusId: Int}` | Owner requests the published dataset be withdrawn/unpublished. |
| `ACCEPT_REMOVAL` | `(Accepted, Removal)` | `{publicationStatusId: Int}` | Publisher accepts — kicks off discover-service's unpublish job (synchronous, unlike Publication/Embargo). |
| `REJECT_REMOVAL` | `(Rejected, Removal)` | `{publicationStatusId: Int}` | |
| `CANCEL_REMOVAL` | `(Cancelled, Removal)` | `{publicationStatusId: Int}` | |
| `COMPLETE_REMOVAL` | `(Completed, Removal)` | `{publicationStatusId: Int} + PublicationArtifact` | |
| `FAIL_REMOVAL` | `(Failed, Removal)` | `{publicationStatusId: Int} + PublicationArtifact` (all `None`) | |

### Revision (`REQUEST_REVISION` → non-data-changing metadata update to an already-published version)

| Event | `(PublicationStatus, PublicationType)` | Detail payload | Trigger |
|---|---|---|---|
| `REQUEST_REVISION` | `(Requested, Revision)` | `{publicationStatusId: Int}` | |
| `ACCEPT_REVISION` | `(Accepted, Revision)` | `{publicationStatusId: Int}` | Kicks off discover-service's revise job (synchronous). |
| `REJECT_REVISION` | `(Rejected, Revision)` | `{publicationStatusId: Int}` | |
| `CANCEL_REVISION` | `(Cancelled, Revision)` | `{publicationStatusId: Int}` | |
| `COMPLETE_REVISION` | `(Completed, Revision)` | `{publicationStatusId: Int} + PublicationArtifact` | |
| `FAIL_REVISION` | `(Failed, Revision)` | `{publicationStatusId: Int} + PublicationArtifact` (all `None`) | |

### Other

| Event | Detail payload | Notes |
|---|---|---|
| `UPDATE_CHANGELOG` | `{oldChangelog: Option[String], newChangelog: Option[String]}` | Not part of the publication-status state machine — a direct edit to the dataset's changelog/release-notes text field. Wire category is `PUBLISHING` despite not going through `dataset_publication_log`. |

---

## Known gaps / things to watch (Part 1)

- **`(Requested, Release)` / `(Accepted, Release)` fire in immediate succession** with no meaningful time gap between them (both written in the same request). A notification subscriber watching `REQUEST_RELEASE` and `ACCEPT_RELEASE` separately will see both essentially simultaneously — they're not useful as distinct "waiting for approval" signals the way `REQUEST_PUBLICATION`/`ACCEPT_PUBLICATION` are.
- **`fromPublicationStatus` silently drops any `(PublicationStatus, PublicationType)` combination it doesn't have a case for** — it falls through to `case _ => None`, meaning a row can be written to `dataset_publication_log` with *no* corresponding changelog event and no error. This is how the original `FAIL_RELEASE` gap (fixed in #402) went unnoticed for years. Before assuming an event "must" exist for some transition, check this function directly rather than inferring from symmetry.
- **`PublishCompleteRequest`** (the wire body discover-service sends to pennsieve-api's `PUT /:id/publication/complete`) is hand-duplicated in both repos with no shared schema/codegen — see the `TODO` comments on that case class in each repo. A future field added to one side without the other will silently fail to round-trip.
- Prior to #401 (merged 2026-09), `DatasetPublicationStatusManager.create` wrote to `dataset_publication_log` via a bare DB call that never reached `ChangelogManager.logEvent` — meaning **no publication-status event of any kind (not just Complete/Fail) ever reached SNS**, for the entire history of the codebase until this fix. If you're looking at historical SQS/webhook data from before this date, publication events will be absent even though the underlying `dataset_publication_log` rows exist.
- **`jobs/` (the Scala consumer in Graph 2) is a real, live component, not legacy scaffolding** — a 2026-06 plan to retire it via a Go strangler-fig migration exists but has had zero implementation progress as of 2026-10; `jobs/` continues to receive live maintenance commits in the meantime. Don't assume it's safe to ignore or that its retirement is imminent.

---

# Part 2: Organization-scoped events (design — not yet implemented)

**Status:** Draft, open for team review — `pennsieve-api#405`.
**Context:** Prerequisite for the User Notifications feature (integration-service + Pennsieve App).

## Problem

Organization- and team-level administrative actions — adding or removing a user from a workspace, changing a user's organization-level role, creating/renaming/deleting a team, adding or removing a user from a team — produce **no durable, queryable signal of any kind** today. Several of these (removing a user from an org, removing a user from a team) don't even send an email; they're silent database mutations.

This was surfaced while scoping the event vocabulary the User Notifications feature needs beyond dataset-publishing events (Part 1, already shipped).

### What's confirmed silent today (traced against the actual code, not inferred)

| Action | Manager method | Email today? | Any other signal? |
|---|---|---|---|
| Add user to org (direct) | `OrganizationManager.addUser` | ❌ | ❌ |
| Add user to org (invite) | `SecureOrganizationManager.inviteMember` | ✅ `addedToOrganization` | ❌ |
| **Remove user from org** | `SecureOrganizationManager.removeUser` | ❌ | ❌ |
| Org-level permission change | `SecureOrganizationManager.updateUserPermission` | ❌ | ❌ |
| Add user to team | `TeamManager.addUser` (email sent in controller) | ✅ `addedToTeam` | ❌ |
| **Remove user from team** | `TeamManager.removeUser` | ❌ | ❌ |
| Create team | `TeamManager.create` | ❌ | ❌ |
| Rename team | `TeamManager.update` | ❌ | ❌ |
| Delete team | `TeamManager.delete` | ❌ | ❌ |

("Any other signal" excludes the `auditLogger`/`Auditor` calls present on some of these endpoints — that mechanism POSTs to the API Gateway's `/logs/enhance/{traceId}` for request-trace correlation. It is not a queryable audit table, is applied inconsistently — notably absent on both silent-removal actions above — and fires even on plain `GET` reads. It is not infrastructure to build on for this feature.)

### Why the existing `ChangelogEvent` system can't just grow an `ORGANIZATION` category

- `changelog_events.dataset_id` (`core/src/main/scala/com/pennsieve/db/ChangelogEventTable.scala:36`) is a **required, non-nullable** column.
- `ChangelogEventMapper.logEvent(dataset: Dataset, detail, user, timestamp)` takes a hard-required `Dataset` parameter — there is no way to call it for an action with no associated dataset.
- Every consumer of dataset-changelog data (the changelog timeline UI, the webhook-delivery system) is written assuming "every event belongs to exactly one dataset." Bolting an org-level concept onto this table (e.g. a nullable `dataset_id`) would ripple into every one of those call sites and force a `WHERE dataset_id IS NULL` special case everywhere a dataset is currently assumed.

**Decision: build a parallel capability, not a nullable-column retrofit.** Organization events are a different shape (org-wide, not dataset-scoped) queried by a different audience (org admins, not dataset collaborators) — trying to force-fit them into the dataset table is exactly the kind of "beat up an existing capability to make something new fit" pattern to avoid.

## Design

### Schema — one table, not two (revised from the original proposal)

The dataset-changelog system uses two tables per organization schema: `changelog_event_types` (id, name, created_at — a pure lookup table) and `changelog_events` (the actual event log, FK'd to the lookup table).

The lookup table exists **purely as a normalization optimization**: it lets `changelog_events` store a small integer FK instead of repeating the event-name string on every row, which matters because dataset events are high-volume (every dataset mutation produces one). It carries no independent business meaning — no description, no schema, no per-type config, just `(id, name, created_at)`.

**Organization events will be comparatively low-volume** (admin actions are rare relative to dataset mutations), so that normalization trade-off doesn't clearly pay for itself here. The simpler, single-table design:

```
organization_events (
  id,
  organization_id,
  actor_user_id,       -- who performed the action (nullable — see below)
  subject_user_id,     -- who the action was performed on (nullable — not every event has one)
  event_type,          -- OrganizationEventName stored directly as an enum column
  detail,              -- JSON, the OrganizationEventDetail payload
  created_at
)
```

This mirrors a pattern already used elsewhere in this codebase — `dataset_publication_log` stores `PublicationStatus`/`PublicationType` as direct enum columns with no lookup table — so it's not a new pattern for this repo, just not the one the dataset-changelog system happens to use.

Both the original two-table proposal and this one were discussed; **decision: reconsidered, but kept the two-table shape for structural parallelism with the dataset-changelog system** (an explicit trade-off — the normalization benefit is weak at this volume, but code/schema symmetry with the existing, well-understood `changelog_events`/`changelog_event_types` pattern was judged more valuable than the marginal storage savings either way). *(Revisit this if organization_events volume ever approaches dataset_events volume — unlikely, but worth noting the assumption.)*

Lives in the **per-organization Postgres schema** (`organization.schemaId`), per [[pennsieve-db-migrations]]'s `core/` vs `organization/` migration split — same as `changelog_events`, not a shared cross-org table.

This intentionally does **not** touch `changelog_events`/`changelog_event_types`/`ChangelogEventName`/`ChangelogEventCategory` at all. Existing dataset-changelog code, tests, and consumers are unaffected.

**`actor_user_id` is nullable, not just `subject_user_id`.** Some organization events have no single human actor at all — see "Workspace-wide broadcast events" below (e.g. `DATASET_PUBLISHED`, which is triggered by discover-service completing a publish job, not a person clicking a button in that moment). Don't assume every row has both an actor and a subject; some have neither, some have one, some have both.

### Model layer — mirror `ChangelogEventName`/`ChangelogEventDetail`, don't extend them

One enum, `OrganizationEventName` (own file, `core-models/src/main/scala/com/pennsieve/models/OrganizationEventName.scala`) — **two wire categories, not a flat one, and not a `TEAMS` category either.** `TEAMS` was considered and rejected as an unnecessary split: team events (add/remove/create/update/delete) are still organization-administration events, and "which system manages this" isn't a notification-relevant axis the way dataset-vs-publishing categories are — they stay under `eventCategory: "ORGANIZATION"` alongside org-membership/permission events.

A second category, **`eventCategory: "PUBLISHING"`**, is warranted for a genuinely different reason: Part 4's workspace-wide broadcast events (`DATASET_PUBLISHED` and siblings) are conceptually a *different* notification topic from "something happened to the workspace's membership/structure" — a workspace member who wants to know "tell me when any dataset is published" has no interest in team-rename events, and vice versa. Keeping them in separate wire categories lets integration-service's `TOPICS` table (and any future category-level subscription UI) group them sensibly, mirroring how Part 1's dataset-scoped events already separate `PUBLISHING` from `METADATA`/`PERMISSIONS`/etc. This is the only category split in the organization-events design — everything in Part 1 of this section (org/team administration) is `ORGANIZATION`; everything in Part 4 (publish-lifecycle broadcasts) is `PUBLISHING`.

New sealed-trait `OrganizationEventDetail` (own file, mirrors `ChangelogEventDetail`'s case-class-per-event-name pattern). Full vocabulary and payload shapes are in the tables below.

### Manager layer — new `OrganizationEventManager`, wired like `ChangelogManager`

```scala
trait OrganizationEventManager {
  def db: Database
  def organization: Organization
  def actor: User
  def snsTopic: SnsTopic   // SAME topic as ChangelogManager — see Part 1's topology
  def sns: SNSClient

  def logEvent(actorUserId: Option[Int], subjectUserId: Option[Int], detail: OrganizationEventDetail, timestamp: ZonedDateTime = now): EitherT[Future, CoreError, ...] =
    for {
      dbResult <- logEventDB(...)     // INSERT INTO organization_events
      _ <- logEventSNS(...)           // sns.publish(topic, formatMessageForSNS(...))
    } yield dbResult
}
```

Call sites: `OrganizationManager.addUser`/`removeUser`, `SecureOrganizationManager.updateUserPermission`/`inviteMember`, `TeamManager.addUser`/`removeUser`/`create`/`update`/`delete`, plus the dataset-adjacent call sites in Part 2's "workspace-scoped" sections below — each gains a call to `organizationEventManager.logEvent(...)` alongside its existing DB mutation (and, where applicable, existing email send). **Do not conflate email-sending with event-logging** — they're independent; an event should fire regardless of whether an email template happens to exist for that action today (closing the gap on the two currently-fully-silent removal actions is part of the point).

Container wiring mirrors the fix already made for `DatasetPublicationStatusManagerImpl` in #401 (that manager was found to be missing its `ChangelogManager` dependency entirely, which is *why* dataset-publication events never reached SNS for years) — inject the real manager with its `sns`/`snsTopic`, not just a bare DB mapper, from day one.

**Cross-service producers (e.g. a future github-service event) should follow Graph 2 from Part 1's topology** — a `BackgroundJob`-wrapped SQS message to `jobs_queue`, consumed by the existing `jobs` worker's dispatch table — rather than requiring every producer to hold SNS credentials and know the wire format directly. Not yet designed in detail; flagged as a follow-up once this design's own single-producer (pennsieve-api-internal) scope is settled.

### Wire format — same SNS topic, new `eventCategory`

Reuses the existing `integration-events-sns-topic` → SQS → integration-service pipeline (see Part 1's topology — this is Graph 1, unchanged; organization events are a new producer onto the same topic, not a new graph).

**This topic is not pennsieve-api-exclusive.** It's the shared event bus for *any* Pennsieve service that wants to emit something integration-service/User-Notifications can act on — not just dataset- and organization-scoped events. A near-term example: github-service emitting an event when a release is published to Discover or the App Store, which has no dataset or organization context at all (it's scoped to a GitHub repo/release). Keep the wire format's required fields to the true lowest common denominator (`eventCategory`, `eventType`, `eventDetail`) rather than assuming every message has an `organizationId`/`datasetId` — those become category-specific, optional fields, not universal ones.

```json
{
  "organizationId": "<int>",
  "actorUserId": "<int, nullable>",
  "subjectUserId": "<int, nullable>",
  "eventCategory": "ORGANIZATION",
  "eventType": "<granular OrganizationEventName, e.g. REMOVE_ORGANIZATION_USER>",
  "eventDetail": { /* OrganizationEventDetail case class, JSON-encoded */ }
}
```

(`eventCategory` is `"ORGANIZATION"` for Part 1/2/3's events, `"PUBLISHING"` for Part 4's — see "Model layer," above.)

Note there is **no `datasetId` field** at the envelope level — integration-service's event parsing (and anything else that assumes every SNS message on this topic has a `datasetId`) needs to tolerate its absence. Worth checking `integration-service`'s `event_parser`/`EventMessage` handling for this assumption before implementation (relevant: the `datasetId`-as-quoted-string bug fixed in integration-service#138 was in this exact parsing path).

## Event vocabulary, part 1: organization/team administration

Full membership + team set, matching what's silent today. **Revised per review: `ADD_ORGANIZATION_USER` splits into two events**, since "an existing Pennsieve user joins this workspace" and "someone with no Pennsieve account is being onboarded onto the platform via this workspace" are different notification-worthy moments with different downstream implications (the latter involves Cognito account creation and is squarely in Auth Refactor's scope for review/improvement — see [[dataset-proposal-onboarding-flow]]).

| Event | Fires from | Subject |
|---|---|---|
| `ADD_ORGANIZATION_USER` | `OrganizationManager.addUser` / `inviteMember`, when the invited/added email belongs to an **existing** Pennsieve account | the added user |
| `ADD_EXTERNAL_ORGANIZATION_USER` | same call sites, when the email belongs to **no existing** Pennsieve account (a new account is created as part of the flow) | the newly-created user |
| `REMOVE_ORGANIZATION_USER` | `SecureOrganizationManager.removeUser` | the removed user |
| `UPDATE_ORGANIZATION_PERMISSION` | `SecureOrganizationManager.updateUserPermission` | the affected user |
| `ADD_TEAM_USER` | `TeamManager.addUser` | the added user |
| `REMOVE_TEAM_USER` | `TeamManager.removeUser` | the removed user |
| `CREATE_TEAM` | `TeamManager.create` | — (no single-user subject, `actor_user_id` only) |
| `UPDATE_TEAM` | `TeamManager.update` | — |
| `DELETE_TEAM` | `TeamManager.delete` | — |

**Note:** `OrganizationManager.addUser`/`inviteMember` don't themselves carry an existing-vs-new-account discriminator the way `addExternalCollaborator` (Part 1) does — `emailExists`-style branching would need to be added at this call site, or reused from wherever it already exists in the org-invite flow, to actually distinguish `ADD_ORGANIZATION_USER` from `ADD_EXTERNAL_ORGANIZATION_USER`. Flagged as an implementation detail to verify, not assumed already present.

### A related, dataset-level pair (flagged for symmetry, not yet designed)

Part 1 documents `PUT /:nodeId/collaborators/external` (`addExternalCollaborator`) as the dataset-level analog — sharing a dataset with someone who may or may not have a Pennsieve account, which **also** auto-grants org-level Guest access (via `addGuestUser`, called twice — once for the inviting org, once for a fixed `welcome_to_pennsieve` org). This endpoint's existing/new-account discriminator (`userExists`) is the real, already-present version of the discriminator `ADD_ORGANIZATION_USER`/`ADD_EXTERNAL_ORGANIZATION_USER` above would need. Whether this endpoint should *also* emit `ADD_ORGANIZATION_USER`/`ADD_EXTERNAL_ORGANIZATION_USER` (since it does grant org membership, just as a Guest) — and whether the `welcome_to_pennsieve` dual-org grant should surface as its own event or be treated as internal plumbing not worth notifying on — is an open question (see Open Questions, below). Not designed in this pass.

## Event vocabulary, part 2: workspace-scoped notice of a personal dataset grant

A workspace member wants to know when *they personally* are granted (or lose) direct access to a dataset — "I'm already in this workspace; tell me when a new dataset shows up that I now have access to." Impossible to express as a subscription against the *existing* dataset-level `UPDATE_PERMISSION`/`UPDATE_OWNER` events (Part 1), because subscribing to a dataset-scoped topic requires knowing the `datasetId` in advance — and a user can't know (or have access to) the id of a dataset they haven't been granted access to yet. Has to be organization-scoped (subscribable the moment you join the workspace), even though it's *describing* a dataset-level grant.

Using the three grant shapes documented in Part 1 ("The three dataset-collaborator grant shapes"): only the **direct-to-user** shape qualifies as "directly to me, not via team, not workspace-wide."

**Decision: fire these as parallel events at the same `DataSetsController` call sites**, right alongside the existing `changelogManager.logEvent(dataset, UpdatePermission(...))`/`UpdateOwner(...)` calls — not derived later by a downstream consumer watching the dataset-event stream. Same request, same actor, no added lag or secondary failure mode.

| Event | Fires from (same call site as) | Discriminator | Subject |
|---|---|---|---|
| `ADDED_TO_DATASET` | `addUserCollaborator` | `userId` set, `teamId`/`organizationId` unset, **and** `oldRole.isEmpty` (new grant, not a role change) | the added user |
| `REMOVED_FROM_DATASET` | `deleteUserCollaborator` | `userId` set, `teamId`/`organizationId` unset (removal is always `newRole = None`, no further discriminator needed) | the removed user |
| `DATASET_OWNERSHIP_TRANSFERRED` | `switchOwner` | none needed — always direct/individual (see Part 1: ownership can't be assigned via team or workspace-wide share) | the new owner |

Proposed detail payloads (mirroring the `publishedDatasetId`/`publishedVersion`/`doi` pattern from the dataset-publication events — enough for a notification to be rendered without a follow-up API call):

```scala
case class AddedToDataset(datasetId: Int, datasetNodeId: String, datasetName: String, role: Role) extends OrganizationEventDetail {
  val eventType = ADDED_TO_DATASET
}
case class RemovedFromDataset(datasetId: Int, datasetNodeId: String, datasetName: String) extends OrganizationEventDetail {
  val eventType = REMOVED_FROM_DATASET
}
case class DatasetOwnershipTransferred(datasetId: Int, datasetNodeId: String, datasetName: String, previousOwnerUserId: Int) extends OrganizationEventDetail {
  val eventType = DATASET_OWNERSHIP_TRANSFERRED
}
```

Note: the event still lives in `organization_events` (keyed by `organization_id`/`subject_user_id`); it's just that its *detail* payload happens to reference a dataset. This doesn't reopen the "should `organization_events` have a `dataset_id` column" question — the table stays dataset-agnostic, same as `AddTeamUser`'s detail referencing a team without the table needing a `team_id` column.

## Event vocabulary, part 3: workspace-wide visibility for privileged roles (Publishers/"Curators")

A privileged workspace member wants visibility into things happening across the whole workspace, independent of whether they personally have dataset-level access to what happened. Concrete example: a Curator (in practice, a member of the org's **Publishers team**) wants to know the moment *any* dataset is created, so they can reach out to the new owner immediately rather than discovering the dataset — and the rework it may already contain — much later at publication time.

**This is a real, checkable gate already — not a new authorization concept.** `SystemTeamType.Publishers` (`core-models/.../SystemTeamType.scala`) is a first-class, platform-managed team: every organization gets one auto-provisioned at creation (`OrganizationManager.create`, `core/src/main/scala/com/pennsieve/managers/OrganizationManager.scala:554-563`, granted `DBPermission.Administer` at the org level), and the codebase already treats it specially elsewhere — e.g. it's explicitly protected from being added as an ordinary dataset collaborator (`DataSetsController.scala:2308`: `"team ${teamDto.id} is managed by Pennsieve and cannot be added by users"`). `SecureOrganizationManager.isPublisher(organization): Boolean` (`OrganizationManager.scala:470-479`) already answers "is the current caller a Publisher," and `getPublishingTeamMembers(organization)` (line 483) returns the full member list — both used today to gate the dataset-publication accept/reject workflow. So "is user X entitled to workspace-wide visibility" is already a solved, existing check; this design just needs to reuse it as a **subscription-creation-time gate**, not invent new authz machinery.

| Event | Fires from | Personal variant exists? | Workspace-wide variant gate |
|---|---|---|---|
| `DATASET_CREATED` | `POST /datasets` (`DataSetsController.scala:933`, alongside the existing dataset-scoped `CreateDataset` event at line 976) | No — the creator already knows they created it; this event exists purely for third-party visibility. | Publishers-team membership required to subscribe at all (no ungated variant). |
| `DATASET_OWNERSHIP_TRANSFERRED` | same call site as part 2 (`switchOwner`) — **same event, not a new one** | Yes — a self-scoped subscription (`context = {subjectUserId: <me>}`) needs no special gate; everyone can always know about grants/transfers concerning themselves. | A wildcard/workspace-wide subscription (no `subjectUserId` filter, i.e. "notify me about every transfer in the org") requires Publishers-team membership. |

**Where the gate actually lives:** enforced once, at **subscription-creation time** in integration-service, not as a per-event runtime check. When a `POST /notification/subscription` request's `context` doesn't scope to the caller's own `subjectUserId`, integration-service needs to check Publishers-team membership for that user in that org before allowing the subscription to be created — a cross-service call integration-service doesn't currently need to make for any other topic, since team/permission data lives in pennsieve-api's schema. Flagged as new integration surface, not just a new `TOPICS` row.

**This generalizes beyond Publishers specifically.** The pattern — "a system-managed team's (or permission level's) membership gates who can create a workspace-wide-visibility subscription to topic X" — will likely recur (e.g. `DBPermission.Administer`-gated visibility into `REMOVE_ORGANIZATION_USER`). Not designing that generalized mechanism now — Publishers is the one concrete, already-real case — but worth naming so the gate-check isn't built Publishers-specific in a way that has to be redone for the next case.

## Event vocabulary, part 4: workspace-wide publishing broadcasts (`eventCategory: PUBLISHING`)

A fourth, distinct shape: an event that **any** workspace member can subscribe to for workspace-wide visibility into the publishing lifecycle — not gated to Publishers (unlike Part 3), and with no personal/self-scoped variant at all, because there's no natural "subject" person for the event to be *about*.

Concrete motivating example (a real, active requirement — HEAL wants Pennsieve to email workspace members when a dataset in their workspace is published; tracked as ClickUp [868mcp26q](https://app.clickup.com/t/868mcp26q)): **"tell me whenever a dataset is published in workspace X."** This is explicitly broader than the dataset-scoped `COMPLETE_PUBLICATION` event (Part 1), which requires knowing a specific `datasetId` to subscribe to — the same "can't subscribe to something you don't have access to yet" problem that motivated Part 2.

**Settled rule: one broadcast event per successful terminal publish-workflow transition, mirroring Part 1's `COMPLETE_*`/`RELEASE_EMBARGO` dataset-scoped successes — no `FAILED_*` siblings at this level.** A failed publish/revision/removal is a conversation between the dataset owner and whoever accepted the request (already covered by the dataset-scoped `FAIL_*` events in Part 1, which both of those people can already subscribe to via Part 2's personal-grant mechanism if they're the dataset owner, or just by having dataset access); it is not inherently workspace-wide-notification-worthy the way a *successful* publish is (new content is now visible/available — something every Curator or interested workspace member plausibly cares about).

**Naming: short, action-announcing names (`DATASET_PUBLISHED`, not `DATASET_PUBLISHED_IN_WORKSPACE`).** A longer, scope-qualified name was considered and rejected — these events already live under the org-level `organization_events`/`PUBLISHING`-category system, so the scope is structural, not something the name needs to restate. Shorter names read better in a notification-preferences UI and in logs.

**Each lifecycle outcome is its own distinct event — not one event reused across outcomes.** In particular, a revision does **not** reuse the same event name as a first publication (unlike the dataset-scoped `COMPLETE_PUBLICATION`, which does fire for both). This is a deliberate choice: event *generation* should be intentional and granular, matching the dataset-scoped `ChangelogEventName` vocabulary 1:1 (see below); any coarser "subscribe to everything publishing-related" UX is a **subscription-time** concern (category-level subscriptions, proposed below), not something to collapse at the event-definition layer.

| Event | Mirrors (Part 1) | Fires from (same call site as) |
|---|---|---|
| `DATASET_PUBLISHED` | `COMPLETE_PUBLICATION` | `PUT /:id/publication/complete` callback, `(Completed, Publication)` |
| `DATASET_EMBARGOED` | `COMPLETE_EMBARGO` | same callback, `(Completed, Embargo)` |
| `DATASET_EMBARGO_RELEASED` | `RELEASE_EMBARGO` | same callback, `(Completed, Release)` |
| `DATASET_UNPUBLISHED` | `COMPLETE_REMOVAL` | the synchronous removal-accept call site (`DataSetsController.scala:3491-3499`) |
| `DATASET_REVISED` | `COMPLETE_REVISION` | the synchronous revision-accept call site (`DataSetsController.scala:3429-3437`) only — `PublicationType.Revision` is its own distinct human-initiated flow (`REQUEST_REVISION`/`ACCEPT_REVISION`, Part 1), not something the ordinary `Publication` flow also triggers based on a version check. A second (or later) version published via the regular `Publication` flow is just another `DATASET_PUBLISHED` — there is no code-level distinction in that path between "first publish" and "republishing a new version." (Earlier drafts of this doc incorrectly claimed `DATASET_REVISED` also fires from the `COMPLETE_PUBLICATION` call site when `publishedVersion > 1` — checked directly against `DataSetsController.scala` and found no such branch; retracted.) |

All five share the same shape: **no actor** (`actor_user_id = None` — these are triggered by discover-service's completion signal or an internal synchronous job, not a person's in-the-moment action at the broadcast's own call site), **no subject** (workspace-wide, no `subjectUserId`), **no gate** (any workspace member may subscribe — publication is already a public act; the dataset is visible to everyone on Discover the moment this fires). This confirms `actor_user_id` must be nullable (see Schema, above).

Detail payload (one case class per event, matching the one-case-class-per-event-name convention used everywhere else in this doc; shown once since all five share the same fields):

```scala
case class DatasetPublished(      // DatasetEmbargoed / DatasetEmbargoReleased / DatasetUnpublished: same shape
  datasetId: Int,
  datasetNodeId: String,
  datasetName: String,
  publishedDatasetId: Option[Int],  // Discover's own id for the published dataset — see below
  publishedVersion: Int,
  revision: Option[Int],            // DatasetRevised only — see below
  doi: String,
  ownerUserId: Int,
  ownerName: String   // denormalized — avoids a lookup at notification render time
) extends OrganizationEventDetail {
  val eventType = DATASET_PUBLISHED
}
```

`ownerUserId`/`ownerName` are the fields that make this payload actually useful for the motivating Curator use case ("reach out to the new owner immediately") — without them, a Curator sees "a dataset was published" with no one to contact. Denormalizing the name (rather than requiring a follow-up lookup) mirrors why `datasetName` is already inline rather than just `datasetId`. No `organizationId` on the detail — it's already on the `organization_events` row and the SNS envelope; no `actorUserId` duplication either, since there isn't one (see above).

**`publishedDatasetId` (Discover's own integer id, distinct from pennsieve-api's `datasetId`) — include it, pennsieve-api already has it.** This is not new plumbing: it's the same `publishedDatasetId: Option[Int]` field already on `PublishCompleteRequest` (`DataSetsController.scala:163-172`, the wire body discover-service's async job-completion callback sends to `PUT /:id/publication/complete`) and already threaded into `ChangelogEventDetail.PublicationArtifact` (Part 1) for every other event in this family. For `DatasetPublished`/`DatasetEmbargoed`/`DatasetEmbargoReleased`/`DatasetUnpublished` — all of which fire from that same callback — this is just reading a field pennsieve-api already receives.

**`revision` (the per-version revision counter) — `DatasetRevised`-only, and NOT currently available; would need a discover-service change.** Traced the actual contract (2026-10-14): unlike the other four broadcasts, **revisions never go through the async `PUT /:id/publication/complete` callback at all.** discover-service's `/revise` endpoint (`PublishHandler.scala:394-671`) is fully synchronous — it runs the whole revise transaction inline and returns its result as the direct HTTP response (`DatasetPublishStatus`, a generated-client model from `discover-service`'s own `openapi/discover-service-internal.yml:1229-1263`) to pennsieve-api's `sendReviseRequest` call (`DataSetPublishingHelper.scala:1067`, return type `EitherT[Future, CoreError, DatasetPublishStatus]`). `DatasetPublishStatus` today carries `publishedVersionCount`/`status`/`workflowId` etc. but **no revision-number field** — and discover-service *does* already compute and persist exactly this number: `case class Revision(datasetId, version, revision, ...)` (`discover-service/server/.../models/Revision.scala:9-19`, table `revisions`, auto-incremented per `(datasetId, version)` by `RevisionsMapper.create`, `RevisionsTable.scala:39-54`) — it's just never included in any response/payload sent back to pennsieve-api.

Per that trace, adding it is a small, low-risk change on discover-service's side (the `revision` value is already in scope, synchronously, at the exact point `DatasetPublishStatus` is assembled for the HTTP response — `PublishHandler.scala:649-651`, right after `RevisionsMapper.create` at line 484) — not a new query, job, or async plumbing, just threading an already-computed int through an existing response object, its OpenAPI schema, and pennsieve-api's generated client (a codegen regen, not hand-written model surgery, since `DatasetPublishStatus` lives in `com.pennsieve.discover.client.publish`).

**Decision: include `revision` in the `DatasetPublished` payload as `Option[Int]`, populated only for `DatasetRevised`, `None` for the other four — but treat the discover-service response-schema change as a prerequisite, not yet done.** This is exactly the "if Discover gives it to us, include it" case: worth capturing once available, not worth blocking this design on. Tracked as a new open question below rather than assumed already in place.

### Category field — proposed now, implementation deferred to a later iteration

The "subscribe to all publishing activity in this workspace" UX (e.g. a single front-end toggle, rather than 5 separate per-event subscriptions) needs a **category** concept on organization events, mirroring dataset-scoped events' `ChangelogEventCategory` — all 5 events above share one category, `PUBLISHING`, the same way their dataset-scoped counterparts already do.

This is a bigger change than it looks: integration-service's `TOPICS` table currently maps 1:1 to individual event *names*, with no category concept at all. Supporting category-level subscriptions means integration-service needs to (a) learn about categories as a first-class concept, (b) store a category on each `TOPICS` row, and (c) support a subscription that matches "any topic in category X" rather than only "this exact topic." That's real new scope on the integration-service side, not just a pennsieve-api wire-format addition.

**Decision: propose the field and initial values now, defer the actual category-subscription mechanism to a later release.** V1 (the HEAL requirement, `DATASET_PUBLISHED` specifically) ships against the current per-event-name `TOPICS` model — no category-level subscriptions yet. The category value itself should still be included on the wire from day one (cheap to add now, expensive to retrofit onto historical events later), even though nothing consumes it until the category-subscription mechanism is built.

| Event | Proposed `category` |
|---|---|
| `DATASET_PUBLISHED` | `PUBLISHING` |
| `DATASET_EMBARGOED` | `PUBLISHING` |
| `DATASET_EMBARGO_RELEASED` | `PUBLISHING` |
| `DATASET_UNPUBLISHED` | `PUBLISHING` |
| `DATASET_REVISED` | `PUBLISHING` |
| every event in Parts 1-3 (org/team administration, personal dataset grants, Publishers-visibility) | `ORGANIZATION` |

### Relationship to integration-service#148

[integration-service#148](https://github.com/Pennsieve/integration-service/pull/148) ("Notify workspace members when a dataset in their workspace is published") already implements a version of this — built from this design's Part 3/4 discussion and a prior review comment on this same ClickUp ticket, before the naming/granularity decisions above were finalized. It currently ships:
- The event named `DATASET_PUBLISHED_IN_WORKSPACE` (not `DATASET_PUBLISHED`).
- One event reused for both first publication and revisions (not split into `DATASET_PUBLISHED`/`DATASET_REVISED`).
- A payload shape matching `DatasetPublished` above (confirms the field rationale — `ownerUserId`/`ownerName` included, no `organizationId` duplication).
- A subscription gate of "any `pennsieve.organization_user`" (matches this doc — no Publishers-style gate for this event).

**This PR needs a round of requested changes to match this spec** — rename to `DATASET_PUBLISHED`, split the revision case out as its own `DATASET_REVISED` event, and (optionally, can be deferred to the category work above) add a `category` field. Since #148 is the integration-service side of a contract whose producer (pennsieve-api) hasn't shipped yet, this is a cheap rename/split to make now rather than carry a naming mismatch into production.

Note on the "revisions also notify" claim in #148's PR description: per the correction above, `DATASET_REVISED` and `DATASET_PUBLISHED` fire from genuinely different pennsieve-api call sites (`Revision` is its own `PublicationType`/workflow, not a version-count branch inside `Publication`'s). If #148's current behavior assumes one producer event covers both, that assumption should be corrected as part of the requested-changes round, not carried forward — confirm with Yi what pennsieve-api's actual (not-yet-shipped) producer will send before assuming the single-event behavior is correct.

**Explicitly out of scope for this design** (may be revisited later, but not blocking the vocabulary above): organization settings changes (name, color theme), subscription/billing status changes, feature-flag changes, custom-ToS-version updates. These are lower-value notification targets and silent today with no urgency to fix.

## Open questions for review

1. **Exact field shapes** for each `OrganizationEventDetail` case class — the ones sketched above are a first pass; should be validated against what's actually in scope at each `OrganizationManager`/`TeamManager` call site (e.g. does `removeUser` have the user's prior permission level in scope to include in the detail, or just the user id?).
2. **Email-template gap** — should adding the new `logEvent` calls also be the moment we add the missing email templates (`removedFromOrganization`, `removedFromTeam`, a permission-changed notice)? Or keep this design strictly to the event-emission layer and let email-template work happen separately/later, once User Notifications can itself be the delivery mechanism instead of a new bespoke email template per action?
3. **Retroactive migration** — none. Like dataset ChangelogEvents, this only covers actions going forward from whenever it ships; no backfill of historical org/team membership changes is proposed or feasible (the data to reconstruct "who removed whom and when" doesn't exist today).
4. **Team-grant and org-wide-share notifications** — this design deliberately only covers the *direct-to-user* dataset-grant shape (`ADDED_TO_DATASET`/`REMOVED_FROM_DATASET`). Being granted access to a dataset via team membership or a workspace-wide share is arguably just as notification-worthy, but is a materially different question ("notify every member of Team X whenever Team X's access changes" is a fan-out, not a single-subscriber event) — deferred as a separate, later design rather than folded in here.
5. **Who owns the Publishers-membership gate check** — does integration-service call a new/extended pennsieve-api endpoint at subscription-creation time (proposed above), or does pennsieve-api instead push a denormalized "is this user a Publisher in this org" flag/event integration-service can cache locally? Leaning toward the former (simpler, correctness > the minor latency cost, subscription-creation isn't a hot path) but not decided.
6. **Generalizing the "workspace-wide visibility" gate beyond Publishers** — should the gate-check mechanism be designed generically now (e.g. each `TOPICS` row declares what gates a wildcard/workspace-wide subscription to it: a system-team name, a minimum `DBPermission`, or nothing), or should Publishers/`DATASET_CREATED`/`DATASET_OWNERSHIP_TRANSFERRED` ship as a one-off special case and get generalized when the second real case shows up?
7. **Does `addExternalCollaborator` (the dataset-level external-share flow) also need to emit `ADD_ORGANIZATION_USER`/`ADD_EXTERNAL_ORGANIZATION_USER`**, given it silently grants org-level Guest access as a side effect? And should the `welcome_to_pennsieve` dual-org grant be visible as its own signal, or treated as internal plumbing? Tied to the ongoing Auth Refactor review of this onboarding path (see [[dataset-proposal-onboarding-flow]]) — may be better resolved there than in this design.
8. **Cross-service producer mechanism** — should a future non-pennsieve-api producer of organization events (or platform-level events, a still-undesigned third scope beyond organization) follow Part 1's Graph 2 shape (SQS → existing `jobs` worker dispatch table), or does introducing more job types into the Scala `jobs` worker argue for prioritizing the long-dormant Go migration plan (see [[changelog-events-go-migration]]) before adding more scope to it?
9. **Category-level subscriptions (Part 4)** — the `category` field is proposed to ship on the wire now, but the actual category-level subscription mechanism (integration-service's `TOPICS` table learning about categories, matching "any topic in category X" rather than one exact topic) is deferred to a later iteration, after the v1 HEAL requirement (`DATASET_PUBLISHED`) ships. Revisit once a second category-spanning UX need (not just publishing) makes the generalized mechanism worth building.
10. **`DatasetRevised`'s `revision` field is blocked on a discover-service change** — confirmed discover-service already computes and persists this number (`Revision.revision`, auto-incremented per `(datasetId, version)`) but never sends it anywhere today; revisions don't even go through the async publication-complete callback the other four Part 4 events use (`/revise` is synchronous, response-only). Needs: (a) discover-service adds `revision: Option[Int]` to `DatasetPublishStatus`'s OpenAPI schema and response body, (b) pennsieve-api regenerates its `com.pennsieve.discover.client.publish` client, (c) pennsieve-api threads the value from `sendReviseRequest`'s already-discarded `DatasetPublishStatus` return value into the `DatasetRevised` event. None of this is done — ship `DatasetRevised` with `revision = None` until discover-service's side lands, or sequence the discover-service change first if the field is wanted from day one.
