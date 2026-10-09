FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Optional trusted CA certificates for Maven downloads through HTTPS inspection.
COPY docker/certs/ /tmp/build-certs/
RUN set -eu; \
    for cert in /tmp/build-certs/*.crt; do \
        [ -f "$cert" ] || continue; \
        keytool -importcert -noprompt -trustcacerts \
            -alias "build-ca-$(basename "$cert" .crt)" \
            -file "$cert" -cacerts -storepass changeit; \
    done; \
    rm -rf /tmp/build-certs
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S -G app app
COPY --from=build --chown=app:app /build/target/social-network-0.0.1-SNAPSHOT.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=Europe/Moscow", "-jar", "/app/app.jar"]
