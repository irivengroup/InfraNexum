# syntax=docker/dockerfile:1.7
FROM ubuntu:noble-20260730.1

ARG TARGETARCH
ARG NODE_VERSION=24.19.0

# Node.js is downloaded from the upstream release archive and verified against
# the published SHA-256 values. Only the runtime prerequisites are retained.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl xz-utils libstdc++6 \
    && rm -rf /var/lib/apt/lists/* \
    && case "${TARGETARCH}" in \
         amd64) node_arch="x64"; expected="d6c664df3f3f61458e8c277585571328522d705166723a7c7823a9253a4d15a0" ;; \
         arm64) node_arch="arm64"; expected="7201e3a09dc825bac57867c81913e2b8f0ef87d04cb9082af4cda82f6ff3d88c" ;; \
         *) echo "Unsupported Docker architecture: ${TARGETARCH}" >&2; exit 64 ;; \
       esac \
    && archive="node-v${NODE_VERSION}-linux-${node_arch}.tar.xz" \
    && url="https://nodejs.org/dist/v${NODE_VERSION}/${archive}" \
    && curl --fail --location --proto '=https' --tlsv1.2 --retry 3 --output /tmp/node.tar.xz "${url}" \
    && printf '%s  %s\n' "${expected}" /tmp/node.tar.xz | sha256sum --check --strict \
    && mkdir -p /opt/node \
    && tar -xJf /tmp/node.tar.xz --strip-components=1 -C /opt/node \
    && rm -f /tmp/node.tar.xz \
    && /opt/node/bin/node --version | grep -Fx "v${NODE_VERSION}" >/dev/null \
    && groupadd --gid 10002 infranexum-web \
    && useradd --uid 10002 --gid 10002 --home-dir /nonexistent --shell /usr/sbin/nologin infranexum-web

ENV PATH="/opt/node/bin:${PATH}" \
    INFRANEXUM_WEB_LISTEN_ADDRESS="0.0.0.0:8080" \
    INFRANEXUM_WEB_STATIC_ROOT="./public" \
    INFRANEXUM_WEB_SHUTDOWN_TIMEOUT_MS="20000"

WORKDIR /opt/infranexum/web
COPY --chown=10002:10002 src/applications/web/package.json ./package.json
COPY --chown=10002:10002 src/applications/web/runtime ./runtime
COPY --chown=10002:10002 src/applications/web/public ./public

USER 10002:10002
EXPOSE 8080
STOPSIGNAL SIGTERM
CMD ["node", "runtime/main.mjs"]
