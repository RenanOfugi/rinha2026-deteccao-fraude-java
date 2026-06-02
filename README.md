# Fraud Detection API (Java)

Submissão da Rinha de Backend 2026 para detecção de fraude por busca vetorial.

Este repositório é uma implementação de alta performance em Java 25. O serviço utiliza a **Vector API** para aceleração SIMD e um índice **IVF (Inverted File Index)** quantizado para realizar a detecção de fraude com latência sub-milissegundo e baixo footprint de memória.

## Arquitetura do projeto

A topologia em runtime consiste em:

- `lb`: Load balancer L4 em Rust que aceita o TCP e **passa o file descriptor**
  da conexão para uma das APIs via `sendmsg`/`SCM_RIGHTS`. Sai do data path
  após o handoff — não copia bytes por requisição.
- `api1` e `api2`: Instâncias da API Java operando em loop NIO single-thread.
  Recebem o fd cru pelo control socket e leem/escrevem direto no socket do cliente.
- `index`: Índice vetorial binário (IVF int8) carregado via `mmap`.

Topologia:
```text
client → lb (TCP :9999) → [SCM_RIGHTS: passa o fd] → api1 / api2 (NIO loop + IVF SIMD)
```

O LB sai do caminho dos dados: depois de entregar o fd, o cliente fala direto
com a API. Isso elimina o proxy de bytes do hot path e derruba o p99.

## Classificação

O payload é convertido em um vetor de 14 dimensões. A API realiza uma busca vetorial aproximada para calcular o score:

- **Vetorização**: Normalização de valores, horários e MCCs para um espaço vetorial.
- **Quantização**: Vetores são convertidos para `int8` para caber inteiramente em cache.
- **Busca**: Localiza os vizinhos mais próximos usando distância Euclidiana quadrada acelerada por hardware (AVX2/SIMD).

## Endpoints

- `GET /ready`: Healthcheck (retorna 200 apenas após o warmup do JIT).
- `POST /fraud-score`: Classificação da transação.

Resposta de exemplo:
```json
{"approved":true,"fraud_score":0.0}
```

## Implementação

- **Hot Path Zero-Allocation**: Sem alocações de objetos ou strings no caminho crítico da requisição.
- **Vector API (SIMD)**: Uso de instruções de hardware para processamento paralelo de distâncias.
- **IVF int8**: Redução de 75% no uso de memória do índice através de quantização escalar.
- **NIO Customizado**: Servidor HTTP minimalista e parser JSON manual de alta eficiência.
- **Warmup Ativo**: Execução de requisições sintéticas no startup para aquecimento do JIT e cache de páginas.
- **FD Passing (SCM_RIGHTS)**: O LB passa o file descriptor da conexão para a API e sai do data path, eliminando o proxy de bytes do hot path.

## Estrutura

- `src/main/java/`: Código fonte da API, parser, vetorizador e kernels SIMD.
- `src/main/lb-rust/`: Load balancer L4 em Rust (FD passing via SCM_RIGHTS).
- `data/index/`: Localização do índice binário gerado.
- `docker-compose.yml`: Topologia oficial da submissão.
- `Dockerfile` / `Dockerfile.lb`: Build da API Java e do LB Rust.

## Configuração

Váriáveis de ambiente principais:

- `RINHA_FD_SOCKET`: Caminho do control socket. Se definido, ativa o modo FD passing (a API recebe fds do LB em vez de aceitar TCP/UDS).
- `PORT` / `RINHA_UDS_PATH`: Fallback quando `RINHA_FD_SOCKET` não está definido (accept loop tradicional via TCP ou Unix Socket).
- `RINHA_IVF_PROBES`: Quantidade de clusters visitados (ajusta precisão vs performance).
- `RINHA_BUILD_ON_STARTUP`: Reconstrói o índice no boot se não for encontrado.

## Comandos

Build e execução via Docker:
```bash
docker compose up --build
```

Build manual (requer JDK 25):
```bash
mvn clean package
java --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector -jar target/deteccao-fraude-java-1.0.0-SNAPSHOT.jar
```
