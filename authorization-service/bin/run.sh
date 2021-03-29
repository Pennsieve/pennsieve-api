#!/bin/sh
ARTIFACT_TARGET_PATH=$1

if [ $CLOUDWRAP_ENVIRONMENT = "local" ]
then
  java -jar $ARTIFACT_TARGET_PATH
else
  /usr/bin/java $JAVA_OPTS -jar $ARTIFACT_TARGET_PATH
fi
