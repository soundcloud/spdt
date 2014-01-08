#!/bin/sh

java \
  -Duser.home=/tmp \
  -Dsbt.repository.config=$PWD/project/repositories \
  -Dsbt.ivy.home=$PWD/project/ivy2/ \
  -Dsbt.boot.directory=$PWD/project/boot/ \
  -Djava.io.tmpdir=${TMPDIR-/tmp} \
  -Dlogback.configurationFile="file://`pwd`/config/logback.build.xml" \
  -Dprocess.name="test" \
  -Dmetrics.namespace="spdt" \
  -Dfile.encoding="UTF-8" \
  -Xms512M \
  -Xmx8192M \
  -Xss1M \
  -XX:+CMSClassUnloadingEnabled \
  -XX:MaxPermSize=1024M \
  -jar bin/sbt-launch.jar "$@"
