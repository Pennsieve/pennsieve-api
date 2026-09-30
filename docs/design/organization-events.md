# Design: Organization-Level Events

**Status:** Draft — for team review before implementation.
**Author:** Michael Uftring, with Claude Code.
**Context:** Prerequisite for the User Notifications feature (integration-service + Pennsieve App).

## Problem

Today, `ChangelogEvent` (see [`docs/changelog-events.md`](../changelog-events.md)) only covers actions on a *dataset*. Organization- and team-level administrative actions — adding or removing a user from a workspace, changing a user's organization-level role, creating/renaming/deleting a team, adding or removing a user from a team — produce **no durable, queryable signal of any kind**. Several of these (removing a user from an org, removing a user from a team) don't even send an email; they're silent database mutations.

This was surfaced while scoping the event vocabulary the User Notifications feature needs. That feature's first release only needs dataset-publishing events (already covered — see `docs/changelog-events.md`), but organization/team membership changes are an obvious, near-term second wave (e.g. "notify me when I'm added to a workspace," "notify a workspace owner when someone's role changes"), and the current architecture has nowhere to put them.

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

**Decision: build a parallel capability, not a nullable-column retrofit.** Organization events are a different shape (org-wide, not dataset-scoped) queried by a different audience (org admins, not dataset collaborators) — trying to force-fit them into the dataset table is exactly the kind of "beat up an existing capability to make something new fit" pattern we want to avoid.

## Design

### Schema — mirror the existing pattern, new tables

The dataset-changelog system is two tables per organization schema:
- `changelog_event_types` (id, name, created_at) — a lookup table, one row per distinct `ChangelogEventName` ever used, populated lazily via `getOrCreate`.
- `changelog_events` (id, dataset_id, user_id, event_type_id, detail, created_at) — the actual event log.

Both live in the **per-organization Postgres schema** (`organization.schemaId`), per [[pennsieve-db-migrations]]'s `core/` vs `organization/` migration split — this repo already has one copy of these tables per org, not a single shared table.

Proposed parallel pair, same schema, same pattern:
- `organization_event_types` (id, name, created_at) — lookup table for `OrganizationEventName`.
- `organization_events` (id, organization_id, actor_user_id, subject_user_id, event_type_id, detail, created_at) — note **two** user references, not one: `actor_user_id` (who performed the action — an admin) and `subject_user_id` (who the action was performed on — nullable, since not every org event has a single affected user, e.g. team-created). Dataset events only need one `user_id` because the dataset itself is the subject; here the subject is a person, and the actor and subject are frequently different people, both of whom may want to be notified for different reasons (the admin who did it vs. the user it happened to).

This intentionally does **not** touch `changelog_events`/`changelog_event_types`/`ChangelogEventName`/`ChangelogEventCategory` at all. Existing dataset-changelog code, tests, and consumers are unaffected.

### Model layer — mirror `ChangelogEventName`/`ChangelogEventDetail`, don't extend them

New sealed-trait enum `OrganizationEventName` (own file, `core-models/src/main/scala/com/pennsieve/models/OrganizationEventName.scala`), analogous to `ChangelogEventName` but without needing a category system of its own initially (open question below) — or, if we want organization events to flow through the *same* SNS topic/wire format as dataset events (decided: yes, see below), it may be simpler to have `OrganizationEventName` also carry a `category`-like tag so the wire `eventCategory` field stays meaningful, even if there's only one category (`ORGANIZATION`) today.

