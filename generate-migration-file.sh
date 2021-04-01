#!/usr/bin/env bash

if [[ "$#" -ne 2 ]]; then
    >&2 echo "usage: generate-migration-file.sh [migration type] [filename]"
    exit 1
fi

MIGRATION_TYPE=$1
FILENAME=$2

if [[ "$MIGRATION_TYPE" == "core" ]]; then
    DIRECTORY="migrations/src/main/resources/db/migrations"
elif [[ "$MIGRATION_TYPE" == "organization" ]]; then
    DIRECTORY="migrations/src/main/resources/db/organization-schema-migrations"
elif [[ "$MIGRATION_TYPE" == "timeseries" ]]; then
    DIRECTORY="migrations/src/main/resources/db/timeseries-migrations"
else
    >&2 echo "usage: migration type must be one of 'core', 'timeseries' or 'organization'"
    exit 1
fi

if [[ "$FILENAME" == *.sql ]]; then
    >&2 echo "usage: migration filename cannot end with '.sql'"
    exit 1
fi

DATE=$(date "+%Y%m%d%H%M%S")

FULL_FILENAME="$DIRECTORY/V${DATE}__$FILENAME.sql"

touch $FULL_FILENAME

echo "Success. Created migration file $FULL_FILENAME"
