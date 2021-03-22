#!/usr/bin/env sh

# Run from pennsieve-api/discover-publish

set -x
set -e

IMAGE_TAG=debug

docker build . -t pennsieve/discover-pgdump-postgres:$IMAGE_TAG

# This is slow, and can be commented out if the discover-publish code does not change
cd ..
sbt "discover-publish/docker"
cd -

docker tag pennsieve/discover-publish:latest pennsieve/discover-publish:$IMAGE_TAG

docker push pennsieve/discover-pgdump-postgres:$IMAGE_TAG
docker push pennsieve/discover-publish:$IMAGE_TAG

cd ../../infrastructure/aws/pennsieve-non-prod/us-east-1/dev-vpc-use1/dev/discover-publish
./terraform.sh apply -var="image_tag=$IMAGE_TAG"
cd -
