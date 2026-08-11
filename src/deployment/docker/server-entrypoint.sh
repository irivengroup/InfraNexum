#!/bin/sh
set -eu

password_file="${INFRANEXUM_DATABASE_PASSWORD_FILE:-/run/infranexum-secrets/db-password}"
if [ ! -r "$password_file" ]; then
  echo "InfraNexum database password file is not readable: $password_file" >&2
  exit 70
fi
export INFRANEXUM_DATABASE_PASSWORD="$(cat "$password_file")"
if [ -z "$INFRANEXUM_DATABASE_PASSWORD" ]; then
  echo "InfraNexum database password file is empty" >&2
  exit 70
fi

exec java \
  -XX:MaxRAMPercentage="${INFRANEXUM_JAVA_MAX_RAM_PERCENTAGE:-75.0}" \
  -Djava.security.egd=file:/dev/urandom \
  -jar /opt/infranexum/server.jar "$@"
