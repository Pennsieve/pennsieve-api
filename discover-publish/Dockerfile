FROM pennsieve/postgres:12.5

USER root

RUN apk update \
    && apk add --no-cache python3 py3-pip \
    && pip3 install awscli==1.16.149 \
    && aws --version

USER postgres

COPY healthcheck.sh /healthcheck.sh
COPY load-dump.sh /docker-entrypoint-initdb.d
