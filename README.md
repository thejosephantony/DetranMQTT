# DETRAN MQTT

Implementação da Atividade 01 – Pub/Sub de Sistemas Distribuídos (UFS): cadastro de condutores, veículos/IPVA, transferência, multas e consultas nacionais do DENATRAN, com comunicação por MQTT e execução automatizada por Docker Compose.

## Execução confirmada no Windows

a execução via Docker Compose foi confirmada pelas saídas do PowerShell: imagem `detran-mqtt:1.0` construída, broker saudável, quatro microsserviços iniciados e teste automático com **SUCESSO: 10/10 funcionalidades verificadas com comunicação MQTT real**.

Também foram conferidas pelo menu:

| Consulta | Resultado observado |
|---|---|
| IPVA da placa `QGV3U65`, com valor de R$ 50.000,00 | R$ 1.000,00, correspondente a 2% |
| Multas de `QGV3U65` em 2026 | Duas multas; a anterior com CPF `53514238804` e a posterior com CPF `64007556598` |
| Abrangência nacional | Cliente de SE consultou multas de SE e BA |
| Top 5 nacional | Demo 2: 11; Demo 1: 10; Demo 3: 4; Demo 4: 3; Demo 5: 2 pontos |

Os valores acima pertencem à demonstração fictícia executada. Cada nova execução de `verificar` gera outros CPFs e placas. Os trechos das saídas recebidas estão em `docs/testes/docker-windows.txt`; a descrição dos testes está em `docs/VALIDACAO.md`.


## Comece aqui no Windows

Extraia **o conteúdo do ZIP** para `C:\Users\Joseph\Downloads\DetranMQTT`. Nessa pasta devem aparecer `pom.xml`, `Dockerfile`, `compose.yaml` e `src`, sem outra pasta intermediária.

Abra o Docker Desktop e aguarde o mecanismo iniciar. No PowerShell:

```powershell
cd C:\Users\Joseph\Downloads\DetranMQTT
docker info
docker compose config --quiet
docker compose build cadastro
docker compose up -d
docker compose ps
docker compose run --rm verificar
```

`build cadastro` cria a imagem comum aos quatro serviços e ao cliente. A compilação também executa os testes JUnit. A primeira execução baixa imagens e dependências e precisa de internet.

O teste de integração cria dados fictícios em SE e BA e termina com:

```text
SUCESSO: 10/10 funcionalidades verificadas com comunicação MQTT real.
```

Abra o menu para usar as funções:

```powershell
docker compose run --rm cliente
```

O menu inicia como DETRAN de SE. A opção 11 troca a UF, e a opção 0 encerra o cliente. Para começar como outra UF:

```powershell
docker compose run --rm cliente menu BA
```

Depois do teste, use os CPFs e placas exibidos na linha `DADOS_DA_DEMONSTRACAO` para consultar os registros pelo menu. O teste pode ser repetido: cada execução acrescenta seis condutores, seis veículos e oito multas com identificadores novos.

## Instalações necessárias

Para executar por Docker, basta **Docker Desktop com Docker Compose**, em modo de contêineres Linux. Java, Maven, Mosquitto e bibliotecas são fornecidos pelas imagens; não é necessário instalar Maven globalmente no Windows.

Para editar no NetBeans, abra esta pasta em **Arquivo → Abrir projeto** como projeto Maven. O projeto usa Java 17; um JDK mais novo, como o JDK 25 já instalado, pode compilar para essa versão. As dependências ficam no `pom.xml`, sem adicionar JARs manualmente à IDE.

## As dez funcionalidades

| Nº | Função | Dados de entrada | Responsável |
|---|---|---|---|
| 1 | Emplacar veículo | Placa, modelo, valor, CPF; ano opcional | `veiculos` |
| 2 | Calcular IPVA de 2% | Placa | `veiculos` |
| 3 | Transferir proprietário | Placa e CPF do novo dono | `cadastro`, com atualização em `veiculos` por MQTT |
| 4 | Cadastrar condutor | CPF e nome | `cadastro` |
| 5 | Lançar multa | Ano, descrição, pontuação e placa | `multas` |
| 6 | Veículos emplacados em um ano | Ano | `denatran` |
| 7 | Multas de um veículo em um ano e dados do condutor | Placa e ano | `denatran` |
| 8 | Multas de um condutor em um ano | CPF e ano | `denatran` |
| 9 | Multas lançadas em um ano | Ano | `denatran` |
| 10 | Cinco condutores com mais pontos | Sem parâmetros | `denatran` |

Cada função tem uma opção no menu e um comando no cliente. A verificação automática exercita as dez.

## Stack e organização

