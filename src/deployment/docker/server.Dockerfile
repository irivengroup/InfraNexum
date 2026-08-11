# syntax=docker/dockerfile:1.7
# InfraNexum pins the Java patch release independently of the base OS image so the
# container toolchain is identical to the hosted JDK 25 certification toolchain.
FROM ubuntu:noble-20260730.1 AS build
ARG TARGETARCH
ARG INFRANEXUM_EXPECTED_JAVA_VERSION=25.0.4
ARG TEMURIN_RELEASE=25.0.4_7
ARG TEMURIN_JDK_AMD64_SHA256=e58fcdcd637b25c03ca84cbbcefc70d11efb8f4b4cbd05decc9f661769d77f94
ARG TEMURIN_JDK_ARM64_SHA256=621f7196f0b682fb557da58bec89bd7dfe5419811fe1c0ba75c9cc8432f084c7
RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl tar gzip \
    && rm -rf /var/lib/apt/lists/* \
    && case "${TARGETARCH}" in \
         amd64) archive="OpenJDK25U-jdk_x64_linux_hotspot_${TEMURIN_RELEASE}.tar.gz"; expected="${TEMURIN_JDK_AMD64_SHA256}" ;; \
         arm64) archive="OpenJDK25U-jdk_aarch64_linux_hotspot_${TEMURIN_RELEASE}.tar.gz"; expected="${TEMURIN_JDK_ARM64_SHA256}" ;; \
         *) echo "Unsupported Docker architecture: ${TARGETARCH}" >&2; exit 64 ;; \
       esac \
    && url="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4%2B7/${archive}" \
    && curl --fail --location --proto '=https' --tlsv1.2 --retry 3 --output /tmp/temurin.tar.gz "${url}" \
    && printf '%s  %s\n' "${expected}" /tmp/temurin.tar.gz | sha256sum --check --strict \
    && mkdir -p /opt/java/openjdk \
    && tar -xzf /tmp/temurin.tar.gz --strip-components=1 -C /opt/java/openjdk \
    && rm -f /tmp/temurin.tar.gz
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
RUN java -version 2>&1 | grep -F "${INFRANEXUM_EXPECTED_JAVA_VERSION}" >/dev/null
WORKDIR /workspace
COPY . .
RUN chmod 0755 ./mvnw \
    && ./mvnw --batch-mode --no-transfer-progress \
       -Dmaven.test.skip=true -Djacoco.skip=true \
       -pl src/applications/server -am package \
    && cp src/applications/server/target/infranexum-server-*.jar /tmp/infranexum-server.jar

FROM ubuntu:noble-20260730.1 AS runtime
ARG TARGETARCH
ARG INFRANEXUM_EXPECTED_JAVA_VERSION=25.0.4
ARG TEMURIN_RELEASE=25.0.4_7
ARG TEMURIN_JRE_AMD64_SHA256=aed3915f8facc0c80733ab2448bb0df4b494a36a2c5759e9a6e1eb979720f2b3
ARG TEMURIN_JRE_ARM64_SHA256=1f2644427000316bc431df3389504551ed7464fe8486bf6b4f1130af9ffc8f55
RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl tar gzip \
    && case "${TARGETARCH}" in \
         amd64) archive="OpenJDK25U-jre_x64_linux_hotspot_${TEMURIN_RELEASE}.tar.gz"; expected="${TEMURIN_JRE_AMD64_SHA256}" ;; \
         arm64) archive="OpenJDK25U-jre_aarch64_linux_hotspot_${TEMURIN_RELEASE}.tar.gz"; expected="${TEMURIN_JRE_ARM64_SHA256}" ;; \
         *) echo "Unsupported Docker architecture: ${TARGETARCH}" >&2; exit 64 ;; \
       esac \
    && url="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4%2B7/${archive}" \
    && curl --fail --location --proto '=https' --tlsv1.2 --retry 3 --output /tmp/temurin.tar.gz "${url}" \
    && printf '%s  %s\n' "${expected}" /tmp/temurin.tar.gz | sha256sum --check --strict \
    && mkdir -p /opt/java/openjdk \
    && tar -xzf /tmp/temurin.tar.gz --strip-components=1 -C /opt/java/openjdk \
    && rm -f /tmp/temurin.tar.gz \
    && groupadd --gid 10001 infranexum \
    && useradd --uid 10001 --gid 10001 --home-dir /nonexistent --shell /usr/sbin/nologin infranexum \
    && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
RUN java -version 2>&1 | grep -F "${INFRANEXUM_EXPECTED_JAVA_VERSION}" >/dev/null
WORKDIR /opt/infranexum
COPY --from=build --chown=10001:10001 /tmp/infranexum-server.jar /opt/infranexum/server.jar
COPY --chown=10001:10001 src/deployment/docker/server-entrypoint.sh /opt/infranexum/bin/server-entrypoint.sh
RUN chmod 0555 /opt/infranexum/bin/server-entrypoint.sh \
    && mkdir -p /var/lib/infranexum/integrity \
    && chown -R 10001:10001 /var/lib/infranexum
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/opt/infranexum/bin/server-entrypoint.sh"]
