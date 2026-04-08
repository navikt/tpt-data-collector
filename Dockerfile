FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21-dev AS zizmor-installer

USER root

# Chainguard rebuilds packages (e.g. CVE patches) without changing the version string, causing SHA drift.
# Pinning the apk version prevents silent upgrades; pin base image by digest for full immutability.
RUN apk add --no-cache zizmor=1.23.1-r5

FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21

COPY build/libs/*.jar /app/
COPY --from=zizmor-installer /usr/bin/zizmor /app/

WORKDIR /app

CMD ["app.jar"]
ENTRYPOINT ["java", "-cp", "/app/*", "no.nav.MainKt"]