| Componente | Tecnologia |
|---|---|
| Linguagem e runtime dos contêineres | Java 17 / Eclipse Temurin |
| Compilação e empacotamento | Maven 3.9.11 e Maven Shade |
| Cliente MQTT | Eclipse Paho 1.2.5, MQTT 3.1.1 |
| Broker | Eclipse Mosquitto 2 |
| JSON | Jackson 2.20.1 |
| Testes unitários | JUnit Jupiter 5.12.2 |
| Execução | Docker e Docker Compose |
| Persistência | JSON com gravação por arquivo temporário e substituição atômica, em volumes separados |

| Caminho | Conteúdo |
|---|---|
| `src/main/java/br/ufs/detran/Main.java` | Entrada do programa e ajuda |
| `Cliente.java`, `Menu.java`, `Demo.java` | Comandos, interface de terminal e verificação automática |
| `CadastroService.java` | Condutores e coordenação da transferência |
| `VeiculosService.java` | Emplacamento, propriedade e IPVA |
| `MultasService.java` | Multas e dados históricos do responsável |
| `DenatranService.java` | Projeções e consultas nacionais |
| `MqttBus.java`, `Servico.java` | Requisição/resposta, correlação, reconexão e publicação de estados |
| `Store.java`, `Json.java`, `Validacao.java` | Persistência, JSON e validações |
| `src/test/java/br/ufs/detran` | Testes JUnit |
| `config/mosquitto.conf` | Configuração do broker |
| `Dockerfile`, `compose.yaml` | Compilação e contêineres |
| `executar.ps1` | Atalhos opcionais para PowerShell |
| `scripts/testar_integracao.py` | Teste de reinício e recuperação com Mosquitto local |
| `dist/detran.jar` | JAR executável já compilado, com dependências |
| `docs/VALIDACAO.md` | Registro dos testes Java/MQTT e da execução Docker no Windows |

Os nomes de arquivos Java da tabela, exceto `Main.java`, também estão em `src/main/java/br/ufs/detran`.

## Como os microsserviços conversam

Há quatro processos independentes, cada um com seu próprio volume. Nenhum lê o arquivo de dados do outro. Consultas entre serviços e atualizações da visão nacional passam pelo Mosquitto.

1. O cliente representa um DETRAN identificado pela UF do comando.
2. `cadastro` valida o novo proprietário e solicita a transferência a `veiculos` por MQTT.
3. `veiculos` consulta `cadastro` antes de emplacar.
4. `multas` consulta o veículo e o condutor por MQTT, guardando CPF, nome e UF do responsável naquele momento.
5. Os três serviços de escrita publicam snapshots por UF. O `denatran` recebe esses estados e monta uma visão nacional própria.

Os serviços de escrita atendem todas as 27 UFs, incluindo DF. A UF identifica a origem do cadastro, emplacamento ou multa. CPF e placa são únicos nacionalmente. A UF do veículo continua sendo a de emplacamento após uma transferência de dono; transferência de jurisdição não foi solicitada no enunciado.

Você pode abrir vários clientes em terminais diferentes, com UFs diferentes. As consultas do DENATRAN são nacionais, inclusive quando solicitadas por um cliente de outra UF. Este Compose usa uma instância de cada serviço; não execute réplicas com o mesmo ID MQTT ou o mesmo arquivo de dados.

### Tópicos MQTT

| Padrão | Uso | QoS | Retido |
|---|---|---|---|
| `detran/<UF>/comandos/<servico>` | Solicitações ao serviço | 1 | Não |
| `detran/respostas/<clienteId>` | Resposta correlacionada ao UUID da solicitação | 1 | Não |
| `detran/<UF>/eventos/<servico>` | Snapshot do estado da UF, com versão | 1 | Sim |
| `detran/status/<servico>` | Presença do serviço e Last Will | 1 | Sim |

Exemplo de solicitação ao cadastro:

```json
{
  "id": "8d7b5847-8b4e-4990-835f-2d362fa2370a",
  "uf": "SE",
  "operacao": "cadastrar",
  "dados": { "cpf": "52998224725", "nome": "Ana Exemplo" },
  "responderEm": "detran/respostas/cliente-exemplo"
}
```

O cliente e o menu já criam e assinam o tópico de resposta. O exemplo documenta o contrato; publicar esse JSON manualmente não cria um consumidor de respostas.

O QoS 1 pode entregar uma mensagem mais de uma vez. Por isso, as mutações guardam o UUID e a resposta junto dos dados: repetir o mesmo comando com o mesmo UUID retorna a resposta anterior. Um UUID reutilizado com outro conteúdo é rejeitado. Comandos iguais enviados como novas solicitações recebem novos UUIDs; cadastros de CPF ou placa já existentes são rejeitados por regra de negócio.

