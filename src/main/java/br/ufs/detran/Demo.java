package br.ufs.detran;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Integração executada pelo mesmo cliente MQTT usado no menu e nos comandos. */
final class Demo {
    private Demo() {}
    static void executar(Cliente cliente) throws Exception {
        cliente.aguardarServicos();
        int ano = java.time.LocalDate.now().getYear();
        String[] cpfs = new String[6], placas = new String[6];
        String[] ufs = {"SE", "BA", "SE", "BA", "SE", "BA"};
        int[] pontos = {7, 5, 4, 3, 2, 1};
        Set<String> idsMultas = new HashSet<>();
        System.out.println("DEMONSTRAÇÃO: 6 condutores, 6 veículos e 8 multas fictícias em SE e BA.");
        System.out.println("Ano principal: " + ano + "; ano de controle: " + (ano - 1));
        for (int i = 0; i < 6; i++) {
            cpfs[i] = cpf(ThreadLocalRandom.current().nextLong(1, 999_999_999L));
            placas[i] = placa();
            JsonNode driver = resultado(cliente, ufs[i], "cadastrar-condutor", Json.obj("cpf", cpfs[i], "nome", "Condutor Demo " + (i + 1)));
            exigir(driver.path("cpf").asText().equals(cpfs[i]) && driver.path("uf").asText().equals(ufs[i]), "Cadastro incorreto.");
        }
        passou(4, "Cadastrar condutor: 6 CPFs válidos, em duas UFs.");
        for (int i = 0; i < 6; i++) {
            JsonNode vehicle = resultado(cliente, ufs[i], "emplacar", Json.obj("placa", placas[i], "modelo", "Modelo Demo " + (i + 1),
                    "valor", i == 0 ? "50000.00" : "35000.00", "cpf", cpfs[i], "ano", i == 5 ? ano - 1 : ano));
            exigir(vehicle.path("proprietarioCpf").asText().equals(cpfs[i]) && vehicle.path("placa").asText().equals(placas[i]), "Emplacamento incorreto.");
        }
        passou(1, "Emplacar veículo: 6 placas, com modelo, valor, proprietário e ano.");
        JsonNode ipva = resultado(cliente, "SE", "ipva", Json.obj("placa", placas[0]));
        exigir(ipva.path("ipva").asText().equals("1000.00"), "IPVA deve ser exatamente 2% de 50000.00.");
        passou(2, "IPVA: R$ 50.000,00 × 2% = R$ 1.000,00.");

        ObjectNode primeira = Json.obj("ano", ano, "descricao", "Infração fictícia antes da transferência", "pontuacao", pontos[0], "placa", placas[0]);
        String idRepetido = UUID.randomUUID().toString();
        ObjectNode original = cliente.enviar("multas", "SE", "lancar", primeira, idRepetido);
        idsMultas.add(Json.result(original).path("id").asText());
        ObjectNode repetida = cliente.enviar("multas", "SE", "lancar", primeira, idRepetido);
        exigir(original.equals(repetida), "Reenvio do mesmo UUID deve retornar a mesma multa.");
        for (int i = 1; i < 6; i++) {
            idsMultas.add(resultado(cliente, ufs[i], "lancar-multa", Json.obj("ano", ano,
                    "descricao", "Infração fictícia " + (i + 1), "pontuacao", pontos[i], "placa", placas[i])).path("id").asText());
        }
        idsMultas.add(resultado(cliente, "SE", "lancar-multa", Json.obj("ano", ano - 1,
                "descricao", "Infração fictícia do ano de controle", "pontuacao", 3, "placa", placas[0])).path("id").asText());
        String idTransferencia = UUID.randomUUID().toString();
        ObjectNode transfer = Json.obj("placa", placas[0], "novoCpf", cpfs[1]);
        JsonNode trocado;
        // Dois clientes enviam o mesmo UUID simultaneamente, como uma entrega MQTT repetida.
        try (Cliente segundoCliente = new Cliente()) {
            var paralelo = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var respostaParalela = paralelo.submit(() -> segundoCliente.enviar("cadastro", "BA", "transferir", transfer, idTransferencia));
                trocado = Json.result(cliente.enviar("cadastro", "BA", "transferir", transfer, idTransferencia));
                exigir(trocado.equals(Json.result(respostaParalela.get(45, java.util.concurrent.TimeUnit.SECONDS))), "Clientes receberam resultados diferentes para o mesmo UUID.");
            } finally { paralelo.shutdownNow(); }
        }
        exigir(trocado.path("proprietarioCpf").asText().equals(cpfs[1]), "Transferência não mudou o proprietário.");
        JsonNode reenvio = Json.result(cliente.enviar("cadastro", "BA", "transferir", transfer, idTransferencia));
        exigir(trocado.equals(reenvio) && trocado.path("transferencias").size() == 1, "Transferência duplicada alterou o histórico.");
        passou(3, "Transferir proprietário: novo CPF validado; histórico de uma transferência.");
        idsMultas.add(resultado(cliente, "BA", "lancar-multa", Json.obj("ano", ano,
                "descricao", "Infração fictícia depois da transferência", "pontuacao", 6, "placa", placas[0])).path("id").asText());
        passou(5, "Lançar multa: 8 multas; cada registro preserva o condutor da época.");

