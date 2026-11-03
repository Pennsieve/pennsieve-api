# Email-service cutover: Dev validation checklist

Manual test checklist for the pennsieve-api migration from direct SES to the
centralized email-service ([PR #427](https://github.com/Pennsieve/pennsieve-api/pull/427),
ClickUp [868k3mqan](https://app.clickup.com/t/868k3mqan)). Run it on Dev after #427 is
merged and deployed.

18 send sites, 17 messageIds (`email-address-changed` fires twice: old and new address).
Triggers are from `email-service/docs/email-inventory.md`. The endpoints were traced from
the controller code on this branch (see [API endpoint reference](#api-endpoint-reference)).
They have not been exercised against Dev, so adjust as needed.

`scripts/send-test-emails.sh` in email-service does **not** exercise pennsieve-api. It puts
sample requests straight on the queue. This checklist is the api-side test.

Use the test org (O38), never a live org.

## Per-email checks

For every row:

- [ ] Email arrives with the expected subject
- [ ] No blank fields or leftover `{{.…}}` in the body
- [ ] `email-message-log` row is `SENT` (`ENV=dev scripts/email-log.sh` in email-service)
- [ ] If nothing arrives, check whether the journal row is `LoggedOnly=true` (a send control fired; not a failure)

## Checklist

Endpoint refs (E1…E13) point to the [reference section](#api-endpoint-reference). Paths are
relative to the api host.

| Done | # | messageId | Recipients | Triggering endpoint | Action / condition |
|---|---|---|---|---|---|
| [ ] | 1 | `dataset-publication-in-review` | owner + contributors | E1 `POST /datasets/:id/publication/request` | `publicationType=Publication` only |
| [ ] | 2 | `dataset-submitted-for-review` | publishers | E1 `POST /datasets/:id/publication/request` | Same call as #1 (publisher team) |
| [ ] | 3 | `dataset-publication-accepted` | owner + contributors | E2 `POST /datasets/:id/publication/accept` | `publicationType=Publication` or `Embargo` |
| [ ] | 4 | `dataset-revision-needed` | owner + contributors | E3 `POST /datasets/:id/publication/reject` | `publicationType=Publication` only |
| [ ] | 5 | `dataset-revision` | owner + contributors | E2 `POST /datasets/:id/publication/accept` | `publicationType=Revision` |
| [ ] | 6 | `notification-of-publication-to-contributor` | owner + contributors | E4 `PUT /datasets/:id/publication/complete` | Discover callback, latest status type `Publication` |
| [ ] | 7 | `dataset-embargo-accepted` | owner + contributors | E4 `PUT /datasets/:id/publication/complete` | Discover callback, latest status type `Embargo` |
| [ ] | 8 | `embargo-dataset-release-accepted` | owner + contributors | E2 `POST /datasets/:id/publication/accept` | `publicationType=Release` |
| [ ] | 9 | `embargoed-dataset-released` | owner + contributors | E4 `PUT /datasets/:id/publication/complete` | Discover callback, latest status type `Release` |
| [ ] | 10 | `embargo-access-requested` | dataset managers | E5 `POST /datasets/publication/preview/request` | Superadmin only; dataset must be under embargo |
| [ ] | 11 | `embargo-access-approved` | requesting user | E6 `POST /datasets/:id/publication/preview` | Manager grants preview access |
| [ ] | 12 | `embargo-access-denied` | requesting user | E7 `DELETE /datasets/:id/publication/preview` | Only if the removed previewer's access was still `Requested` |
| [ ] | 13 | `email-address-changed` (old address) | old email | E8 `PUT /user/email` | Only when `userRequestedChange` is `true` |
| [ ] | 14 | `email-address-changed` (new address) | new email | E8 `PUT /user/email` | Same call as #13 (check both inboxes) |
| [ ] | 15 | `added-to-team` | added user | E9 `POST /organizations/:organizationId/teams/:id/members` | One email per added user |
| [ ] | 16 | `added-to-organization` | added user | E10 `POST /organizations/:id/members` | Invitee email matches an existing user; see note under E10 |
| [ ] | 17 | `invite-external-existing-user-to-dataset` | external user | E11 `PUT /datasets/:nodeId/collaborators/external` | Only when the email is an existing Pennsieve user |
| [ ] | 18 | `change-of-dataset-owner` | new owner | E12 `PUT /datasets/:id/collaborators/owner` | Switch a dataset's owner |

## API endpoint reference

Controllers are mounted at `/datasets/*` (DataSetsController), `/organizations/*`
(OrganizationsController) and `/user/*` (UserController), per `ScalatraBootstrap.scala`.
All calls need a bearer token for the acting user, and the dataset/org must belong to the
org in the user's token context.

**Not verified:** the JSON casing of enum values (`publicationType`, `role`, `status`). They are
Scala enumeratum enums with Circe, so confirm the wire casing against a real response before
building the Postman requests. Dataset `:id` / `:nodeId` path params are strings (node ids)
except E4, which takes the integer dataset id.

### E1. Request publication

`POST /datasets/:id/publication/request` (DataSetsController)

| Where | Name | Type | Notes |
|---|---|---|---|
| path | `id` | string | dataset id |
| query | `publicationType` | enum, required | `Revision`, `Publication`, `Removal`, `Embargo`, `Release`; must match the in-progress workflow. #1/#2 only fire for `Publication` |
| query | `comments` | string, optional | |
| query | `embargoReleaseDate` | date `YYYY-MM-DD`, optional | Required when `publicationType=Embargo`; must be within 1 year |

No JSON body. Not allowed in the demo organization.

### E2. Accept publication request

`POST /datasets/:id/publication/accept`

Same params as E1 (`id`, `publicationType`, `comments`, `embargoReleaseDate`). Needs the
publisher role. Behaviour by `publicationType`:

| publicationType | Email | Other effect |
|---|---|---|
| `Publication` or `Embargo` | #3 `dataset-publication-accepted` | Starts the publish job in Discover |
| `Revision` | #5 `dataset-revision` | Synchronous revise |
| `Release` | #8 `embargo-dataset-release-accepted` | Starts the release in Discover |

### E3. Reject publication request

`POST /datasets/:id/publication/reject`

Same params as E1. Sends #4 only when `publicationType=Publication`. Needs the publisher role.

### E4. Publish complete (Discover callback)

`PUT /datasets/:id/publication/complete`

| Where | Name | Type | Notes |
|---|---|---|---|
| path | `id` | integer | internal dataset id (not the node id) |
| body | `publishedDatasetId` | integer, optional | Discover dataset id |
| body | `publishedVersionCount` | integer | |
| body | `lastPublishedDate` | ISO-8601 datetime, optional | |
| body | `status` | enum | `NotPublished`, `PublishInProgress`, `PublishSucceeded`, `PublishFailed`, `EmbargoInProgress`, `EmbargoSucceeded`, `EmbargoFailed`, … |
| body | `success` | boolean | Email only on success |
| body | `error` | string, optional | |
| body | `publishedVersion` | integer, optional | |
| body | `doi` | string, optional | |

**Superadmin only.** Normally called by Discover after E2, so for #6, #7 and #9 either drive
E2 and let Discover call back, or call this directly with a superadmin token. The email sent
(#6 / #7 / #9) depends on the dataset's latest publication status type (`Publication` /
`Embargo` / `Release`).

### E5. Request embargo preview access

`POST /datasets/publication/preview/request`

| Where | Name | Type | Notes |
|---|---|---|---|
| body | `datasetId` | integer | internal dataset id |
| body | `userId` | integer | the requesting user |
| body | `dataUseAgreementId` | integer, optional | |

**Superadmin only** (the Discover service calls it on the user's behalf). The dataset must be
under embargo, else 400. Emails the dataset managers (#10).

### E6. Grant preview access

`POST /datasets/:id/publication/preview`

| Where | Name | Type | Notes |
|---|---|---|---|
| path | `id` | string | dataset id |
| body | `userId` | integer | user to grant access |

Caller needs the `AddPeople` dataset permission. Always emails the granted user (#11); it
fires whether or not a prior request existed.

### E7. Remove / deny preview access

`DELETE /datasets/:id/publication/preview`

| Where | Name | Type | Notes |
|---|---|---|---|
| path | `id` | string | dataset id |
| body | `userId` | integer | user whose access is removed |

Needs `AddPeople`. Sends #12 only when the removed previewer's access was still
`Requested` (a pending request), so create one with E5 first.

### E8. Update user email

`PUT /user/email`

| Where | Name | Type | Notes |
|---|---|---|---|
| body | `email` | string | new address; must differ from the current one |
| body | `userRequestedChange` | boolean, optional (default false) | **Must be `true` to send #13/#14** |
| body | other `UpdateUserRequest` fields | optional | `firstName`, `lastName`, `middleInitial`, `degree`, `credential`, `organization`, `url`, `color` |

Acts on the logged-in user. It updates the email in Cognito too, so use a throwaway test user
and an address you can read.

### E9. Add members to a team

`POST /organizations/:organizationId/teams/:id/members`

| Where | Name | Type | Notes |
|---|---|---|---|
| path | `organizationId` | string | org node id |
| path | `id` | string | team id |
| body | `ids` | string[] | user node ids to add |

Caller needs Administer on the org. Guest users cannot be added to teams. One email (#15)
per added user.

### E10. Add / invite members to an organization

`POST /organizations/:id/members`

| Where | Name | Type | Notes |
|---|---|---|---|
| path | `id` | string | org node id |
| body | `invites` | array of `{email, firstName, lastName, customMessage?}` | |
| body | `role` | enum, optional (default `Editor`) | |

Caller needs Administer. When the invited email belongs to an existing Pennsieve user, they
are added directly and #16 is sent. A new email address creates a pending invite and
should not send #16 (the invite email itself goes out via Cognito; not verified).

Not fully traced: `sendAddedToOrganizationEmail` is also called from `refreshInvite`
(`PUT /organizations/:organizationId/invites/:id`) when an existing user is found. Treat that as
a possible second trigger and check it if the E10 test passes but you want full coverage.

### E11. Add external collaborator to a dataset

`PUT /datasets/:nodeId/collaborators/external`

| Where | Name | Type | Notes |
|---|---|---|---|
| path | `nodeId` | string | dataset node id |
| body | `id` | string | the invitee's **email address** |
| body | `role` | enum | dataset role: `Guest`, `Viewer`, `Editor`, `Manager`, `Owner` |
| body | `message` | string, optional | custom message in the email |

Needs `AddPeople` and `ChangeRoles` on the dataset. Not allowed in the demo organization.
Sends #17 only when the email matches an existing Pennsieve user. A new address instead goes
through the Cognito invite with a custom welcome message (no email-service message).

### E12. Switch dataset owner

`PUT /datasets/:id/collaborators/owner`

| Where | Name | Type | Notes |
|---|---|---|---|
| path | `id` | string | dataset node id |
| body | `id` | string | node id of the new owner |

Not allowed in the demo organization; the dataset must not be locked. Emails the new owner (#18).

## Known differences from the old direct-SES emails

Confirm these are acceptable rather than treating them as failures:

- **#17 subject:** now "...on a dataset" (the api sent "You have been invited to collaborate").
- **Apostrophes:** #15, #16 and #18 now use straight instead of curly apostrophes.
- **From / Reply-To:** now `Pennsieve <support@{domain}>` instead of `email.support_email`.
- **Delivery is async:** the api returns once the message is queued, not once SES accepts it.
- **Dedupe:** keyed on content hash (no `dedupeId` set), so an identical repeat send to the
  same recipient may be suppressed. Use a changed input, or email-service `--fresh`, to retest.
- **Org branding:** not used; templates resolve to `default/`.
- **#1 and #2** both fire from one publication request.
- Template HTML parity against the old api templates has **not** been compared; read each
  rendered email.

## Out of scope

- Cognito emails (`new-account-creation`, `password-reset`) are sent by Cognito directly.
- `error-publishing` and `invite-external-new-user-to-dataset` templates are never sent.

## Results

Note failures here (messageId, what was wrong, journal status):

-
