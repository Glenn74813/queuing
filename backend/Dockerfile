# Build: docker build -t philhealth/qms-backend:<version> backend/
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
RUN groupadd --system qms && useradd --system --gid qms --home /opt/qms qms \
    && mkdir -p /opt/qms/data/media /opt/qms/data/reports && chown -R qms:qms /opt/qms
WORKDIR /opt/qms
COPY --from=build /src/target/qms-backend.jar app.jar
USER qms
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -Duser.timezone=UTC"
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
