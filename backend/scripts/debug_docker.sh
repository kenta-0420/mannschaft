#!/bin/bash
docker update --restart=no mannschaft-mysql mannschaft-valkey 2>/dev/null
docker start mannschaft-mysql mannschaft-valkey 2>/dev/null
echo "Started containers. Monitoring..."
for i in $(seq 1 30); do
  s=$(docker inspect mannschaft-mysql --format '{{.State.Status}}' 2>/dev/null)
  if [ "$s" != "running" ]; then
    echo "Container STOPPED at second $((i*2))"
    docker inspect mannschaft-mysql --format 'ExitCode={{.State.ExitCode}} FinishedAt={{.State.FinishedAt}}' 2>/dev/null
    docker events --since 60s --until 0s --filter container=mannschaft-mysql 2>/dev/null | head -10
    exit 0
  fi
  sleep 2
done
echo "Still running after 60 seconds"
