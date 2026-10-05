package br.ufs.detran;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CountDownLatch;

public final class Main {
    private Main() {}
    public static void main(String[] args) {
        int codigo = 0;
        try { codigo = executar(args); }
        catch (Exception e) {
            System.err.println("ERRO: " + e.getMessage());
            if (Boolean.parseBoolean(MqttBus.env("DEBUG", "false"))) e.printStackTrace(System.err);
            codigo = 2;
        }
        if (codigo != 0) System.exit(codigo);
    }
    private static int executar(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) { ajuda(); return 0; }
        switch (args[0]) {
            case "servico" -> {
                if (args.length != 2) throw new IllegalArgumentException("Use: servico cadastro|veiculos|multas|denatran");
                Servico servico = switch (args[1]) {
                    case "cadastro" -> new CadastroService(); case "veiculos" -> new VeiculosService();
                    case "multas" -> new MultasService(); case "denatran" -> new DenatranService();
                    default -> throw new IllegalArgumentException("Serviço desconhecido: " + args[1]);
                };
                Runtime.getRuntime().addShutdownHook(new Thread(servico::close, "encerrar-servico"));
                servico.start(); new CountDownLatch(1).await();
            }
            case "cliente" -> {
                if (args.length < 3) throw new IllegalArgumentException("Use: cliente UF operacao chave=valor ...");
                ObjectNode dados = Json.obj();
                for (int i = 3; i < args.length; i++) {
                    int separador = args[i].indexOf('=');
                    if (separador <= 0) throw new IllegalArgumentException("Argumento deve ser chave=valor: " + args[i]);
                    String chave = args[i].substring(0, separador);
                    if (dados.has(chave)) throw new IllegalArgumentException("Argumento repetido: " + chave);
                    dados.put(chave, args[i].substring(separador + 1));
                }
                try (Cliente cliente = new Cliente()) {
                    ObjectNode resposta = cliente.executar(args[1], args[2], dados);
                    System.out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(resposta));
                    return resposta.path("ok").asBoolean() ? 0 : 2;
                }
            }
            case "menu" -> Menu.executar(args.length > 1 ? args[1] : "SE");
            case "verificar", "demo" -> {
                try (Cliente cliente = new Cliente()) { Demo.executar(cliente); }
            }
            case "auditar" -> {
                try (Cliente cliente = new Cliente()) { Demo.auditar(cliente); }
            }
            default -> throw new IllegalArgumentException("Modo desconhecido. Use --help.");
        }
        return 0;
    }
    private static void ajuda() {
        System.out.println("""
                DETRAN MQTT - Java / Eclipse Mosquitto

                Uso: java -jar target/detran.jar <modo> [argumentos]
                  servico cadastro|veiculos|multas|denatran
                  menu [UF]              Menu com as dez operações (UF padrão: SE).
                  cliente UF operacao chave=valor ...
                  verificar              Cria dados fictícios e testa as dez operações.
                  demo                   Mesmo comportamento de verificar.
                  auditar                Confere dados existentes e ranking, sem inserir.
                  --help                 Exibe esta ajuda.

                Operações do cliente:
                  cadastrar-condutor cpf=... "nome=Nome Completo"
                  emplacar placa=... "modelo=Modelo" valor=50000.00 cpf=... [ano=2026]
                  ipva placa=...
                  transferir placa=... novoCpf=...
                  lancar-multa ano=2026 "descricao=Descricao" pontuacao=5 placa=...
                  veiculos-ano ano=2026
                  multas-veiculo placa=... ano=2026
                  multas-condutor cpf=... ano=2026
                  multas-ano ano=2026
                  top5
                  resumo

                Configuração por variáveis de ambiente:
                  MQTT_BROKER=tcp://localhost:1883
                  MQTT_PREFIX=detran
                  DATA_DIR=./dados/<servico>
                  DEBUG=false

                A alíquota de IPVA deste exercício é 2%.
                Consultas nacionais incluem todas as UFs; UF indica a origem do comando.
                CPF deve ter dígitos verificadores válidos. Use somente dados fictícios.
                """);
    }
}
