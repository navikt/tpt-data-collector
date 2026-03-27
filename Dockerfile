FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-26-dev AS zizmor-installer

USER root

# e19a1651fef8429db9721573b5644b5cd696f893e2273c6338c88b1d34ace785 = 1.23.1-r0
RUN ARCH=`uname -m` && \
    echo "ARCH: $ARCH" && \
    CHECKSUM="e19a1651fef8429db9721573b5644b5cd696f893e2273c6338c88b1d34ace785" && \
    if [ $ARCH = "aarch64" ] ; then CHECKSUM="0e14525857aa60d44b3f4d266dc6b8511fac5e269e94e1bdd5bbf0edbe5fc4d2" ; fi && \
    echo "Expected zizmor CHECKSUM: $CHECKSUM" && \
    set -eux && \
    apk update && \
    apk add zizmor && \
    echo "Zizmor version" && \
    zizmor --version && \
    echo "Zizmor checksum" && \
    sha256sum /usr/bin/zizmor && \
    echo "$CHECKSUM  /usr/bin/zizmor" | sha256sum -c

FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-26

COPY build/libs/*.jar /app/
COPY --from=zizmor-installer /usr/bin/zizmor /app/

WORKDIR /app

CMD ["app.jar"]
ENTRYPOINT ["java", "-cp", "/app/*", "no.nav.MainKt"]
