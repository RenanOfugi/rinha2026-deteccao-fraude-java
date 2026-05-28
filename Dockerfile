# syntax=docker/dockerfile:1.7
#
# Build stages:
#   1. build       — compila o JAR
#   2. index-stage — gera os 4 índices IVF particionados (offline)
#   3. aot-record  — roda o app em modo AOT_TRAINING para gravar o profile JIT
#   4. aot-create  — gera o AOT cache a partir do profile
#   5. runtime     — imagem final com AOT cache + flags agressivas + jemalloc
#
# As flags do JVM_HEAP devem ser BYTE-IDÊNTICAS entre record/create/runtime —
# o AOT cache é keyed nelas. Mexer aqui requer rebuildar tudo.
ARG JVM_HEAP="-Xms80m -Xmx80m -Xss256k -XX:MaxMetaspaceSize=64m -XX:ReservedCodeCacheSize=48m -XX:ActiveProcessorCount=1 -XX:CICompilerCount=2 -XX:+UseFMA -XX:MaxInlineLevel=20 -XX:MaxInlineSize=500 -XX:+AlwaysPreTouch -XX:+UseTransparentHugePages -XX:+UnlockDiagnosticVMOptions -XX:GuaranteedSafepointInterval=0 -XX:-UsePerfData -XX:-BackgroundCompilation -XX:+DisableExplicitGC"

########## Stage 1: build do jar ##########
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN mvn -q -DskipTests package

########## Stage 2: pré-construção do índice IVF particionado ##########
FROM eclipse-temurin:25-jdk AS index-stage
WORKDIR /work
COPY --from=build /workspace/target/deteccao-fraude-java-1.0.0-SNAPSHOT.jar /work/app.jar
COPY src/main/resources /opt/rinha/resources
ENV RINHA_RESOURCES_DIR=/opt/rinha/resources \
    RINHA_INDEX_DIR=/opt/rinha/index \
    RINHA_IVF_CLUSTERS=2048 \
    RINHA_IVF_SAMPLE_SIZE=16384 \
    RINHA_KMEANS_ITERATIONS=15
RUN java \
    --enable-native-access=ALL-UNNAMED \
    --add-modules jdk.incubator.vector \
    -Xms256m -Xmx1g \
    -jar /work/app.jar build-index \
 && ls -la /opt/rinha/index

########## Stage 3: AOT record — grava profile do hot path ##########
FROM eclipse-temurin:25-jdk AS aot-record
WORKDIR /app
COPY --from=build /workspace/target/deteccao-fraude-java-1.0.0-SNAPSHOT.jar /app/app.jar
COPY --from=index-stage /opt/rinha/index /opt/rinha/index
COPY --from=index-stage /opt/rinha/resources/normalization.json /opt/rinha/resources/normalization.json
COPY --from=index-stage /opt/rinha/resources/mcc_risk.json /opt/rinha/resources/mcc_risk.json
ARG JVM_HEAP
# Roda o app com AOT_TRAINING=1: depois do warmup, sai limpo com System.exit(0).
# O JIT registra durante o warmup os métodos hot (engine.evaluate, parser, etc).
# Path /opt/rinha precisa ser idêntico ao runtime — AOT amarra paths no cache.
RUN AOT_TRAINING=1 \
    RINHA_INDEX_DIR=/opt/rinha/index \
    RINHA_RESOURCES_DIR=/opt/rinha/resources \
    RINHA_BUILD_ON_STARTUP=false \
    RINHA_IVF_MAX_PROBES=256 \
    RINHA_IVF_PRUNE_MARGIN=1.0 \
    java $JVM_HEAP -XX:+UseSerialGC \
        --add-modules jdk.incubator.vector \
        --enable-native-access=ALL-UNNAMED \
        -XX:AOTMode=record \
        -XX:AOTConfiguration=/tmp/app.aotconf \
        -Drinha.warmup.iterations=10000 \
        -jar /app/app.jar

########## Stage 4: AOT create — gera o cache compilado ##########
FROM eclipse-temurin:25-jdk AS aot-create
WORKDIR /app
COPY --from=build /workspace/target/deteccao-fraude-java-1.0.0-SNAPSHOT.jar /app/app.jar
COPY --from=aot-record /tmp/app.aotconf /tmp/app.aotconf
ARG JVM_HEAP
RUN java $JVM_HEAP -XX:+UseSerialGC \
    --add-modules jdk.incubator.vector \
    --enable-native-access=ALL-UNNAMED \
    -XX:AOTMode=create \
    -XX:AOTConfiguration=/tmp/app.aotconf \
    -XX:AOTCache=/app/app.aot \
    -jar /app/app.jar
# Confirma que o cache foi criado.
RUN ls -la /app/app.aot

########## Stage 5: imagem final ##########
# AOT requer JDK completo (não jlink). eclipse-temurin:25-jdk.
FROM eclipse-temurin:25-jdk
RUN apt-get update \
 && apt-get install -y --no-install-recommends libjemalloc2 ca-certificates \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/deteccao-fraude-java-1.0.0-SNAPSHOT.jar /app/app.jar
COPY --from=index-stage /opt/rinha/index /opt/rinha/index
COPY --from=index-stage /opt/rinha/resources/normalization.json /opt/rinha/resources/normalization.json
COPY --from=index-stage /opt/rinha/resources/mcc_risk.json /opt/rinha/resources/mcc_risk.json
COPY --from=aot-create /app/app.aot /app/app.aot

ENV RINHA_INDEX_DIR=/opt/rinha/index \
    RINHA_RESOURCES_DIR=/opt/rinha/resources \
    RINHA_BUILD_ON_STARTUP=false \
    LD_PRELOAD=/usr/lib/x86_64-linux-gnu/libjemalloc.so.2 \
    MALLOC_ARENA_MAX=1

ARG JVM_HEAP
ENV JVM_HEAP="${JVM_HEAP}"

# Serial GC: footprint mínimo, pause STW curta com heap pequena.
# AOTCache: hot path já compilado, zero JIT warmup em runtime.
# Demais flags: inline agressivo, sem safepoint periódico, sem background compile,
# TransparentHugePages, FMA. Conjunto validado por jvmoonshot-xxvi (p99 1.29ms).
ENTRYPOINT ["sh", "-c", "exec java $JVM_HEAP -XX:+UseSerialGC \
    --add-modules jdk.incubator.vector \
    --enable-native-access=ALL-UNNAMED \
    -XX:AOTCache=/app/app.aot \
    -Drinha.warmup.iterations=4000 \
    -Dfile.encoding=UTF-8 \
    -jar /app/app.jar"]