New sealed-trait `OrganizationEventDetail` (own file, mirrors `ChangelogEventDetail`'s case-class-per-event-name + `fromXyz`-style construction-helper pattern), e.g.:

```scala
sealed trait OrganizationEventDetail { val eventType: OrganizationEventName }

case class AddOrganizationUser(userId: Int, permission: DBPermission) extends OrganizationEventDetail {
  val eventType = ADD_ORGANIZATION_USER
}
case class RemoveOrganizationUser(userId: Int) extends OrganizationEventDetail {
  val eventType = REMOVE_ORGANIZATION_USER
}
case class UpdateOrganizationPermission(userId: Int, oldPermission: DBPermission, newPermission: DBPermission) extends OrganizationEventDetail {
  val eventType = UPDATE_ORGANIZATION_PERMISSION
}
case class AddTeamUser(teamId: Int, teamName: String, userId: Int) extends OrganizationEventDetail {
  val eventType = ADD_TEAM_USER
}
case class RemoveTeamUser(teamId: Int, teamName: String, userId: Int) extends OrganizationEventDetail {
  val eventType = REMOVE_TEAM_USER
}
case class CreateTeam(teamId: Int, name: String) extends OrganizationEventDetail {
  val eventType = CREATE_TEAM
}
case class UpdateTeam(teamId: Int, oldName: String, newName: String) extends OrganizationEventDetail {
  val eventType = UPDATE_TEAM
}
case class DeleteTeam(teamId: Int, name: String) extends OrganizationEventDetail {
  val eventType = DELETE_TEAM
}
```

(Field shapes above are a starting proposal, not final — should be refined against what `OrganizationManager`/`TeamManager` actually have in scope at each call site, same way the dataset `ChangelogEventDetail` case classes were shaped around what each controller action already had on hand.)

### Manager layer — new `OrganizationEventManager`, wired like `ChangelogManager`

```scala
trait OrganizationEventManager {
  def db: Database
  def organization: Organization
  def actor: User
  def snsTopic: SnsTopic   // SAME topic as ChangelogManager — see wire format below
  def sns: SNSClient

  def logEvent(subjectUserId: Option[Int], detail: OrganizationEventDetail, timestamp: ZonedDateTime = now): EitherT[Future, CoreError, ...] =
    for {
      dbResult <- logEventDB(...)     // INSERT INTO organization_events
      _ <- logEventSNS(...)           // sns.publish(topic, formatMessageForSNS(...))
    } yield dbResult
}
```

Call sites: `OrganizationManager.addUser`/`removeUser`, `SecureOrganizationManager.updateUserPermission`/`inviteMember`, `TeamManager.addUser`/`removeUser`/`create`/`update`/`delete` — each gains a call to `organizationEventManager.logEvent(...)` alongside its existing DB mutation (and, where applicable, existing email send). **Do not conflate email-sending with event-logging** — they're independent; an event should fire regardless of whether an email template happens to exist for that action today (closing the gap on the two currently-fully-silent removal actions is part of the point).

Container wiring mirrors the fix already made for `DatasetPublicationStatusManagerImpl` in #401 (that manager was found to be missing its `ChangelogManager` dependency entirely, which is *why* dataset-publication events never reached SNS for years) — inject the real manager with its `sns`/`snsTopic`, not just a bare DB mapper, from day one.

### Wire format — same SNS topic, new `eventCategory`

Reuses the existing `{env}-integration-events-sns-topic` → SQS → integration-service pipeline (decided in discussion — simplest for integration-service's `TOPICS` table, avoids a second topic/queue/IAM wiring).

**This topic is not pennsieve-api-exclusive.** It's intended as the shared event bus for *any* Pennsieve service that wants to emit something integration-service/User-Notifications can act on — not just dataset- and organization-scoped events. A near-term example: github-service emitting an event when a release is published to Discover or the App Store, which has no dataset or organization context at all (it's scoped to a GitHub repo/release). Keep the wire format's required fields to the true lowest common denominator (`eventCategory`, `eventType`, `eventDetail`) rather than assuming every message has an `organizationId`/`datasetId` — those become category-specific, optional fields, not universal ones. Message shape mirrors `ChangelogManager.formatMessageForSNS`:

```json
{
  "organizationId": "<int>",
  "actorUserId": "<int>",
  "subjectUserId": "<int, nullable>",
  "eventCategory": "ORGANIZATION",
  "eventType": "<granular OrganizationEventName, e.g. REMOVE_ORGANIZATION_USER>",
  "eventDetail": { /* OrganizationEventDetail case class, JSON-encoded */ }
}
```

Note there is **no `datasetId` field** — integration-service's event parsing (and anything else that assumes every SNS message on this topic has a `datasetId`) needs to tolerate its absence. Worth checking `integration-service`'s `event_parser`/`EventMessage` handling for this assumption before implementation (relevant: the `datasetId`-as-quoted-string bug fixed in integration-service#138 was in this exact parsing path).

### Event vocabulary (this design's scope)

Full membership + team set, matching what's silent today:

| Event | Fires from | Subject |
|---|---|---|
| `ADD_ORGANIZATION_USER` | `OrganizationManager.addUser` / `inviteMember` | the added user |
| `REMOVE_ORGANIZATION_USER` | `SecureOrganizationManager.removeUser` | the removed user |
| `UPDATE_ORGANIZATION_PERMISSION` | `SecureOrganizationManager.updateUserPermission` | the affected user |
| `ADD_TEAM_USER` | `TeamManager.addUser` | the added user |
| `REMOVE_TEAM_USER` | `TeamManager.removeUser` | the removed user |
| `CREATE_TEAM` | `TeamManager.create` | — (no single-user subject) |
| `UPDATE_TEAM` | `TeamManager.update` | — |
| `DELETE_TEAM` | `TeamManager.delete` | — |

### Event vocabulary, part 2: workspace-scoped notice of a personal dataset grant

A distinct but related need, surfaced in review: **a workspace member wants to know when *they personally* are granted (or lose) direct access to a dataset** — "I'm already in this workspace; tell me when a new dataset shows up that I now have access to." This is impossible to express as a subscription against the *existing* dataset-level `UPDATE_PERMISSION`/`UPDATE_OWNER` `ChangelogEvent`s, because subscribing to a dataset-scoped topic requires knowing the `datasetId` in advance — and a user can't know (or have access to) the id of a dataset they haven't been granted access to yet. It has to be an organization-scoped event (subscribable the moment you join the workspace, with no dataset id needed up front), even though it's *describing* a dataset-level grant.

Traced the exact call sites and confirmed the discriminator is already present in the existing dataset-level event payloads — no new information needs to be captured, just re-emitted at a different scope:

- `UpdatePermission(oldRole, newRole, userId, teamId, organizationId)` — `api/src/main/scala/com/pennsieve/api/DataSetsController.scala`. Fired from exactly 3 endpoint pairs, cleanly discriminated by which of `userId`/`teamId`/`organizationId` is set:
  - `PUT|DELETE /:id/collaborators/users` (`addUserCollaborator`/`deleteUserCollaborator`, `DataSetsController.scala:1968`/`2026`) — **direct-to-user grant**: `userId = Some`, `teamId = None`, `organizationId = None`.
  - `PUT|DELETE /:id/collaborators/teams` (`DataSetsController.scala:2281`/`2340`) — **team grant**: `teamId = Some`.
  - `PUT|DELETE /:id/collaborators/organizations` (`setOrganizationCollaboratorRole`, `DataSetsController.scala:2461`/`2512`) — **workspace-wide share**: `organizationId = Some`.
  
  Only the first shape is "directly to me, not via team, not workspace-wide" — exactly what you asked for. Within that shape, `oldRole: Option[Role]` further distinguishes *new* access (`oldRole = None`, this is the "added" case) from a *role change* on access you already had (`oldRole = Some(...)`) — these are different notification-worthy moments and get different event names below.
- `UpdateOwner(oldOwner, newOwner)` — fired from the ownership-transfer endpoint (`switchOwner`, `DataSetsController.scala:2621`). Always a single, direct, individual grant by construction — you cannot become a dataset's owner via a team or a workspace-wide share — so no discriminator is needed; every occurrence qualifies.

**Decision: fire these as parallel events at the same `DataSetsController` call sites**, right alongside the existing `changelogManager.logEvent(dataset, UpdatePermission(...))`/`UpdateOwner(...)` calls — not derived later by a downstream consumer watching the dataset-event stream. Same request, same actor, no added lag or secondary failure mode.

| Event | Fires from (same call site as) | Discriminator | Subject |
|---|---|---|---|
| `ADDED_TO_DATASET` | `addUserCollaborator` | `userId` set, `teamId`/`organizationId` unset, **and** `oldRole.isEmpty` (new grant, not a role change) | the added user |
| `REMOVED_FROM_DATASET` | `deleteUserCollaborator` | `userId` set, `teamId`/`organizationId` unset (removal is always `newRole = None`, no further discriminator needed) | the removed user |
| `DATASET_OWNERSHIP_TRANSFERRED` | `switchOwner` | none needed — always direct/individual | the new owner |

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

Note these three are the *only* organization-level events proposed so far whose `OrganizationEventDetail` needs a dataset reference at all — that's fine; the event still lives in `organization_events` (keyed by `organization_id`/`subject_user_id`, no `dataset_id` column needed on the table itself), it's just that its *detail* payload happens to describe a dataset. This doesn't reopen the "should organization_events have a dataset_id column" question — the table stays dataset-agnostic; only this one event's JSON detail blob references a dataset, the same way `AddTeamUser`'s detail references a team without the table needing a `team_id` column.

**Explicitly out of scope for this design** (may be revisited later, but not blocking the vocabulary above): organization settings changes (name, color theme), subscription/billing status changes, feature-flag changes, custom-ToS-version updates. These are lower-value notification targets and silent today with no urgency to fix.

## Open questions for review

1. **`OrganizationEventName` category system** — do we want a `category` concept at all given there's only one category (`ORGANIZATION`) in scope, or is a flat enum (no category) simpler until/unless a second category (e.g. `BILLING`, if subscription events get added later) actually materializes?
2. **Exact field shapes** for each `OrganizationEventDetail` case class — the ones sketched above are a first pass; should be validated against what's actually in scope at each `OrganizationManager`/`TeamManager` call site (e.g. does `removeUser` have the user's prior permission level in scope to include in the detail, or just the user id?).
3. **Email-template gap** — should adding the new `logEvent` calls also be the moment we add the missing email templates (`removedFromOrganization`, `removedFromTeam`, a permission-changed notice)? Or keep this design strictly to the event-emission layer and let email-template work happen separately/later, once User Notifications can itself be the delivery mechanism instead of a new bespoke email template per action?
4. **Retroactive migration** — none. Like dataset ChangelogEvents, this only covers actions going forward from whenever it ships; no backfill of historical org/team membership changes is proposed or feasible (the data to reconstruct "who removed whom and when" doesn't exist today).
5. **Team-grant and org-wide-share notifications** — this design deliberately only covers the *direct-to-user* dataset-grant shape (`ADDED_TO_DATASET`/`REMOVED_FROM_DATASET`). Being granted access to a dataset via team membership or a workspace-wide share is arguably just as notification-worthy, but is a materially different question ("notify every member of Team X whenever Team X's access changes" is a fan-out, not a single-subscriber event) — deferred as a separate, later design rather than folded in here.
