# Pennsieve API

## Overview
This is the main repository for the Pennsieve api and shared core library of middleware for interacting with the graph store.
The project is currently composed of the api code in the api sub directory and the core middelware
code in the core subdirectory.

## Deployment

### Jobs

#### Environment

- Dev - Merges into the `DEVELOPMENT` branch will automatically deploy to the dev envionment.
- Prod - Merges into the `master` branch require a manual deploy [here](https://jenkins.pennsieve.io/view/Deploy%20Jobs/job/service-deploy/job/pennsieve-prod/job/us-east-1/job/prod-vpc/job/prod/job/jobs/).

## Development

To develop effectively with this project you will need the following tools:
  - [docker for mac](https://store.docker.com/editions/community/docker-ce-desktop-mac?tab=description)

This project is set up to use docker to quickly develop locally using [testcontainers-scala](https://github.com/testcontainers/testcontainers-scala)

## Building and Testing

Pennsieve's own libraries (`core-models`, `auth-middleware` and the rest) come from Pennsieve's AWS CodeArtifact. sbt gets a short-lived token itself with your AWS credentials, so run it from a shell where you have an MFA session or have assumed a role:

```bash
assume-role ccmfa x           # from cli-utils: MFA session in pennsieve-cc, no role needed
assume-role non-prod admin    # also works
```

IntelliJ needs the same credentials. Start it from that shell (for example `idea .`). Alternatively, run `ps-codeartifact-login` (cli-utils) and give IntelliJ's sbt the `CODEARTIFACT_AUTH_TOKEN` it sets; the token lasts as long as your session. To compile or test code
across both projects use the regular sbt commands:

```bash
sbt +compile
sbt +test
```

The leading `+` will take care of cross compiling any modules that are configured for more than one version of Scala.

To run just the the core compile or test by themselves use

```bash
sbt core/compile
sbt core/test
```

## Migrations

SQL migrations live in the separate [pennsieve-db-migrations](https://github.com/Pennsieve/pennsieve-db-migrations) repo, which publishes the `pennsieve/pennsievedb:*-seed` Docker image consumed by the test harness here. There is no in-process migration step in this repo — tests start against the already-migrated seed image. To add or modify migrations, open a PR in `pennsieve-db-migrations`.

When a new seed image is published, bump the pinned tag in `core/src/test/scala/com/pennsieve/test/PostgresDockerContainer.scala`.


## Deployment

Merging to `main` deploys all services in this repo to the dev environment. Postgres migrations run from the `pennsieve-db-migrations` pipeline, not this one.

Individual services are deployed to production via Jenkins service-deploy jobs. Use the
[pennsieve-api-release](https://jenkins.pennsieve.cc/job/service-deploy/job/pennsieve-prod/job/us-east-1/job/prod-vpc-use1/job/prod/job/pennsieve-api-release/)
job to deploy _all_ services to production and (optionally) run the Postgres
migrations.

## Update documentation

We have an API Reference section on https://docs.pennsieve.io, managed by https://readme.io. This service's API reference is mantained manually rather than automatically.
The steps to update the API Reference are:
1. Open https://api.pennsieve.io/api-docs/swagger.json and download the file to a scratch directory as `swagger.json`.
2. This generated file will have some validation errors that readme.io will not accept. In your scratch directory run `./<path-to-this-repo>/fix-swagger.py swagger.json`
By default this will output `swagger.fixed.json` with those validation errors fixed. See `fix-swagger.py -h` for other options.
3. Go to readme.io > Api Reference and upload the fixed file to replace the current documentation.

rev. 2026/08/13
