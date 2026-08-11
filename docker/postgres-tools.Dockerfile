# syntax=docker/dockerfile:1.7
# Developer/test helper image. Production deployment does not depend on containers.
FROM postgres:17.10-alpine3.24

COPY --chmod=0555 docker/init-secrets.sh /opt/infranexum/docker/init-secrets.sh
COPY --chmod=0555 docker/migrate-postgresql.sh /opt/infranexum/docker/migrate-postgresql.sh
COPY --chmod=0555 docker/rollback-postgresql.sh /opt/infranexum/docker/rollback-postgresql.sh
