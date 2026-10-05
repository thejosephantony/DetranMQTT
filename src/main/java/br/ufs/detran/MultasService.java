package br.ufs.detran;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;

final class MultasService extends Servico {
    MultasService() throws Exception { super("multas"); }
    ObjectNode execute(ObjectNode request) throws Exception {
        if (!Json.text(request, "operacao").equals("lancar")) throw new RegraException("OPERACAO_INVALIDA", "Operação desconhecida em multas.");
        ObjectNode data = (ObjectNode) request.get("dados");
        String uf = Validacao.uf(Json.text(request, "uf"));
        String placa = Validacao.placa(Json.text(data, "placa"));
        int ano = Validacao.ano(Json.text(data, "ano"));
        int pontos = Validacao.pontos(Json.text(data, "pontuacao"));
        String descricao = Json.text(data, "descricao");
        var veiculo = Json.result(bus.call("veiculos", uf, "obter", Json.obj("placa", placa)));
        var condutor = Json.result(bus.call("cadastro", uf, "obter", Json.obj("cpf", veiculo.path("proprietarioCpf").asText())));
        String id = Json.text(request, "id");
        return mutate(request, uf, rows -> {
            ObjectNode row = Json.obj("id", id, "uf", uf, "placa", placa, "ano", ano, "descricao", descricao,
                    "pontuacao", pontos, "condutor", Json.obj("cpf", condutor.path("cpf").asText(),
                            "nome", condutor.path("nome").asText(), "uf", condutor.path("uf").asText()),
                    "lancadaEm", Instant.now().toString());
            rows.set(id, row); return row.deepCopy();
        });
    }
}
