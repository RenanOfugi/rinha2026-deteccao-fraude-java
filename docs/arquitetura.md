# Arquitetura

## Restrições do desafio

- 1 CPU / 350 MB para a soma de todos os serviços (LB + APIs).
- Bridge network, sem `host` nem `privileged`.
- Pelo menos 2 instâncias da API atrás de um load balancer.

## Topologia atual

```
client → nginx (TCP 9999) → UDS /sockets/{api1,api2}.sock → api{1,2} (NIO loop)
```

Limites do compose: api1=0.45/150MB, api2=0.45/150MB, nginx=0.10/40MB. Soma 1.00 CPU / 340 MB.

## Caminho quente da requisição

```
nginx ─UDS─► NIO Selector ─► HttpServerLoop$Connection
                                ├─ parser HTTP (linha-por-linha)
                                ├─ JsonRequestParser   (zero String, hash de chaves)
                                ├─ Vectorizer          (14 dims, multiplicações por inverso)
                                ├─ Quantization        (float[14] → int8[16])
                                ├─ IvfIndex.search     (256 centroides + 4 buckets × ~12k vetores)
                                │   └─ SimdQuantDistanceKernel (AVX2)
                                └─ resposta pré-encodada (1 de 6 bytes[])
```

## Por que IVF int8?

Para 3 M vetores × 14 floats (16 com padding) = 192 MB em float32. Esse footprint **não cabe em RAM** dado o orçamento de 350 MB compartilhado. Quantizar para int8 reduz para **48 MB** — cabe em mmap e geralmente fica residente no page cache durante o teste.

A quantização preserva a ordenação por proximidade (KNN só usa relações relativas), e o sentinela `-1` para `last_transaction null` é mapeado para `-128`, mantendo separação grande de qualquer valor normalizado em `[0, 127]`.

## Por que NIO próprio em vez de Netty ou `HttpServer` do JDK

- `com.sun.net.httpserver.HttpServer` aloca `HttpExchange` por requisição e tem thread pool bloqueante — overhead de ~100 µs por request a 400 RPS.
- Netty é maduro mas adiciona ~3 MB de dependências e mais 1 MB de imagem, e a curva de tuning é própria.
- Servidor NIO próprio cabe em ~300 linhas, single-threaded por instância (alinha com o budget de ~0.45 vCPU), e dá controle total sobre buffers e parsing.

O parser HTTP cobre só o que o teste do k6 produz (Content-Length obrigatório, sem chunked/trailers). Se a Rinha vier a usar variantes, esse é o ponto a estender.

## Por que Unix Domain Socket entre nginx e API

UDS evita o overhead do stack TCP (~20-40 µs por hop em loopback). O preço é um volume compartilhado no compose; o ganho é maior estabilidade de p99 sob carga.

## GC

- `-XX:+UseSerialGC` com heap fixo `48m..96m` é suficiente — a aplicação não aloca durante o hot path (parser opera em buffer reusável, query e scratch são por-conexão).
- `-XX:+AlwaysPreTouch` evita page faults nas primeiras requisições.
- Considerar `-XX:+UseEpsilonGC` em produção do teste se o profiling confirmar churn de heap < 1 MB durante a janela de 120 s.

## Pre-aquecimento e gating do /ready

O endpoint `GET /ready` retorna `503 Service Unavailable` até o warmup completar. Sequência exata no startup:

1. Carrega `normalization.json`, `mcc_risk.json` e o índice IVF mmap.
2. `IvfIndex.prefetch` toca uma palavra por página de 4 KiB de `vectors.bin` e `labels.bin` para forçar resident pages.
3. Abre o socket NIO em background (já aceitando conexões).
4. `Warmup.run(engine, 4000)` chama `engine.evaluate` 4000 vezes com payloads sintéticos.
5. Só então `loop.markReady()` é invocado e `/ready` começa a responder 200.

Sem isso, o k6 começaria a 1 RPS já contra um JIT frio. As primeiras centenas de requests rodariam interpretadas e contaminariam o p99 inteiro do teste — a janela é de 120 s, ou seja, o JIT mal teria tempo de se estabilizar antes do pico.

Não há AppCDS — o startup do servidor é dominado pelo prefetch + warmup (~3-4 s), não pelo carregamento de classes.

## Vector API

- Usada via reflexão dinâmica (MethodHandle) em `QuantDistanceKernel.resolve()`, com fallback escalar caso `--add-modules jdk.incubator.vector` não esteja presente.
- O kernel SIMD opera com `ByteVector.SPECIES_128` (16 lanes) → promove para `ShortVector.SPECIES_256` (16 lanes) → split para `IntVector.SPECIES_256` para evitar overflow no `mul`.
- AVX2 cobre Haswell em diante, incluindo Mac Mini Late 2014 (alvo da Rinha).

## O que ainda não foi feito (oportunidades)

1. **Layout SoA (Struct of Arrays) por bucket**: armazenar `N` int8 por dimensão em vez de 14 dims por vetor. Permite processar 16 ou 32 vetores em paralelo numa única `ByteVector`. Estimativa de speedup: 3-5× no kernel.
2. **Kernel "batch" de centroides**: usar `gather`/`scatter` para varrer 8 centroides simultaneamente. Hoje processo 1 por chamada.
3. **AppCDS / dynamic CDS**: reduz startup em 200-400 ms. Não afeta p99 do teste, mas reduz tempo de boot do compose.
4. **GraalVM Native Image**: footprint cai para 30-50 MB, startup instantâneo, sem warmup do JIT. Complicação: Vector API ainda não é first-class no Native Image.
5. **HNSW** em vez de IVF: latência sublinear (`O(log N)`) com melhor recall. Custo: mais memória e código.
6. **Pre-computar `||r||²` por vetor**: troca o kernel `Σ(qᵢ - rᵢ)²` por `||q||² + ||r||² - 2·q·r`. Casa melhor com SIMD em algumas arquiteturas.

## Recall observado

Com 256 clusters / `nprobe=4` o KNN aproximado tipicamente atinge 95-98% de recall sobre KNN exato (medido em vetores 14-D do mesmo dataset). Isso impacta o `detection_score`:

- Cada FN extra pesa 3 na fórmula de erro ponderado.
- Aumentar `nprobe` melhora recall mas aumenta p99 linearmente.
- O balanço atual (4 probes) foi escolhido com base em benchmark local; em hardware diferente vale recalibrar.
