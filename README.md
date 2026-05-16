# deteccao-fraude-java

Implementação Java 25 para a Rinha de Backend 2026 (detecção de fraude por busca vetorial). Foco em latência consistente, footprint pequeno e zero alocação no caminho quente.

## Topologia

```
client → nginx (porta 9999) → UDS → api1 / api2 (loop NIO + IVF int8)
```

- Load balancer: nginx 1.27 com upstreams via Unix Domain Socket.
- 2 instâncias da API, single-threaded (1 event loop NIO por instância).
- Sem banco de dados — o índice IVF quantizado em int8 fica em disco mmap.

Limites do `docker-compose.yml` (soma = 1.00 CPU / 340 MB):

| serviço | CPU | mem |
|---|---|---|
| api1 | 0.45 | 150 MB |
| api2 | 0.45 | 150 MB |
| nginx | 0.10 | 40 MB |

## Decisões principais

### Servidor HTTP
- NIO próprio (`ServerSocketChannel` + `Selector`) com pipeline single-thread, keep-alive, parser HTTP minimal (apenas o que o teste do k6 produz).
- Cada conexão tem `JsonRequestParser`, `MutableTransactionRequest`, `float[]` de query e `SearchScratch` próprios — zero contenção, zero alocação por requisição depois do `accept`.
- 6 respostas possíveis (`fraud_score` em incrementos de 0.2) são pré-encodadas como `byte[]` HTTP completos.

### Vetorização
- 14 dimensões conforme `REGRAS_DE_DETECCAO.md`, padded a 16 para alinhamento SIMD.
- Parser JSON manual sem alocação de `String` — chaves comparadas por hash FNV-1a 32-bit, `merchant.id` comparado por bytes contra `known_merchants`, MCC parseado para `int` e lido em tabela aberta `int → float`.
- Multiplicações por inverso (`1/max_amount` etc.) pré-calculadas em `float`.

### Warmup e gating do /ready
- O endpoint `GET /ready` retorna `HTTP 503 not-ready` enquanto o warmup não completa.
- No startup, após carregar o índice, executamos `Warmup.run(engine, 4000)` que faz 4000 chamadas internas a `engine.evaluate` com payloads sintéticos (legit, fraud, com last_transaction). Isso:
  - força o JIT a promover o hot path para C2;
  - inicializa o MethodHandle dinâmico de `QuantDistanceKernel` (Vector API);
  - garante que as classes lazy-loaded estejam quentes.
- Só após o warmup (~3 s no nosso hardware de bench) o servidor marca `ready = true` e o `/ready` passa a responder 200.
- Tempo do warmup pode ser ajustado via `-Drinha.warmup.iterations=N` (default 4000).

### Busca vetorial — IVF int8
- 256 clusters via k-means simples sobre amostra de 32k vetores (8 iterações).
- Vetores quantizados em **int8**: footprint cai de 192 MB (float32 × 16 dims) para **48 MB** (int8 × 16 stride). Cabe inteiro em L3 cache moderno.
- Sentinela `-1.0` (last_transaction null) mapeada para `-128`, mantém separação no espaço int8.
- Distância euclidiana² calculada com `jdk.incubator.vector` (Vector API, AVX2 em Haswell/Mac Mini Late 2014). Fallback escalar com loop unrolled caso o módulo não esteja disponível.
- mmap pré-aquecido (touch de uma palavra por página de 4 KiB) no startup para evitar page faults durante o teste.
- `nprobe = 4` (parametrizável via `RINHA_IVF_PROBES`).

### GC e JVM
- `-XX:+UseSerialGC -Xms48m -Xmx96m -Xss256k -XX:+AlwaysPreTouch`.
- Runtime customizado via `jlink` (apenas `java.base`, `java.logging`, `jdk.incubator.vector`, `jdk.unsupported`). Imagem final em `debian:12-slim`.
- Índice IVF construído durante o `docker build` numa stage dedicada e copiado para a imagem final — runtime não precisa ler `references.json.gz`.

## Estrutura

```
src/main/java/br/com/rinha/fraude/
├── FraudDetectionApplication.java   # bootstrap
├── HttpServerLoop.java              # servidor NIO + parser HTTP
├── AppConfig.java                   # env vars
├── JsonRequestParser.java           # parser zero-alloc
├── MutableTransactionRequest.java   # DTO mutável (offsets em buffer)
├── Vectorizer.java                  # 14 dims + normalização
├── DetectionEngine.java             # cola Vectorizer + IvfIndex
├── IvfIndex.java                    # busca IVF int8 (mmap)
├── IvfIndexBuilder.java             # k-means + dump binário
├── Quantization.java                # float[14] -> int8[16]
├── QuantDistanceKernel.java         # dispatcher (SIMD ou escalar)
├── SimdQuantDistanceKernel.java     # ByteVector / ShortVector / IntVector
├── DistanceKernel.java              # versão float (usada no k-means)
├── SimdDistanceKernel.java          # versão float SIMD
├── SearchScratch.java               # top-K + buffers reusáveis
└── TimeUtil.java                    # parse de timestamp ISO sem alocação
```

## Build local

```bash
mvn -q -DskipTests package
java --enable-native-access=ALL-UNNAMED \
     --add-modules jdk.incubator.vector \
     -jar target/deteccao-fraude-java-1.0.0-SNAPSHOT.jar build-index
java --enable-native-access=ALL-UNNAMED \
     --add-modules jdk.incubator.vector \
     -jar target/deteccao-fraude-java-1.0.0-SNAPSHOT.jar
```

Variáveis principais:

| variável | default | função |
|---|---|---|
| `PORT` | `8081` | porta TCP (ignorada se `RINHA_UDS_PATH` definida) |
| `RINHA_UDS_PATH` | _vazio_ | se definida, abre Unix Domain Socket em vez de TCP |
| `RINHA_RESOURCES_DIR` | `../rinha-de-backend-2026/resources` | onde estão `references.json.gz`, `normalization.json`, `mcc_risk.json` |
| `RINHA_INDEX_DIR` | `./data/index` | onde gravar/ler o índice IVF |
| `RINHA_IVF_CLUSTERS` | `256` | número de centroides (k-means) |
| `RINHA_IVF_PROBES` | `4` | quantos buckets visitar por query |
| `RINHA_IVF_SAMPLE_SIZE` | `16384` | tamanho da amostra para k-means |
| `RINHA_KMEANS_ITERATIONS` | `6` | iterações de k-means |
| `RINHA_BUILD_ON_STARTUP` | `true` | se construir o índice no boot quando não existe |

## Subir o docker-compose

```bash
cd deteccao-fraude-java
docker compose up --build
```

O `additional_contexts` do build aponta para `../rinha-de-backend-2026/resources`, então o repositório irmão precisa estar nesse caminho relativo.

## Observações

- O p99 alvo (≤ 1 ms) só é atingível com hardware e cache de página quentes. Faça warmup local antes de medir.
- Os campos `merchantMcc` e `mccRisk` usam `int` puro; um MCC com prefixo zero (impossível na tabela atual) seria distorcido.
- `BUCKET_CHUNK = 1024` em `SearchScratch` é um trade-off entre cache locality e overhead de chamada `MemorySegment.copy`.
