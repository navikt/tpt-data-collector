FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25-dev as zizmor-installer

USER root

RUN ARCH=`uname -m` && \
    echo "ARCH: $ARCH" && \
    CHECKSUM="5c7e74a37abd140317631c0be321f49272589b670fc9dfc77766ce97f6fe35f0" && \
    if [ $ARCH = "aarch64" ] ; then CHECKSUM="2187019d69ffebf808f1195cdfa66ba769590e6eec9ad70b7250d68e5a3d2af3" ; fi && \
    echo "CHECKSUM: $CHECKSUM" \
    set -eux && \
    apk update && \
    apk add zizmor && \
    zizmor --version && \
    sha256sum /usr/bin/zizmor && \
    echo "$CHECKSUM  /usr/bin/zizmor" | sha256sum -c

FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25-dev

COPY build/libs/*.jar /app/
COPY --from=zizmor-installer /usr/bin/zizmor /app/

WORKDIR /app

CMD ["app.jar"]
ENTRYPOINT ["java", "-cp", "/app/*", "no.nav.MainKt"]
