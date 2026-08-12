# syntax=docker/dockerfile:1.7
# Developer/test PostgreSQL HA node for the PRO Compose topology.
FROM postgres:17.10-alpine3.24

ARG PATRONI_VERSION=4.1.4
RUN apk add --no-cache python3 py3-pip su-exec curl ca-certificates \
    && python3 -m venv /opt/patroni \
    && /opt/patroni/bin/pip install --no-cache-dir --disable-pip-version-check \
         "patroni[psycopg3,etcd3]==${PATRONI_VERSION}"

COPY --chmod=0555 docker/patroni-entrypoint.sh /opt/infranexum/docker/patroni-entrypoint.sh
ENTRYPOINT ["/opt/infranexum/docker/patroni-entrypoint.sh"]
