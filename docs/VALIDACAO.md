# Validação do projeto entregue

Testes executados em **4 de outubro de 2026**. Os resultados de compilação e integração MQTT local são descritos nas duas primeiras seções. A execução Docker no Windows foi confirmada depois, pelas saídas de PowerShell fornecidas pelo usuário; seus trechos estão em `docs/testes/docker-windows.txt`.

## Compilação e testes unitários

Ambiente: OpenJDK 17.0.20 e Maven 3.9.11. Resultado do empacotamento: **BUILD SUCCESS**. O JAR contém a aplicação e as dependências e também foi executado por `java -jar target/detran.jar --help`.

| Classe | Testes | Falhas | Erros | Ignorados |
|---|---:|---:|---:|---:|
| `ValidacaoTest` | 7 | 0 | 0 | 0 |
| `StoreTest` | 5 | 0 | 0 | 0 |
| Total | **12** | **0** | **0** | **0** |

Cobertura dos cenários: CPF e placa, UFs incluindo DF, valores monetários e arredondamento do IPVA, ano e pontuação, persistência após reinício, resposta de um UUID repetido, rejeição de reutilização de identificador, rollback de alteração parcial, proteção de cópias consultadas e gravações concorrentes sem perda de dados.

## Integração com MQTT real

Foi executado `scripts/testar_integracao.py` com um broker **Eclipse Mosquitto 2.0.22 real** e quatro processos Java separados. A aplicação usa Eclipse Paho 1.2.5, MQTT 3.1.1 e QoS 1. Os dados de cada processo ficaram em diretórios separados.

| Cenário | Resultado |
|---|---|
| As dez funcionalidades do enunciado | Passou: `SUCESSO: 10/10` |
| CPF/placa duplicados, CPF inválido e pontuação inválida | Rejeitados corretamente |
| Reenvio da mesma multa com mesmo UUID | Uma multa armazenada; mesma resposta |
| Dois clientes transferindo com o mesmo UUID | Uma transferência; resultados iguais |
| Multa antes e depois da transferência | Responsável histórico preservado |
| Consultas nacionais de SE e BA, em dois anos | Filtros e agregação corretos |
| IPVA de veículo de R$ 50.000,00 | R$ 1.000,00 |
| Top 5 nacional | Pontos, quantidade de multas, ordem e desempate conferidos |
| Reinício do broker com os serviços ativos | Reconexão automática e auditoria passaram |
| Reinício completo dos serviços e broker | Dados e ranking preservados |
| DENATRAN reiniciado com sua projeção apagada no teste isolado | Visão reconstruída pelos snapshots retidos |
| Demonstração executada uma segunda vez | Dados anteriores preservados |
| Dois clientes simultâneos, AC e DF | Cadastros concluídos sem perda de registros |

A primeira demonstração gerou 6 condutores, 6 veículos e 8 multas. A segunda ampliou os totais para 12, 12 e 16. Após os dois cadastros simultâneos de AC e DF, a auditoria final confirmou **14 condutores, 12 veículos e 16 multas**, em AC, BA, DF e SE.

Os registros de teste não são incluídos como base pré-carregada. Na primeira execução do Compose, o projeto inicia vazio, e `verificar` cria seus próprios dados fictícios.

## Docker e Windows

O `compose.yaml` foi analisado como YAML: oito serviços, cinco volumes separados, dependências do broker, comandos e imagem comum conferidos. O `Dockerfile` compila a aplicação e executa os testes antes de gerar a imagem final.

A execução Docker foi confirmada em **4 de outubro de 2026, no Windows PowerShell**, pelas saídas fornecidas pelo usuário.

| Verificação | Evidência recebida |
|---|---|
| `docker compose build cadastro` | Build concluído; imagem `detran-mqtt:1.0` construída |
| `docker compose up -d` | Mosquitto saudável; cadastro, veículos, multas e DENATRAN iniciados |
| `docker compose run --rm verificar` | `SUCESSO: 10/10 funcionalidades verificadas com comunicação MQTT real.` |
| Menu: opção 2, placa `QGV3U65` | Valor 50000.00, alíquota 0.02, IPVA 1000.00 |
| Menu: opção 7, placa `QGV3U65`, ano 2026 | Multa de 7 pontos do antigo proprietário, em SE; multa de 6 pontos do novo proprietário, em BA |
| Menu: opção 10 | Top 5 decrescente: 11, 10, 4, 3 e 2 pontos |

A consulta da multa manteve CPF e nome de cada responsável. Ela também confirmou a abrangência nacional, pois o cliente de SE recebeu a multa lançada em BA.

O ranking manual apresentou:

| Condutor | UF | Pontos | Multas |
|---|---|---:|---:|
| Condutor Demo 2 | BA | 11 | 2 |
| Condutor Demo 1 | SE | 10 | 2 |
| Condutor Demo 3 | SE | 4 | 1 |
| Condutor Demo 4 | BA | 3 | 1 |
| Condutor Demo 5 | SE | 2 | 1 |

O ranking inclui todo o histórico: os 10 pontos do Demo 1 somam a multa de 7 pontos de 2026 e a de 3 pontos de 2025.

Os testes de reinício, reconstrução da projeção e clientes simultâneos foram executados na integração MQTT local descrita acima. As saídas Docker recebidas confirmam build, inicialização, teste das dez funções e as três consultas manuais. O atalho opcional `executar.ps1` não foi utilizado na execução mostrada; os comandos Docker foram executados diretamente no PowerShell.

Trechos efetivamente recebidos, sem representar uma transcrição completa do terminal: `docs/testes/docker-windows.txt`.
