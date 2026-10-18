# Build stage
FROM eclipse-temurin:21.0.6_7-jdk AS build
WORKDIR /build

COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -DskipTests package

# Extract layers stage
FROM build AS extract
WORKDIR /build
RUN java -Djarmode=tools -jar target/rejuv-0.0.1-SNAPSHOT.jar extract --layers --launcher --destination /extracted

# Runtime stage
FROM eclipse-temurin:21.0.6_7-jre AS runtime
WORKDIR /app

RUN apt-get update && \
    apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd -g 10001 appgroup && \
    useradd -u 10001 -g 10001 -M -s /usr/sbin/nologin appuser && \
    chown -R appuser:appgroup /app

COPY --from=extract --chown=appuser:appgroup /extracted/dependencies/ ./
COPY --from=extract --chown=appuser:appgroup /extracted/spring-boot-loader/ ./
COPY --from=extract --chown=appuser:appgroup /extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=appuser:appgroup /extracted/application/ ./

USER appuser

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Djava.awt.headless=true"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
