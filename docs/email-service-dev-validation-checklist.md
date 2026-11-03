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

## Deploy order and failure behaviour

- **Apply the Terraform before the image.** The api reads the queue URL from the SSM parameter `email-service-queue-url`, which this change's Terraform creates from the email-service remote state. If the `terraform plan` cannot read the email-service state or its outputs, stop there.
- **A missing queue URL stops the deployment.** `api` and `admin` check `email.service_queue_url` once at startup (`verifyEmailConfig`) and refuse to start without it, so a new task never becomes healthy and the previous tasks keep serving. A value that is present but wrong, or missing IAM/KMS permissions, is not caught at startup; it shows up as the failures below.
- **A failed enqueue never blocks `publication/complete`.** The contributor emails there are best-effort: the failure is logged with the marker `EMAIL_ENQUEUE_FAILED` and the dataset still completes and broadcasts. Other send sites still return their existing error. **Alarm:** a CloudWatch metric filter on `EMAIL_ENQUEUE_FAILED` (and the org-event write failure log) belongs in the infrastructure repo; it is not part of this PR.

## Per-email checks

For every row:

- [ ] Email arrives with the expected subject
- [ ] No blank fields or leftover `{{.…}}` in the body
- [ ] `email-message-log` row is `SENT` (`ENV=dev scripts/email-log.sh` in email-service)
- [ ] If nothing arrives, check whether the journal row is `LoggedOnly=true` (a send control fired; not a failure)

## Resources to prepare

Fill the **Actual** column in the checklist with real values at runtime (user email, dataset id, etc.).

**Datasets (all in test org O38, owner = `OWNER`):**

