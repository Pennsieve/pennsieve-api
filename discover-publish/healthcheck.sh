#!/bin/bash
set -eo pipefail

# Force Postgres to test external connectivity, instead of testing connectivity
# on the localhost loopback interface. Localhost is available even while the
# container is running entrypoint scripts. The external address only becomes
# available when all data is loaded.
#
# See https://github.com/docker-library/postgres/issues/146 for information on
# how the Postgres Docker container starts (and restarts) when running scripts
# in docker-entrypoint-initdb.d
#
# Adapted from https://github.com/docker-library/healthcheck/blob/master/postgres/docker-healthcheck

args=(
    --host "$(hostname -i)"
    --username "${POSTGRES_USER}"
    --dbname "${POSTGRES_DB}"
    --quiet --no-align --tuples-only
)

export PGPASSWORD="${POSTGRES_PASSWORD}"

if select="$(echo 'SELECT 1' | psql "${args[@]}")" && [ "$select" = '1' ]; then
    exit 0
fi

exit 1
