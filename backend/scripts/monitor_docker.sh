#!/bin/bash
echo "Monitoring MySQL container..."
for i in $(seq 1 60); do
  s=$(docker inspect mannschaft-mysql --format '{{.State.Status}}' 2>/dev/null)
  if [ "$s" != "running" ]; then
    echo "STOPPED at second $i: status=$s"
    docker inspect mannschaft-mysql --format 'Exit={{.State.ExitCode}} Finished={{.State.FinishedAt}}' 2>/dev/null
    exit 0
  fi
  sleep 1
done
echo "Still running after 60 seconds!"
