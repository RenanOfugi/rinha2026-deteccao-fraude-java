#!/bin/bash
# Teste de integração FD passing: LB Rust + 2 APIs + curl. Flags literais.
set -e
cd "$(dirname "$0")"
JAR="$PWD/target/deteccao-fraude-java-1.0.0-SNAPSHOT.jar"
RES="$PWD/src/main/resources"
LB="$PWD/src/main/lb-rust/target/release/rinha-lb"
IDX=/tmp/ivf-fd

pkill -f "deteccao-fraude.*jar" 2>/dev/null || true
pkill -f rinha-lb 2>/dev/null || true
sleep 1
rm -f /tmp/api1-fd.sock /tmp/api2-fd.sock /tmp/fdapi1.log /tmp/fdapi2.log /tmp/fdlb.log

run_api() {
  RINHA_FD_SOCKET="$1" RINHA_INDEX_DIR="$IDX" RINHA_RESOURCES_DIR="$RES" \
  RINHA_BUILD_ON_STARTUP=false RINHA_IVF_MAX_PROBES=256 RINHA_IVF_PRUNE_MARGIN=1.0 RINHA_HTTP_WORKERS=1 \
  setsid java --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector \
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED \
    -Xmx256m -Drinha.warmup.iterations=500 -jar "$JAR" > "$2" 2>&1 < /dev/null &
  disown
}

run_api /tmp/api1-fd.sock /tmp/fdapi1.log
run_api /tmp/api2-fd.sock /tmp/fdapi2.log

echo "aguardando control sockets..."
for i in $(seq 1 30); do
  [ -S /tmp/api1-fd.sock ] && [ -S /tmp/api2-fd.sock ] && break
  sleep 1
done
if [ ! -S /tmp/api1-fd.sock ]; then echo "FALHA: control socket nao criado"; tail -5 /tmp/fdapi1.log | grep -v WARNING; exit 1; fi
echo "control sockets OK"

# sobe o LB
FD_UPSTREAMS=/tmp/api1-fd.sock,/tmp/api2-fd.sock LISTEN_ADDR=0.0.0.0:9990 \
  setsid "$LB" > /tmp/fdlb.log 2>&1 < /dev/null &
disown
sleep 2
echo "LB log:"; cat /tmp/fdlb.log

echo "=== curl /ready ==="
curl -s --max-time 5 http://localhost:9990/ready; echo
echo "=== curl /fraud-score ==="
curl -s --max-time 5 -X POST http://localhost:9990/fraud-score \
  -H "Content-Type: application/json" \
  -d '{"id":"t","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},"customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-016"]},"merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},"terminal":{"is_online":false,"card_present":true,"km_from_home":29.23},"last_transaction":null}'; echo
echo "=== fim ==="
