#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" = 0
exec 9>/run/lock/yufeichi-ops.lock
flock -n 9 || { echo 'Another deployment/backup operation is running' >&2; exit 75; }
release=${1:?Usage: activate-release.sh YYYYMMDDTHHMMSSZ-commit}
[[ "$release" =~ ^[0-9]{8}T[0-9]{6}Z-[a-f0-9]{7,40}$ ]] || exit 2
backend="/opt/yufeichi/backend/releases/$release"
frontend="/var/www/yufeichi-app/releases/$release"
test -f "$backend/yufeichi-server.jar"
test -f "$frontend/index.html"
find "$backend" "$frontend" -type d -exec chmod 755 {} +
find "$backend" "$frontend" -type f -exec chmod 644 {} +
test -s /var/backups/yufeichi/day6-initial/pre-deploy.tar.gz
java_home=/usr/lib/jvm/java-21-openjdk-amd64
"$java_home/bin/java" -version 2>&1 | grep -q 'version "21\.'

# Preserve all previous releases; replace only the current symlinks.
previous_backend=$(readlink -f /opt/yufeichi/backend/current)
previous_frontend=$(readlink -f /var/www/yufeichi-app/current)
nginx_backup="/var/backups/yufeichi/nginx-before-$release.conf"
cp -a /etc/nginx/sites-available/yufeichi "$nginx_backup"
activated=false
rollback() {
    if [ "$activated" != true ]; then
        ln -sfn "$previous_backend" /opt/yufeichi/backend/current.rollback
        mv -Tf /opt/yufeichi/backend/current.rollback /opt/yufeichi/backend/current
        ln -sfn "$previous_frontend" /var/www/yufeichi-app/current.rollback
        mv -Tf /var/www/yufeichi-app/current.rollback /var/www/yufeichi-app/current
        cp -a "$nginx_backup" /etc/nginx/sites-available/yufeichi
        systemctl restart yufeichi
        nginx -t && systemctl reload nginx
        echo 'Activation failed; previous release restored' >&2
    fi
}
trap rollback EXIT
ln -s "$backend" /opt/yufeichi/backend/current.next
mv -Tf /opt/yufeichi/backend/current.next /opt/yufeichi/backend/current
install -m 644 /opt/yufeichi/deploy/yufeichi.service /etc/systemd/system/yufeichi.service
systemctl daemon-reload
systemctl enable yufeichi
systemctl restart yufeichi
healthy=false
for attempt in $(seq 1 90); do
    if curl -fsS http://127.0.0.1:8080/api/health >/dev/null; then healthy=true; break; fi
    sleep 2
done
test "$healthy" = true

ln -s "$frontend" /var/www/yufeichi-app/current.next
mv -Tf /var/www/yufeichi-app/current.next /var/www/yufeichi-app/current
install -m 644 /opt/yufeichi/deploy/nginx.conf /etc/nginx/sites-available/yufeichi
if ! nginx -t; then
    cp -a "$nginx_backup" /etc/nginx/sites-available/yufeichi
    exit 1
fi
systemctl reload nginx
activated=true
printf 'Activated backend and frontend release: %s\n' "$release"
