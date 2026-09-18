# Reconciling a stuck dataset removal

When a dataset removal (unpublish) needs a restore first -- some files are live only
in the publish bucket -- `accept(removal)` starts a Step Functions execution and
leaves the dataset in an intermediate `Accepted`/`Removal` state. It stays locked
until something calls `PUT /datasets/:id/publication/removal/complete`, which normally
happens automatically once the restore finishes. If that completion signal is ever
lost, the removal can be stuck indefinitely.

This is a manual runbook, not an automated tool: the common failure mode (the restore
itself fails) already produces a completion signal, so removals don't silently strand
on their own. This only covers the rarer case where the signal itself never arrives.

## Steps

1. **Identify the stuck row.** In the dataset's organization schema:
   ```sql
   SELECT id, publication_status, publication_type, removal_metadata, created_at
   FROM "<org_schema>".dataset_publication_log
   WHERE dataset_id = <datasetId>
   ORDER BY created_at DESC
   LIMIT 1;
   ```
   Confirm `publication_type = 'Removal'` and `publication_status = 'Accepted'`, and
   pull the execution ARN out of `removal_metadata->>'executionArn'`.

2. **Check its status** in the AWS Step Functions console (or
   `aws stepfunctions describe-execution --execution-arn <arn>`). If it's still
   `RUNNING`, stop -- it isn't stuck, just slow (e.g. a large dataset still copying).

3. **If it's terminal** (`SUCCEEDED`, `FAILED`, `TIMED_OUT`, `ABORTED`), call the
   completion endpoint manually, authenticated as a superadmin:
   ```
   PUT /datasets/<datasetId>/publication/removal/complete
   Authorization: Bearer <superadmin session token>
   Content-Type: application/json

   { "success": <true if SUCCEEDED, false otherwise> }
   ```
   This is safe even if the execution's true state is uncertain: the endpoint
   independently re-verifies that no files are still live-only in the publish bucket
   before it will ever delete the publish bucket, and it's a no-op if the removal is
   already `Completed`.

4. **Confirm the result** -- the dataset's latest publication log row should now read
   `Completed` (teardown finished) or `Failed` (ready for the publisher to retry by
   re-accepting the removal through the normal flow).