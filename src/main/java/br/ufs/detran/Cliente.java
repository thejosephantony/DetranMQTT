package br.ufs.detran;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/** Cliente de um DETRAN. As consultas do DENATRAN têm abrangência nacional. */
final class Cliente implements AutoCloseable {
    private record Rota(String servico, String operacao) {}
    private static final Map<String, Rota> ROTAS = Map.ofEntries(
            Map.entry("cadastrar-condutor", new Rota("cadastro", "cadastrar")),
            Map.entry("emplacar", new Rota("veiculos", "emplacar")),
            Map.entry("ipva", new Rota("veiculos", "ipva")),
            Map.entry("transferir", new Rota("cadastro", "transferir")),
            Map.entry("lancar-multa", new Rota("multas", "lancar")),
            Map.entry("veiculos-ano", new Rota("denatran", "veiculosAno")),
            Map.entry("multas-veiculo", new Rota("denatran", "multasVeiculoAno")),
            Map.entry("multas-condutor", new Rota("denatran", "multasCondutorAno")),
            Map.entry("multas-ano", new Rota("denatran", "multasAno")),
            Map.entry("top5", new Rota("denatran", "top5")),
            Map.entry("resumo", new Rota("denatran", "resumo")));
    final MqttBus bus = new MqttBus(null, null);
    Cliente() throws Exception {}

    ObjectNode executar(String uf, String operacao, ObjectNode dados) throws Exception {
        Rota rota = ROTAS.get(operacao);
        if (rota == null) throw new RegraException("OPERACAO_INVALIDA", "Use --help para consultar as operações.");
        return enviar(rota.servico, Validacao.uf(uf), rota.operacao, dados);
    }
    ObjectNode enviar(String servico, String uf, String operacao, ObjectNode dados) throws Exception {
        ObjectNode resposta = bus.call(servico, uf, operacao, dados);
        sincronizar(resposta);
        return resposta;
    }
    ObjectNode enviar(String servico, String uf, String operacao, ObjectNode dados, String id) throws Exception {
        ObjectNode resposta = bus.call(servico, uf, operacao, dados, id);
        sincronizar(resposta);
        return resposta;
    }
    private void sincronizar(ObjectNode resposta) throws Exception {
        if (resposta.path("ok").asBoolean() && resposta.has("meta")) {
            ObjectNode meta = (ObjectNode) resposta.get("meta");
            Json.result(bus.call("denatran", meta.path("uf").asText(), "sincronizar", meta));
        }
    }
    void aguardarServicos() throws Exception {
        Json.result(bus.call("denatran", "SE", "ping", Json.obj()));
        for (String nome : java.util.List.of("cadastro", "veiculos", "multas")) {
            var pronto = Json.result(bus.call(nome, "SE", "ping", Json.obj()));
            for (String uf : Validacao.UFS)
                Json.result(bus.call("denatran", uf, "sincronizar", Json.obj("servico", nome, "uf", uf, "versao", pronto.path("versao"))));
        }
    }
    public void close() { bus.close(); }
}
