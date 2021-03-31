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

You will need access to the nexus repository manager. Once an account has been created for you, add the following to
your .bashrc or .zshrc file:

```
export PENNSIEVE_NEXUS_USER=[NEXUS USERNAME]
export PENNSIEVE_NEXUS_PW=[REDACTED]
```
For intellij to work you will also need to add the following file at `~/Library/LaunchAgents/environment.plist`:

```xml
 <?xml version="1.0" encoding="UTF-8"?>
 <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
 <plist version="1.0">
 <dict>
  <key>Label</key>
  <string>my.startup</string>
  <key>ProgramArguments</key>
  <array>
    <string>sh</string>
    <string>-c</string>
    <string>
    launchctl setenv PENNSIEVE_NEXUS_USER [NEXUS_USERNAME]
    launchctl setenv PENNSIEVE_NEXUS_PW [REDACTED]
    </string>
  </array>
  <key>RunAtLoad</key>
  <true/>
 </dict>
 </plist>

```

Then run `launchctl load ~/Library/LaunchAgents/environment.plist` and restart intellij for it to talk to the
nexus server.  You don't need to rerun this command as OSX will do it for you in the future. To compile or test code
across both projects use the regular sbt commands:

```bash
sbt compile
sbt test
```

To run just the the core compile or test by themselves use

```bash
sbt core/compile
sbt core/test
```

## Migrations

SQL migration files live in the `migrations` subproject. There are two types of migrations:

* Core migrations on the `pennsieve` schema
* Organization migrations on the numeric organization schemas (`1`, `2`, etc)

Jenkins runs the migrations against Postgres.

Use the `generate-migration-file.sh` script to create an empty migration file in the appropriate place.


## Deployment

Merging to `main` deploys all services in this repo to the dev environment and runs the Postgres migrations.

Individual services are deployed to production via Jenkins service-deploy jobs. Use the
[pennsieve-api-release](https://jenkins.pennsieve.cc/job/service-deploy/job/pennsieve-prod/job/us-east-1/job/prod-vpc-use1/job/prod/job/pennsieve-api-release/)
job to deploy _all_ services to production and (optionally) run the Postgres
migrations.