        JsonNode veiculos = resultado(cliente, "BA", "veiculos-ano", Json.obj("ano", ano));
        exigir(contar(veiculos, "placa", Set.of(placas)) == 5, "Filtro de ano de emplacamento incorreto.");
        Set<String> estados = new HashSet<>();
        veiculos.forEach(row -> { if (Set.of(placas).contains(row.path("placa").asText())) estados.add(row.path("uf").asText()); });
        exigir(estados.equals(Set.of("SE", "BA")), "Consulta nacional não reuniu as duas UFs.");
        passou(6, "Veículos emplacados por ano: 5 atuais e 1 do ano de controle; consulta reúne SE/BA.");
        JsonNode multasVeiculo = resultado(cliente, "SE", "multas-veiculo", Json.obj("placa", placas[0], "ano", ano));
        exigir(multasVeiculo.size() == 2, "Veículo transferido deve ter duas multas no ano principal.");
        Set<String> responsaveis = new HashSet<>();
        multasVeiculo.forEach(row -> {
            responsaveis.add(row.path("condutor").path("cpf").asText());
            exigir(!row.path("condutor").path("nome").asText().isBlank(), "Falta nome do condutor na multa.");
        });
        exigir(responsaveis.equals(Set.of(cpfs[0], cpfs[1])), "Transferência reescreveu o condutor de multa antiga.");
        passou(7, "Multas do veículo/ano: CPF e nome do antigo e do novo proprietário preservados.");
        JsonNode multasAntigo = resultado(cliente, "BA", "multas-condutor", Json.obj("cpf", cpfs[0], "ano", ano));
        JsonNode multasNovo = resultado(cliente, "SE", "multas-condutor", Json.obj("cpf", cpfs[1], "ano", ano));
        exigir(multasAntigo.size() == 1 && multasNovo.size() == 2, "Consulta de multas do condutor está incorreta.");
        passou(8, "Multas do condutor/ano: 1 do antigo proprietário e 2 do novo.");
        JsonNode multasAno = resultado(cliente, "SE", "multas-ano", Json.obj("ano", ano));
        JsonNode multasPassado = resultado(cliente, "SE", "multas-ano", Json.obj("ano", ano - 1));
        exigir(contar(multasAno, "id", idsMultas) == 7 && contar(multasPassado, "id", idsMultas) == 1, "Multas por ano ou deduplicação incorretas.");
        passou(9, "Multas por ano: 7 no ano principal e 1 no ano de controle, sem duplicação de mensagem.");
        conferirEstadoERanking(cliente);
        passou(10, "Top 5 nacional: soma de pontos de todo o histórico, ordem decrescente e desempate por CPF.");