Snapshots são retidos para que o DENATRAN recupere a visão mesmo entrando depois dos demais serviços. Versões antigas não sobrescrevem versões novas. Após uma alteração, o cliente aguarda a versão correspondente no DENATRAN antes de retornar; assim, uma consulta imediatamente seguinte enxerga essa alteração. Os callbacks MQTT apenas encaminham trabalho, e as chamadas entre serviços ocorrem em threads de processamento.

## Regras e decisões do exercício

- IPVA: exatamente **2%**, conforme o enunciado; valor monetário calculado com `BigDecimal` e arredondado para centavos por `HALF_UP`.
- Emplacamento: o ano atual é usado quando `ano` é omitido. O campo opcional permite demonstrar filtros por ano com dados fictícios.
- Multas do veículo: a descrição da função exige ano, apesar de a lista de argumentos do PDF citar apenas placa. Esta implementação recebe **placa e ano**.
- O enunciado não informa um CPF separado para o infrator. A multa é atribuída ao proprietário registrado no momento do lançamento. Transferir o veículo posteriormente não altera multas antigas.
- Top 5: soma de todas as multas registradas, sem filtro de ano; ordem decrescente de pontos. Em empate, CPF crescente; até cinco condutores com multas.
- CPF: máscara opcional, com validação dos dígitos verificadores. Placa: formatos `AAA1234` e `AAA1A23`. Ano: 1900 a 2100; pontuação: inteiro positivo. Valores positivos, com até duas casas decimais, sem separador de milhar.

## Exemplos por comando

Os CPFs e nomes a seguir são dados de exemplo. Execute a sequência uma vez em uma base sem esses registros; depois use os comandos de consulta ou os dados novos gerados pela demonstração.

```powershell
# Cadastro em dois DETRANs
docker compose run --rm cliente cliente SE cadastrar-condutor cpf=52998224725 "nome=Ana Exemplo"
docker compose run --rm cliente cliente BA cadastrar-condutor cpf=11144477735 "nome=Bruno Exemplo"

# Veículo e IPVA
docker compose run --rm cliente cliente SE emplacar placa=ABC1D23 "modelo=Carro Exemplo" valor=50000.00 cpf=52998224725 ano=2026
docker compose run --rm cliente cliente SE ipva placa=ABC1D23

# Multa antes da transferência
docker compose run --rm cliente cliente SE lancar-multa ano=2026 "descricao=Infracao ficticia anterior" pontuacao=5 placa=ABC1D23

# Transferência e multa posterior
docker compose run --rm cliente cliente BA transferir placa=ABC1D23 novoCpf=11144477735
docker compose run --rm cliente cliente BA lancar-multa ano=2026 "descricao=Infracao ficticia posterior" pontuacao=7 placa=ABC1D23

# Todas as consultas do enunciado
docker compose run --rm cliente cliente BA veiculos-ano ano=2026
docker compose run --rm cliente cliente SE multas-veiculo placa=ABC1D23 ano=2026
docker compose run --rm cliente cliente SE multas-condutor cpf=52998224725 ano=2026
docker compose run --rm cliente cliente BA multas-ano ano=2026
docker compose run --rm cliente cliente SE top5
docker compose run --rm cliente cliente SE resumo
```

A consulta da placa mostrará uma multa de Ana e outra de Bruno. A consulta do CPF de Ana continuará mostrando sua multa depois da transferência.

Para observar as mensagens enquanto usa outro terminal:

```powershell
docker compose exec mosquitto mosquitto_sub -t "detran/+/comandos/+" -v
```

`Ctrl+C` encerra a observação. Para ver respostas, use `-t "detran/respostas/+"`; para ver os estados retidos, `-t "detran/+/eventos/+"`.

## Testes e evidências para a entrega

O teste automático verifica os dez itens, duas UFs, dois anos, o condutor histórico, a ordem do ranking e mensagens repetidas. Também rejeita CPF inválido, pontuação inválida, CPF duplicado e placa duplicada. Na base vazia, a demonstração gera **6 condutores, 6 veículos e 8 multas**.

```powershell
docker compose run --rm verificar
docker compose run --rm cliente auditar
docker compose run --rm cliente cliente SE resumo
```

`auditar` confere os dados existentes, referências e ranking, sem inserir novos registros. Para executar os testes JUnit separadamente em um contêiner:

```powershell
docker compose build testes
docker compose run --rm testes
```

Para demonstrar persistência e reconexão:

```powershell
docker compose restart mosquitto
docker compose run --rm cliente auditar
docker compose down
docker compose up -d
docker compose run --rm cliente auditar
```

Guarde as saídas executadas **no seu computador**:

