# Fraud Detection API (Java)

Submissão da Rinha de Backend 2026 para detecção de fraude por busca vetorial.

Este repositório é uma implementação de alta performance em Java 25. O serviço utiliza a **Vector API** para aceleração SIMD e um índice **IVF (Inverted File Index)** quantizado para realizar a detecção de fraude com latência sub-milissegundo e baixo footprint de memória.

## Arquitetura do projeto

A topologia em runtime consiste em:

- `nginx`: Load balancer distribuindo tráfego via Unix Domain Sockets (UDS).
- `api1` e `api2`: Instâncias da API Java operando em loop NIO single-thread.
- `index`: Índice vetorial binário (IVF int8) carregado via `mmap`.

Topologia:
```text
client → nginx (porta 9999) → UDS → api1 / api2 (NIO loop + IVF SIMD)
```

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
- **Warmup Ativo**: Execução de 4000 requisições sintéticas no startup para aquecimento do JIT e cache de páginas.
- **Unix Domain Sockets**: Comunicação entre LB e API sem o overhead do stack TCP local.

## Estrutura

- `src/main/java/`: Código fonte da API, parser, vetorizador e kernels SIMD.
- `data/index/`: Localização do índice binário gerado.
- `docker-compose.yml`: Topologia oficial da submissão.
- `nginx.conf`: Configuração do load balancer.

## Configuração

Váriáveis de ambiente principais:

- `PORT`: Porta TCP (padrão 8081).
- `RINHA_UDS_PATH`: Se definido, ativa comunicação via Unix Socket.
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
