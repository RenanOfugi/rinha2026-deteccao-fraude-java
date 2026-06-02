# syntax=docker/dockerfile:1.7
########## Stage 1: build do jar ##########
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN mvn -q -DskipTests package

########## Stage 2: pré-construção do índice IVF ##########
FROM eclipse-temurin:25-jdk AS index-stage
WORKDIR /work
COPY --from=build /workspace/target/deteccao-fraude-java-1.0.0-SNAPSHOT.jar /work/app.jar

COPY src/main/resources  /opt/rinha/resources
ENV RINHA_RESOURCES_DIR=/opt/rinha/resources
ENV RINHA_INDEX_DIR=/opt/rinha/index
ENV RINHA_IVF_CLUSTERS=2048
ENV RINHA_IVF_SAMPLE_SIZE=16384
ENV RINHA_KMEANS_ITERATIONS=15
RUN java \
    --enable-native-access=ALL-UNNAMED \
    --add-modules jdk.incubator.vector \
    -Xms256m -Xmx1g \
    -jar /work/app.jar build-index && \
    ls -la /opt/rinha/index

########## Stage 3: jlink — runtime mínimo ##########
FROM eclipse-temurin:25-jdk AS jlink-stage
RUN $JAVA_HOME/bin/jlink \
        --add-modules java.base,java.logging,jdk.incubator.vector,jdk.unsupported \
        --strip-debug \
        --no-man-pages \
        --no-header-files \
        --compress=zip-9 \
        --output /opt/jre-min

########## Stage 4: imagem final ##########
FROM debian:12-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=jlink-stage /opt/jre-min /opt/jre
COPY --from=build /workspace/target/deteccao-fraude-java-1.0.0-SNAPSHOT.jar /app/app.jar
COPY --from=index-stage /opt/rinha/index /opt/rinha/index
COPY --from=index-stage /opt/rinha/resources/normalization.json /opt/rinha/resources/normalization.json
COPY --from=index-stage /opt/rinha/resources/mcc_risk.json /opt/rinha/resources/mcc_risk.json
ENV PATH="/opt/jre/bin:${PATH}"
ENV RINHA_INDEX_DIR=/opt/rinha/index
ENV RINHA_BUILD_ON_STARTUP=false
ENV RINHA_RESOURCES_DIR=/opt/rinha/resources
WORKDIR /app
ENTRYPOINT [ \
    "java", \
    "--enable-native-access=ALL-UNNAMED", \
    "--add-modules", "jdk.incubator.vector", \
    "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED", \
    "--add-opens", "java.base/java.io=ALL-UNNAMED", \
    "-XX:+UnlockExperimentalVMOptions", \
    "-XX:+UseShenandoahGC", \
    "-XX:ShenandoahGCHeuristics=compact", \
    "-Xms80m", \
    "-Xmx80m", \
    "-Xss256k", \
    "-XX:ReservedCodeCacheSize=24m", \
    "-XX:InitialCodeCacheSize=16m", \
    "-XX:+AlwaysPreTouch", \
    "-XX:-UsePerfData", \
    "-XX:+DisableExplicitGC", \
    "-Xlog:gc:stderr:time,uptime,level,tags", \
    "-Drinha.warmup.iterations=10000", \
    "-Dfile.encoding=UTF-8", \
    "-jar", "/app/app.jar" \
]
