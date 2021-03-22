#!/usr/bin/env sh

set -e

if [ -z "$AWS_SESSION_TOKEN" ]; then
  echo "Missing AWS session token. Call assume-role first"
  exit 1
fi

STATE_MACHINE="arn:aws:states:us-east-1:300018926035:stateMachine:dev-discover-publish-state-machine-use1"
VERSION=1000

EXECUTION_ARN=$(aws stepfunctions start-execution --state-machine-arn $STATE_MACHINE --input '
{
  "organization_id": "10",
  "organization_node_id": "N:organization:68170b4d-19d0-4a3e-ab23-ee5097c70df4",
  "organization_name": "Hells Angels",
  "dataset_id": "5",
  "dataset_node_id": "N:dataset:25a6e089-2f91-4420-977c-a121a9146204",
  "published_dataset_id": "13",
  "user_id": "11",
  "user_node_id": "N:user:0cd35396-6145-406f-a30e-988213b30a04",
  "user_first_name": "Bo",
  "user_last_name": "Marchman",
  "user_orcid": "0000-0003-4794-225X",
  "s3_pgdump_key": "13/1000/dump.sql",
  "s3_publish_key": "13/1000/",
  "version": "1000",
  "doi": "10.21397/6wdz-9llu",
  "contributors": "[{\"id\":28,\"first_name\":\"Bo\",\"last_name\":\"Marchman\",\"orcid\":\"0000-0003-4794-225X\"}]"
}
' | jq .executionArn --raw-output)

echo
echo "You can follow the execution here:"
echo
echo "  https://console.aws.amazon.com/states/home?region=us-east-1#/executions/details/$EXECUTION_ARN"
echo
