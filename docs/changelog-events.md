# Changelog Events — Reference

pennsieve-api emits a **ChangelogEvent** for most mutating actions on a dataset: metadata edits, file/package operations, model/record changes, permission changes, the publishing workflow, and custom integration-defined events. Each event is persisted to the per-organization `changelog_events` table and published to the `{env}-integration-events-sns-topic` SNS topic, which fans out to an SQS queue consumed by [integration-service](https://github.com/Pennsieve/integration-service) (webhooks today; the User Notifications feature, in progress as of 2026-09, is the next consumer).

This doc is the source-of-truth catalog: every `ChangelogEventName`, its category, its `ChangelogEventDetail` payload shape, and — for the publishing workflow, where the mapping is least obvious — exactly what triggers it. A prior Google Sheet (linked in a code comment above `ChangelogEventDetail`) served this purpose historically; it has since been deleted. This markdown doc replaces it.

**Source of truth — keep this doc in sync with the code, not the other way around:**
- `core-models/src/main/scala/com/pennsieve/models/ChangelogEventName.scala` — the enum of event names + their category.
- `core-models/src/main/scala/com/pennsieve/models/ChangelogEventCategory.scala` — the 6 categories.
- `core-models/src/main/scala/com/pennsieve/models/ChangelogEventDetail.scala` — one case class per event name, carrying that event's payload (the `eventDetail` field on the wire).
- `core/src/main/scala/com/pennsieve/managers/ChangelogManager.scala` — `logEvent` (DB write + SNS publish), `eventCategory` (category remap for the wire format), `formatMessageForSNS` (SNS message shape).

## How an event reaches SNS

```scala
// ChangelogManager.scala
def logEvent(dataset, detail, timestamp = now) =
  for {
    _ <- logEventDB(dataset, detail, timestamp)   // INSERT INTO changelog_events
    _ <- logEventSNS(dataset, detail, timestamp)   // sns.publish(topic, formatMessageForSNS(...))
  } yield ...
```

The SNS message body:

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
| `UPDATE_PERMISSION` | `{oldRole: Option[Role], newRole: Option[Role], userId: Option[Int], teamId: Option[Int], organizationId: Option[Int]}` | Exactly one of `userId`/`teamId`/`organizationId` is set, depending on who the permission change targets. |
| `UPDATE_OWNER` | `{oldOwner: Int, newOwner: Int}` | User ids. |

## Category: FILES (internal `PACKAGES`)

Package/file tree operations. All four share a `PackageDetail = {id: Int, nodeId: Option[String], name: Option[String]}` sub-shape for parent references.

| Event | Detail payload | Notes |
|---|---|---|
| `CREATE_PACKAGE` | `{id: Int, nodeId: Option[String], name: Option[String], parent: Option[PackageDetail]}` | |
| `RENAME_PACKAGE` | `{id: Int, nodeId: Option[String], oldName: String, newName: String, parent: Option[PackageDetail]}` | |
| `MOVE_PACKAGE` | `{id: Int, nodeId: Option[String], name: Option[String], oldParent: Option[PackageDetail], newParent: Option[PackageDetail]}` | |
| `DELETE_PACKAGE` | `{id: Int, nodeId: Option[String], name: Option[String], parent: Option[PackageDetail]}` | |
| `RESTORE_PACKAGE` | `{id: Int, nodeId: Option[String], name: Option[String], originalName: Option[String], parent: Option[PackageDetail]}` | |

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
populated from discover-service's `PUT /datasets/:id/publication/complete` callback on success; all `None` on failure (not knowable at that point). **Note:** `publishedVersion` is *not* the same as the pre-existing `publishedVersionCount` field on the underlying request — the latter is a running count of successful versions for the dataset, the former is the version number of the publish job that just finished. See the `PublishCompleteRequest` TODO comments in both `pennsieve-api` and `discover-service` for the cross-repo contract this rides on.

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

## Known gaps / things to watch

- **`(Requested, Release)` / `(Accepted, Release)` fire in immediate succession** with no meaningful time gap between them (both written in the same request). A notification subscriber watching `REQUEST_RELEASE` and `ACCEPT_RELEASE` separately will see both essentially simultaneously — they're not useful as distinct "waiting for approval" signals the way `REQUEST_PUBLICATION`/`ACCEPT_PUBLICATION` are.
- **`fromPublicationStatus` silently drops any `(PublicationStatus, PublicationType)` combination it doesn't have a case for** — it falls through to `case _ => None`, meaning a row can be written to `dataset_publication_log` with *no* corresponding changelog event and no error. This is how the original `FAIL_RELEASE` gap (fixed in #402) went unnoticed for years. Before assuming an event "must" exist for some transition, check this function directly rather than inferring from symmetry.
- **`PublishCompleteRequest`** (the wire body discover-service sends to pennsieve-api's `PUT /:id/publication/complete`) is hand-duplicated in both repos with no shared schema/codegen — see the `TODO` comments on that case class in each repo. A future field added to one side without the other will silently fail to round-trip.
- Prior to #401 (merged 2026-09), `DatasetPublicationStatusManager.create` wrote to `dataset_publication_log` via a bare DB call that never reached `ChangelogManager.logEvent` — meaning **no publication-status event of any kind (not just Complete/Fail) ever reached SNS**, for the entire history of the codebase until this fix. If you're looking at historical SQS/webhook data from before this date, publication events will be absent even though the underlying `dataset_publication_log` rows exist.
