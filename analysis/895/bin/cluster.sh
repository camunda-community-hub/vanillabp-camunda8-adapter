#!/bin/bash
# Starts, disturbs and stops the cluster of analysis 895: a Camunda 8 cluster of the GA line
# whose secondary storage is an RDBMS in a container of its own, so the storage can be taken
# away without taking the engine with it.
#
#   cluster.sh up            network, PostgreSQL, Camunda (REST 18080, gRPC 26500, management 19600)
#   cluster.sh storage-stop  secondary storage answers with an error (connection refused)
#   cluster.sh storage-pause secondary storage does not answer at all (frozen container)
#   cluster.sh storage-back  undoes either of the two
#   cluster.sh export-pause  the exporter stops, engine and storage keep running
#   cluster.sh export-resume the exporter catches up
#   cluster.sh down          removes exactly what 'up' started
set -euo pipefail
IMAGE=${CAMUNDA_IMAGE:-camunda/camunda:8.10.0}
NET=a895-net
PG=a895-postgres
C8=a895-camunda
case "${1:-}" in
  up)
    docker network create $NET >/dev/null 2>&1 || true
    docker run -d --name $PG --network $NET -e POSTGRES_USER=camunda -e POSTGRES_PASSWORD=camunda \
      -e POSTGRES_DB=camunda postgres:16-alpine >/dev/null
    until docker exec $PG pg_isready -U camunda >/dev/null 2>&1; do sleep 1; done
    docker run -d --name $C8 --network $NET -p 18080:8080 -p 26500:26500 -p 19600:9600 \
      -e CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTEDAPI=true \
      -e CAMUNDA_DATA_SECONDARYSTORAGE_TYPE=rdbms \
      -e CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_URL=jdbc:postgresql://$PG:5432/camunda \
      -e CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_USERNAME=camunda \
      -e CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_PASSWORD=camunda \
      -e JAVA_TOOL_OPTIONS="-Xmx2g" \
      $IMAGE >/dev/null
    timeout 300 bash -c "until curl -sf http://localhost:19600/actuator/health/readiness >/dev/null; do sleep 5; done"
    echo "cluster ready"
    ;;
  storage-stop)  docker stop $PG >/dev/null; echo "storage stopped $(date +%T)";;
  storage-pause) docker pause $PG >/dev/null; echo "storage paused $(date +%T)";;
  storage-back)
    if [ "$(docker inspect -f '{{.State.Paused}}' $PG)" = "true" ]; then docker unpause $PG >/dev/null; else docker start $PG >/dev/null; fi
    echo "storage back $(date +%T)";;
  export-pause)  curl -s -X POST http://localhost:19600/actuator/exporting/pause; echo " exporting paused $(date +%T)";;
  export-resume) curl -s -X POST http://localhost:19600/actuator/exporting/resume; echo " exporting resumed $(date +%T)";;
  down)
    docker rm -f $C8 $PG >/dev/null 2>&1 || true
    docker network rm $NET >/dev/null 2>&1 || true
    echo "cluster removed";;
  *) echo "usage: $0 up|storage-stop|storage-pause|storage-back|export-pause|export-resume|down"; exit 2;;
esac