| Name | State needed | Used by |
|---|---|---|
| `DS1` | Ready to publish (see [Dataset setup](#dataset-setup-ready-to-publish)); never published or embargoed | #1–#6 (publish, reject, revision) |
| `DS2` | Ready to publish; never published (an embargo is refused on a dataset that is already published) | #7–#12, #8, #9 (embargo, previews, release) |
| `DS3` | Created only | #17, #18 (collaborator, owner switch) |

**Users:**

| Name | Role | Notes |
|---|---|---|
| `OWNER` | Owner of DS1, DS2, DS3; org member | Needs an ORCID linked, **or** be on the publisher team (publishers get a placeholder ORCID) |
| `PUBLISHER` | Member of the org's Publisher team | Accepts / rejects publication requests |
| `SUPER` | Platform superadmin | E4 callback and E5 preview request are superadmin-only |
| `MANAGER` | Manager on DS1/DS2 (`AddPeople`) | `OWNER` also qualifies |
| `ORG_ADMIN` | Org administrator | Adds team and org members |
| `PREVIEW_1`, `PREVIEW_2` | Existing users who seek embargo preview | Typically outside the org (the embargo-preview use case; not verified the endpoint requires that). Need their integer user ids |
| `EXISTING_USER` | An existing Pennsieve user | Invitee for #16 and #17 |
| `TEAM_MEMBER` | Non-Guest member of O38 | Added to the team in #15 |
| `NEW_OWNER` | A user who can own DS3 | #18 |
| `EMAIL_USER` | Throwaway user | Needs the *before* inbox, and an unused *after* inbox that you can read |

Also: a **Team** in O38 (for #15), and a non-empty Publisher team. Every recipient above needs a real,
readable inbox. Collection variables: `dataset_node_id`, `dataset_int_id` (swap per dataset),
`email_team_id`, `email_test_user_int_id`, `email_test_user2_int_id`, `email_existing_user_email`,
`email_original_address`, `email_new_address`, `new_owner_node_id`.

## Dataset setup (ready to publish)

Publication requests are refused unless the dataset passes `gatherPublicationInfo`
(`DataSetPublishingHelper.scala`). It requires **all** of:

1. a **description** (the subtitle), non-empty;
2. a **license**;
3. a **banner image**;
4. a **readme**;
5. at least one **tag**;
6. at least one **contributor**;
7. the owner has an **ORCID** linked, unless the caller is on the Publisher team.

Steps 1-6 can be scripted as `OWNER` (endpoint shapes read from the code; not yet run against Dev):

| Step | Call | Body |
|---|---|---|
| Create with description, license, tags | `POST /datasets/` | `{"name": "...", "description": "...", "properties": [], "license": "<License>", "tags": ["..."]}` |
| Banner | `PUT /datasets/:id/banner` | multipart form, file field `banner` (an image) |
| Readme | `PUT /datasets/:id/readme` | `{"readme": "..."}` |
| Create a contributor | `POST /contributors/` | `{"firstName": "...", "lastName": "...", "email": "..."}` (returns the contributor id) |
| Attach contributor | `PUT /datasets/:id/contributors` | `{"contributorId": <int>}` |

`license` is a Circe enum with human-readable values, e.g. `"Creative Commons Zero 1.0 Universal"`.
The ORCID link (item 7) comes from the ORCID OAuth flow in the app, so it can't be scripted; use an
owner that already has one, or put the owner on the Publisher team. `DS3` needs only the create call
(name, `"properties": []`). To read integer ids, check the `content` of `GET /datasets/:id` and
`GET /user` (field name not verified).

## Run order

State rules from `validatePublicationStateTransition`:

- an embargo is allowed only on a dataset that has not been published;
- a revision needs a dataset whose last completed workflow was publication, revision or release;
- a release needs an embargo that has completed;
- an embargo preview request (E5) needs the dataset to be **under embargo**, i.e. after #7 and **before** the release.

So the order is: **DS1** `#1+#2` -> `#4` -> (re-request `#1+#2`) -> `#3` -> `#6` -> `pre.revision` -> `#5`.
**DS2** `pre.embargo` -> `#3` (embargo) -> `#7` -> **previews** `#10` -> `#11` -> `#10b` -> `#12` -> `pre.release` -> `#8` -> `#9`.
Then **DS3** `#17` and `#18`, and the independent `#13+#14`, `#15`, `#16`.

## Checklist

One row per send point. Endpoint refs (E1...E12) point to the [reference section](#api-endpoint-reference);
paths are relative to the api host. Fill **Actual** at runtime (who you ran it as, the dataset id, the inbox
you checked).

| Done | # | messageId | Endpoint | Run as | Resource requirements | Actual |
|---|---|---|---|---|---|---|
| [ ] | 1 | `dataset-publication-in-review` | E1 `POST /datasets/:id/publication/request` | `OWNER` | DS1 ready to publish, no publication history; at least one contributor (recipients: owner + contributors); `publicationType=publication` | |
| [ ] | 2 | `dataset-submitted-for-review` | E1 (same call as #1) | `OWNER` | As #1, plus a non-empty Publisher team (recipients) | |
| [ ] | 3 | `dataset-publication-accepted` | E2 `POST /datasets/:id/publication/accept` | `PUBLISHER` | DS1 in Requested (`publication`); or DS2 in Requested (`embargo`) | |
| [ ] | 4 | `dataset-revision-needed` | E3 `POST /datasets/:id/publication/reject` | `PUBLISHER` | DS1 in Requested (`publication`); run before #3 as the alternative branch | |
| [ ] | 5 | `dataset-revision` | E2 (`publicationType=revision`) | `PUBLISHER` | DS1 already published (#6 done), then `pre.revision` requested by `MANAGER`/`OWNER` | |
| [ ] | 6 | `notification-of-publication-to-contributor` | E4 `PUT /datasets/:id/publication/complete` | `SUPER` (normally the Discover callback) | DS1 accepted (#3), latest status still `Accepted`; DS1 integer id | |
| [ ] | 7 | `dataset-embargo-accepted` | E4 (embargo) | `SUPER` | DS2 accepted as `embargo` (#3), latest status `Accepted`; DS2 integer id; `embargoReleaseDate` set on request | |
| [ ] | 8 | `embargo-dataset-release-accepted` | E2 (`publicationType=release`) | `PUBLISHER` | DS2 embargo completed (#7), then `pre.release` requested; do after the previews (#10-#12) | |
| [ ] | 9 | `embargoed-dataset-released` | E4 (release) | `SUPER` | DS2 release accepted (#8), latest status `Accepted` | |
| [ ] | 10 | `embargo-access-requested` | E5 `POST /datasets/publication/preview/request` | `SUPER` | DS2 under embargo (after #7); DS2 integer id; `PREVIEW_1` integer id; DS2 managers exist (recipients) | |
| [ ] | 11 | `embargo-access-approved` | E6 `POST /datasets/:id/publication/preview` | `MANAGER` (needs `AddPeople` on DS2) | DS2 under embargo; `PREVIEW_1` (after #10) | |
| [ ] | 12 | `embargo-access-denied` | E7 `DELETE /datasets/:id/publication/preview` | `MANAGER` (needs `AddPeople` on DS2) | `PREVIEW_2` with a pending request (`#10b` for `PREVIEW_2`); sends nothing if access was already approved | |
| [ ] | 13 | `email-address-changed` (old address) | E8 `PUT /user/email` | `EMAIL_USER` | Before-inbox readable; `userRequestedChange=true`; restore the address afterwards | |
| [ ] | 14 | `email-address-changed` (new address) | E8 (same call as #13) | `EMAIL_USER` | After-inbox readable and not used by another account | |
| [ ] | 15 | `added-to-team` | E9 `POST /organizations/:organizationId/teams/:id/members` | `ORG_ADMIN` | A team in O38; `TEAM_MEMBER` is a non-Guest org member | |
| [ ] | 16 | `added-to-organization` | E10 `POST /organizations/:id/members` | `ORG_ADMIN` | `EXISTING_USER` email (an existing Pennsieve user; a new address sends no #16) | |
| [ ] | 17 | `invite-external-existing-user-to-dataset` | E11 `PUT /datasets/:nodeId/collaborators/external` | `OWNER` (needs `AddPeople` + `ChangeRoles` on DS3) | DS3; `EXISTING_USER` email; not the demo org | |
| [ ] | 18 | `change-of-dataset-owner` | E12 `PUT /datasets/:id/collaborators/owner` | `OWNER` (current owner of DS3) | DS3 not locked; `NEW_OWNER` node id; changes the owner, so reverse it afterwards | |

## API endpoint reference

Controllers are mounted at `/datasets/*` (DataSetsController), `/organizations/*`
(OrganizationsController) and `/user/*` (UserController), per `ScalatraBootstrap.scala`.
All calls need a bearer token for the acting user, and the dataset/org must belong to the
org in the user's token context.

**Enum wire format (from the code, not yet confirmed against Dev):** `PublicationType`,
`PublicationStatus` and `Role` are enumeratum `Snakecase`, so on the wire they are lowercase
snake_case (`publication`, `embargo`, `release`, `revision`, `viewer`); `PublishStatus` is
`UpperSnakecase` (`PUBLISH_SUCCEEDED`). The tables below show the Scala names; send the wire
form. Postman request `#0` logs the observed casing. Dataset `:id` / `:nodeId` path params are
strings (node ids) except E4, which takes the integer dataset id.

**Postman:** these requests are built in `~/git/work/postman/` (collection folder
`pennsieve-api-emails`, driven by `email-send.sh` / `email-verify-log.sh`); see the README section
"Email-service validation" there.

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

**Superadmin only.** The controller treats the call as a duplicate (no-op, no email) unless the
dataset's latest publication status is still `Accepted`. Normally called by Discover after E2, so for #6, #7 and #9 either drive
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
