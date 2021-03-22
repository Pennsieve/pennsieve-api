#!/bin/bash

# Startup script for the discover Postgres database.

# Given an S3 bucket and key, download a .sql dump file and load it into
# the database before serving connections.

set -e

if [ -z "$S3_PGDUMP_BUCKET" ]; then
    echo "Error: missing S3 bucket"
    exit 1
fi

if [ -z "$S3_PGDUMP_KEY" ]; then
    echo "Error: missing S3 key"
    exit 1
fi

cd $HOME

echo "Getting s3://$S3_PGDUMP_BUCKET/$S3_PGDUMP_KEY"
aws s3 cp "s3://$S3_PGDUMP_BUCKET/$S3_PGDUMP_KEY" dump.sql

echo "Loading dump.sql"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --file dump.sql
