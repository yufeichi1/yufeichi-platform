#!/usr/bin/env bash
# Controlled Day6 restart test: run after acceptance.py before. Causes a brief outage.
set -euo pipefail
test "$(id -u)" = 0
test -f /var/lib/yufeichi/day6-acceptance.json
compose=(docker compose -f /opt/yufeichi/deploy/compose.yml)
"${compose[@]}" restart mysql redis
"${compose[@]}" up -d --wait --wait-timeout 180
previous_pid=$(systemctl show yufeichi -p MainPID --value)
test "$previous_pid" -gt 0
systemctl kill --kill-who=main --signal=KILL yufeichi
sleep 2
recovered=false
for attempt in $(seq 1 90); do
    next_pid=$(systemctl show yufeichi -p MainPID --value)
    if test "$next_pid" -gt 0 && test "$next_pid" != "$previous_pid" && curl -fsS http://127.0.0.1:8080/api/health >/dev/null 2>&1; then
        recovered=true
        break
    fi
    sleep 2
done
test "$recovered" = true
python3 /opt/yufeichi/deploy/acceptance.py after
systemctl show yufeichi -p NRestarts -p MainPID -p User -p ActiveState -p UnitFileState
printf 'PASS: container restart and unplanned JVM termination recovered\n'