        erro(cliente.executar("SE", "cadastrar-condutor", Json.obj("cpf", cpfs[0], "nome", "Duplicado")), "JA_CADASTRADO");
        erro(cliente.executar("SE", "emplacar", Json.obj("placa", placas[0], "modelo", "Duplicado", "valor", "1.00", "cpf", cpfs[0])), "JA_CADASTRADO");
        erro(cliente.executar("SE", "cadastrar-condutor", Json.obj("cpf", "11111111111", "nome", "Inválido")), "CPF_INVALIDO");
        erro(cliente.executar("SE", "lancar-multa", Json.obj("ano", ano, "descricao", "Inválido", "pontuacao", 0, "placa", placas[0])), "PONTUACAO_INVALIDA");
        System.out.println("[OK] Extras: mensagem repetida, transferência repetida, duplicidades, CPF inválido e pontuação inválida.");
        var dados = Json.array();
        for (int i = 0; i < 6; i++) dados.add(Json.obj("cpf", cpfs[i], "placa", placas[i], "uf", ufs[i]));
        System.out.println("DADOS_DA_DEMONSTRACAO=" + dados);
        System.out.println("SUCESSO: 10/10 funcionalidades verificadas com comunicação MQTT real.");
    }
    static void auditar(Cliente cliente) throws Exception {
        cliente.aguardarServicos();
        conferirEstadoERanking(cliente);
        JsonNode resumo = resultado(cliente, "SE", "resumo", Json.obj());
        System.out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(resumo));
        System.out.println("AUDITORIA OK: referências, dados persistidos e ranking nacional consistentes.");
    }
    private static void conferirEstadoERanking(Cliente cliente) throws Exception {
        JsonNode estado = Json.result(cliente.enviar("denatran", "SE", "estado", Json.obj()));
        Map<String, JsonNode> condutores = new HashMap<>(), veiculos = new HashMap<>();
        Map<String, Long> pontos = new HashMap<>(), quantidades = new HashMap<>();
        for (JsonNode row : estado.path("condutores")) {
            String cpf = Validacao.cpf(row.path("cpf").asText());
            exigir(condutores.put(cpf, row) == null, "CPF duplicado no estado nacional.");
        }
        for (JsonNode row : estado.path("veiculos")) {
            String placa = Validacao.placa(row.path("placa").asText());
            exigir(veiculos.put(placa, row) == null, "Placa duplicada no estado nacional.");
            exigir(condutores.containsKey(row.path("proprietarioCpf").asText()), "Proprietário sem cadastro.");
            Validacao.valor(row.path("valor").asText()); Validacao.ano(row.path("anoEmplacamento").asText());
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode row : estado.path("multas")) {
            exigir(ids.add(row.path("id").asText()), "Multa repetida na projeção nacional.");
            String cpf = row.path("condutor").path("cpf").asText();
            exigir(condutores.containsKey(cpf) && veiculos.containsKey(row.path("placa").asText()), "Multa sem condutor/veículo cadastrado.");
            int valor = Validacao.pontos(row.path("pontuacao").asText());
            pontos.merge(cpf, (long) valor, Long::sum); quantidades.merge(cpf, 1L, Long::sum);
        }
        List<String> esperado = new ArrayList<>(pontos.keySet());
        esperado.sort(Comparator.<String>comparingLong(pontos::get).reversed().thenComparing(Comparator.naturalOrder()));
        if (esperado.size() > 5) esperado = esperado.subList(0, 5);
        JsonNode ranking = resultado(cliente, "BA", "top5", Json.obj());
        exigir(ranking.size() == esperado.size(), "Ranking deve retornar até cinco condutores.");
        for (int i = 0; i < esperado.size(); i++) {
            String cpf = esperado.get(i); JsonNode row = ranking.get(i);
            exigir(row.path("cpf").asText().equals(cpf) && row.path("totalPontos").asLong() == pontos.get(cpf)
                    && row.path("totalMultas").asLong() == quantidades.get(cpf), "Soma/ordenação incorreta no ranking.");
        }
    }
    private static JsonNode resultado(Cliente cliente, String uf, String op, ObjectNode dados) throws Exception {
        return Json.result(cliente.executar(uf, op, dados));
    }
    private static long contar(JsonNode rows, String chave, Set<String> valores) {
        long total = 0; for (JsonNode row : rows) if (valores.contains(row.path(chave).asText())) total++; return total;
    }
    private static void erro(ObjectNode resposta, String codigo) {
        exigir(!resposta.path("ok").asBoolean() && resposta.path("erro").path("codigo").asText().equals(codigo), "Erro esperado: " + codigo + "; obtido: " + resposta);
    }
    private static void exigir(boolean condicao, String mensagem) {
        if (!condicao) throw new IllegalStateException("TESTE FALHOU: " + mensagem);
    }
    private static void passou(int item, String mensagem) { System.out.println("[OK " + item + "/10] " + mensagem); }
    static String cpf(long base) {
        String corpo = String.format(java.util.Locale.ROOT, "%09d", base);
        for (int tamanho = 9; tamanho <= 10; tamanho++) {
            int soma = 0;
            for (int i = 0; i < tamanho; i++) soma += (corpo.charAt(i) - '0') * (tamanho + 1 - i);
            int digito = 11 - soma % 11; corpo += digito >= 10 ? 0 : digito;
        }
        return Validacao.cpf(corpo);
    }
    private static String placa() {
        var random = ThreadLocalRandom.current();
        return "" + (char) ('A' + random.nextInt(26)) + (char) ('A' + random.nextInt(26)) + (char) ('A' + random.nextInt(26))
                + random.nextInt(10) + (char) ('A' + random.nextInt(26)) + random.nextInt(10) + random.nextInt(10);
    }
}
