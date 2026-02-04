FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21

COPY build/libs/*.jar /app/
COPY zizmor /app/

WORKDIR /app

CMD ["app.jar"]
ENTRYPOINT ["java", "-cp", "/app/*", "no.nav.MainKt"]
