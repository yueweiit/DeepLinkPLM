ARG BUILD_IMAGE=maven:3.9-eclipse-temurin-17
ARG RUNTIME_IMAGE=eclipse-temurin:17-jre

FROM ${BUILD_IMAGE} AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B -q

FROM ${RUNTIME_IMAGE} AS runtime-base
USER root
WORKDIR /app
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3 openssh-client ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && (getent group plm >/dev/null || groupadd -r plm) \
    && (id -u plm >/dev/null 2>&1 || useradd -r -m -g plm plm)
ENV HOME=/home/plm TZ=Asia/Shanghai
RUN mkdir -p /app/data/uploads /app/data/master-data /app/data/mold-sales /app/data/mold-applications \
    && chown -R plm:plm /app \
    && install -d -m 0700 -o plm -g plm /home/plm/.ssh \
    && python3 -c "from zoneinfo import ZoneInfo; ZoneInfo('Asia/Shanghai')"
USER plm
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM runtime-base AS verified-runtime
ARG JAR_FILE=target/*.jar
COPY --chown=plm:plm ${JAR_FILE} app.jar

FROM runtime-base AS runtime
COPY --from=build --chown=plm:plm /build/target/*.jar app.jar