```powershell
New-Item -ItemType Directory -Force evidencias | Out-Null
docker compose ps | Out-File evidencias\containers.txt -Encoding utf8
docker compose run --rm verificar 2>&1 | Tee-Object evidencias\verificacao.txt
docker compose run --rm cliente auditar 2>&1 | Tee-Object evidencias\auditoria.txt
docker compose logs --no-color cadastro veiculos multas denatran | Out-File evidencias\servicos.txt -Encoding utf8
```

Para a apresentação, mostre os contêineres, a saída `10/10`, o menu e o exemplo da multa antes/depois da transferência. O registro em `docs/VALIDACAO.md` descreve os 12 testes unitários, a integração MQTT local e a execução Docker confirmada no Windows. Os trechos desta última estão em `docs/testes/docker-windows.txt`. Os comandos acima permitem guardar evidências de novas execuções.

## Parar e iniciar novamente

```powershell
docker compose down
docker compose up -d
```

Os volumes são preservados. Para **apagar todos os dados desta atividade** e começar uma base vazia:

```powershell
docker compose down -v
docker compose up -d
```

Esse segundo procedimento remove também as mensagens retidas do broker. Se mudar o código, refaça `docker compose build cadastro` e `docker compose up -d`. Para diagnosticar erros:

```powershell
docker compose ps -a
docker compose logs --tail=100 mosquitto cadastro veiculos multas denatran
```

O broker deste projeto usa porta `1883`; o RabbitMQ da atividade anterior usa outras portas. Se Docker informar falta de memória ou recursos, pare os contêineres da atividade anterior enquanto usa este projeto.

## Atalhos opcionais no PowerShell

Se a sua política já permite scripts locais:

```powershell
.\executar.ps1 iniciar
.\executar.ps1 testar
.\executar.ps1 menu -UF BA
.\executar.ps1 auditar
.\executar.ps1 parar
```

Os comandos Docker diretos funcionam sem mudar políticas do PowerShell.

## Executar Java pelo NetBeans ou terminal

Você pode manter os serviços no Docker e executar apenas o cliente Java na IDE. A classe principal é `br.ufs.detran.Main`; use argumentos `menu SE`. Com o broker Docker iniciado, o padrão `tcp://localhost:1883` funciona no host.

O ZIP já inclui o executável com dependências:

```powershell
java -jar .\dist\detran.jar --help
java -jar .\dist\detran.jar menu SE
```

Para recompilar localmente com Maven instalado (ou pela ação Construir do NetBeans):

```powershell
mvn clean package
java -jar .\target\detran.jar --help
```

Se desejar executar os serviços em Java fora do Docker, deixe somente o broker do Compose ligado, abra quatro terminais e use, um por terminal:

```powershell
java -jar .\dist\detran.jar servico cadastro
java -jar .\dist\detran.jar servico veiculos
java -jar .\dist\detran.jar servico multas
java -jar .\dist\detran.jar servico denatran
```

Os serviços Java criam `dados/<servico>` automaticamente. Para esse modo, pare os quatro serviços Docker antes, mantendo `mosquitto` ativo, pois o ID MQTT de cada serviço é estável e exclusivo.

O teste ampliado opcional usa Python 3, Java 17+ e Mosquitto 2 local; ele cria diretórios isolados, testa reinícios, reconstrói a projeção do DENATRAN e lança dois clientes simultâneos:

```powershell
python scripts\testar_integracao.py --jar dist\detran.jar --mosquitto "C:\Program Files\mosquitto\mosquitto.exe"
```

Python e Mosquitto local não são necessários para a execução normal por Docker.

## Escopo

Projeto didático para dados fictícios e execução local. O broker permite conexões anônimas na rede dos contêineres, e sua porta no Windows fica vinculada a `127.0.0.1`. Não há autenticação de operadores, TLS, exclusão/atualização de condutores, migração de UF, cobrança de impostos ou regras reais de trânsito além da taxa definida no exercício.

A gravação é atômica por serviço; as consultas entre serviços não constituem uma transação distribuída. Lançar multa e transferir o mesmo veículo ao mesmo tempo pode atribuir a multa ao proprietário lido durante a operação. O histórico preserva esse responsável. Os snapshots completos e os arquivos JSON atendem ao volume de uma atividade, sem alegar capacidade para uma base nacional de produção.

## Referências técnicas

- [Eclipse Paho: documentação do cliente Java](https://eclipse.dev/paho/files/javadoc/org/eclipse/paho/client/mqttv3/MqttClient.html)
- [Eclipse Mosquitto: configuração do broker](https://mosquitto.org/man/mosquitto-conf-5.html)
- [Docker Compose: dependências e ordem de inicialização](https://docs.docker.com/compose/how-tos/startup-order/)
- [Docker Compose: execução de comandos avulsos](https://docs.docker.com/reference/cli/docker/compose/run/)
- [Apache Maven](https://maven.apache.org/)
