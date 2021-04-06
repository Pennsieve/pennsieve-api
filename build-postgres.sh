#!/bin/bash

# Create a postgres Docker image that contains the Pennsieve API schema.

set -eu

REGISTRY="pennsieve"
REPO="pennsievedb"
ENVIRONMENT="${ENVIRONMENT:-local}"

if [[ "$ENVIRONMENT" != "local" ]]; then
    echo "Environment is $ENVIRONMENT. Getting tag from git log and pushing images"
    TAG="$(git log --name-only --oneline -2 | tail -n +2 | grep -E migrations/src/main/resources/db | xargs basename -a | grep -E '^V.*' | awk -F '_' '{print $1}' | sort -u | tail -n 1)"
else
    echo "Environment is local. Getting tag from local filesystem and not pushing images"
    TAG="$(find migrations/src/main/resources/db | xargs basename -a | grep -E '^V.*' | awk -F '_' '{print $1}' | sort -u | tail -n 1)"
fi

###################################
run_migrations() {
  echo -e "\nBuilding database container.\n"
  docker-compose down -v --remove-orphans
  docker-compose rm -f
  docker-compose build --pull
  sbt migrations/docker

  echo -e "\nStarting database container.\n"
  docker-compose up -d postgres && sleep 5
  container=$(docker-compose ps postgres | tail -n 1 | awk '{print $1}')

  echo -e "\nRunning migrations....\n"

  docker-compose run -e MIGRATION_TYPE=timeseries -e MIGRATION_BASELINE=true migrations
  docker-compose run -e MIGRATION_TYPE=core -e MIGRATION_BASELINE=true migrations

  while true; do
    HEALTH=$(docker inspect --format='{{.State.Health.Status}}' $container)
    [ "$HEALTH" != healthy ] || break
    sleep 1
  done

  echo -e "\nMigrations complete.\n"
}

create_container() {
  tag=$1
  container=$(docker-compose ps postgres | tail -n 1 | awk '{print $1}')
  container_id=$(docker inspect --format='{{.Id}}' $container)

  echo -e "\nCreating new pennsieve/pennsievedb:$tag from $container_id\n"
  docker-compose stop

  if [[ $tag =~ .*seed.* ]]; then
    docker commit $container_id $REGISTRY/$REPO:latest-seed
    docker tag $REGISTRY/$REPO:latest-seed $REGISTRY/$REPO:$tag

    if [[ "$ENVIRONMENT" != "local" ]]; then
      echo -e "\nPushing $REGISTRY/$REPO:latest-seed\n"
      docker push $REGISTRY/$REPO:latest-seed

      echo -e "\nPushing $REGISTRY/$REPO:$tag\n"
      docker push $REGISTRY/$REPO:$tag
    fi
  else
    docker commit $container_id $REGISTRY/$REPO
    docker tag $REGISTRY/$REPO:latest $REGISTRY/$REPO:$tag

    if [[ "$ENVIRONMENT" != "local" ]]; then
        echo -e "\nPushing $REGISTRY/$REPO:$tag\n"
        docker push $REGISTRY/$REPO:$tag

        echo -e "\nPushing $REGISTRY/$REPO:latest\n"
        docker push $REGISTRY/$REPO
    fi
  fi
}

seed_db_container() {
  docker-compose up -d postgres && sleep 5

  docker-compose run -e MIGRATION_TYPE=organization -e ORGANIZATION_SCHEMA_COUNT=3 migrations

  container=$(docker-compose ps postgres | tail -n 1 | awk '{print $1}')
  docker cp local-seed.sql $container:/local-seed.sql
  docker-compose exec -T postgres sh -c "psql -v ON_ERROR_STOP=1 postgres < /local-seed.sql"
}
###################################

echo -e "\nStarting build-postgres script...\n"

if [[ ! -z $TAG ]]; then
  echo -e "\nCreating a new image with tag: $TAG\n"
  run_migrations
  create_container $TAG
  seed_db_container
  create_container $TAG-seed
  docker-compose down -v
  docker-compose rm -f
else
  echo -e "\nCould not find a valid tag. Not creating a pennsievedb image.\n"
fi

echo -e "\nThe build-postgres script is complete.\n"
