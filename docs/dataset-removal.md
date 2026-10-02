# Dataset Removal (Unpublish) — Reference

A dataset removal withdraws a published dataset from Discover. Before Discover is told to unpublish, publish-storage-sync runs a **restore** that copies any files that live only in the publish bucket back to storage. A removal is therefore asynchronous: accepting it starts the restore, and the removal finishes only when the restore's completion signal arrives.

This doc covers pennsieve-api's side: the `dataset_publication_log` rows a removal writes, what each outcome does, and how to retry. The wire contract with publish-storage-sync (execution input, completion message, endpoint body) is documented in that repo's [`docs/restore-integration.md`](https://github.com/Pennsieve/publish-storage-sync/blob/main/docs/restore-integration.md). For a removal whose completion signal never arrives, see [reconciling-a-stuck-removal.md](reconciling-a-stuck-removal.md).

**Source of truth — keep this doc in sync with the code, not the other way around:**
- `api/src/main/scala/com/pennsieve/api/DataSetsController.scala`
  - the `PublicationType.Removal` case of `accept`: writes the `Accepted` row.
  - `startRemovalRestore`: starts the restore.
  - `completeRemovalRestore`: handles the completion signal.
  - `finalizeAcceptedRemoval`: the only caller of `sendUnpublishRequest`.
- `core-models/src/main/scala/com/pennsieve/models/RemovalRestoreMetadata.scala` — the `removal_metadata` column.
- `core/src/main/scala/com/pennsieve/managers/FileManager.scala` — `countPublishedFiles`, the teardown gate.
- `core/src/main/scala/com/pennsieve/aws/stepfunctions/StepFunctions.scala` — `executionArn`, which derives an execution's ARN from its name.

## Why every removal runs a restore

Discover calls `PUT /:id/publication/complete` *before* it enqueues the publish-storage-sync message for that publish. So a dataset can be marked `Completed` and accepted for removal while its sync is still queued or running, with no files deduped yet. Tearing down the publish bucket at that point would delete objects the sync is about to point `files` rows at.

The restore takes publish-storage-sync's restore guard, which serializes it against any sync for the dataset:
- **A queued sync** becomes version-stale and runs as a no-op.
- **A running sync** makes the restore fail, which marks the removal `Failed`. The publisher retries once the sync is done.

A restore with nothing to copy just takes the guard and marks it `RESTORED`.

## Lifecycle

| Step | Trigger | Row written to `dataset_publication_log` | Changelog event |
|---|---|---|---|
| Request | Owner: `POST /:id/publication/request?publicationType=removal` | `(Requested, Removal)` | `REQUEST_REMOVAL` |
| Accept | Publisher: `POST /:id/publication/accept?publicationType=removal` | `(Accepted, Removal)`, then its `removal_metadata` is filled in and the restore is started | `ACCEPT_REMOVAL` |
| Complete | Restore-completion Lambda (or a superadmin): `PUT /datasets/:id/publication/removal/complete` | `(Completed, Removal)` or `(Failed, Removal)` — see outcomes below | `COMPLETE_REMOVAL` / `FAIL_REMOVAL` |

`Accepted` is a locked status, so no other publication workflow can start while a restore is in flight.

**`removal_metadata`** (JSONB, set only on `(Accepted, Removal)` rows):

```json
{ "executionArn": "arn:aws:states:<region>:<account>:execution:<machine>:restore-<orgId>-<datasetId>-<rowId>", "publishedVersion": 3 }
```

The execution name includes the org id, because dataset ids and log row ids are only unique within an organization's schema. It also includes the row id, so each accept, including a retry, starts a distinct execution. The ARN is derived and written *before* `StartExecution`. That way a completion signal can never arrive ahead of the ARN it must match, and a failed write never leaves a restore running behind a `Failed` row.

## Outcomes

| Situation | Result | Teardown | How it's retried |
|---|---|---|---|
| Discover status has no `publishedDatasetId`, the state machine ARN is empty or malformed, the metadata write fails, or `StartExecution` fails | Accept returns an error; `(Failed, Removal)` | No | Publisher re-accepts |
| Completion with `success = false` | `(Failed, Removal)` | No | Publisher re-accepts, which starts a new restore |
| Completion with `success = true`, but `countPublishedFiles > 0` | `(Failed, Removal)` | No | Publisher re-accepts |
| Completion with `success = true` and `countPublishedFiles == 0` | Unpublish on Discover, remove the publisher team, unregister ORCID (failures logged and ignored); `(Completed, Removal)` | Yes | — |
| The Discover unpublish, team removal or owner lookup fails during that teardown | Endpoint returns an error; the row stays `(Accepted, Removal)` | Partial | SQS redelivers the completion message; after the DLQ, use the runbook |
| Completion whose `executionArn` doesn't match the latest `Accepted` removal, or arrives after the removal finished | `200`, no change (a stale or duplicate signal) | No | — |

The `countPublishedFiles` recount is the data-loss backstop. A `success = true` signal on its own never tears anything down.

## Configuration

| Setting | Source | Notes |
|---|---|---|
| `pennsieve.publishing.restore_state_machine_arn` | `RESTORE_STATE_MACHINE_ARN`; SSM `/<env>/<service>/restore-state-machine-arn`, from publish-storage-sync's remote state | Empty by default. Every accept then fails closed with `(Failed, Removal)`. |
| `pennsieve.s3.default_storage_bucket` | `DEFAULT_STORAGE_BUCKET`; SSM `/<env>/<service>/default-storage-bucket` | The restore's destination when the organization has no storage bucket of its own. |
| IAM | `api/terraform/iam.tf` | `states:StartExecution` on the restore state machine. |

The completion endpoint requires a superadmin. publish-storage-sync's Lambda calls it with a `ServiceClaim` JWT, which resolves to one.

---

## Known gaps / things to watch

- **`publishedVersion` is a count, not a version number.** The restore input takes it from Discover's `publishedVersionCount`, which counts versions in status `PublishSucceeded`. Once a dataset has been unpublished and republished, the count is lower than the latest version number. The restore then sets the guard's `guardVersion` too low, and a redelivered sync for the latest version is not treated as stale. The fix is a discover-service change that adds the latest version number to `DatasetPublishStatus`; switch the restore input to it once it ships.
- **Two simultaneous completion calls for the same execution can both finalize.** Both pass the latest-row check before either writes `Completed`, so Discover is asked to unpublish twice and two `Completed` rows are written. This relies on Discover's unpublish being idempotent.
- **An ambiguous `StartExecution` failure can leave a restore running behind a `Failed` row.** This happens when every SDK retry fails but AWS did start the execution. Its completion signal is then ignored, and a re-accept starts a second restore, possibly while the first is still running. That is safe but redundant. Both runs share the guard's `runUuid`, so they copy the same pinned versions to the same storage keys and write identical row updates. A run still going when the teardown deletes the publish bucket fails before changing anything.
- **A retried teardown repeats completed steps.** If teardown fails after Discover's unpublish succeeded, the redelivered signal sends the unpublish again.
- **Every removal waits for a Fargate restore**, including embargoed datasets that have nothing to copy. This is deliberate (see above): it keeps pennsieve-api from depending on which publication types Discover syncs after.