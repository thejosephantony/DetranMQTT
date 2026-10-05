package br.ufs.detran;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;

final class VeiculosService extends Servico {
    VeiculosService() throws Exception { super("veiculos"); }
    ObjectNode execute(ObjectNode request) throws Exception {
        ObjectNode data = (ObjectNode) request.get("dados");
        String uf = Validacao.uf(Json.text(request, "uf"));
        String placa = Validacao.placa(Json.text(data, "placa"));
        return switch (Json.text(request, "operacao")) {
            case "emplacar" -> {
                String cpf = Validacao.cpf(Json.text(data, "cpf"));
                String modelo = Json.text(data, "modelo");
                var valor = Validacao.valor(Json.text(data, "valor"));
                int ano = Validacao.ano(data.has("ano") ? Json.text(data, "ano") : Integer.toString(LocalDate.now().getYear()));
                Json.result(bus.call("cadastro", uf, "obter", Json.obj("cpf", cpf)));
                yield mutate(request, uf, rows -> {
                    if (rows.has(placa)) throw new RegraException("JA_CADASTRADO", "Placa já cadastrada.");
                    ObjectNode row = Json.obj("placa", placa, "modelo", modelo, "valor", valor.toPlainString(),
                            "proprietarioCpf", cpf, "uf", uf, "anoEmplacamento", ano,
                            "emplacadoEm", Instant.now().toString(), "transferencias", Json.array());
                    rows.set(placa, row); return row.deepCopy();
                });
            }
            case "obter" -> Json.ok(find(store.records(), placa, "Veículo"));
            case "ipva" -> {
                ObjectNode row = find(store.records(), placa, "Veículo");
                var base = Validacao.valor(row.path("valor").asText());
                yield Json.ok(Json.obj("placa", placa, "valorVeiculo", base.toPlainString(), "aliquota", "0.02", "ipva", Validacao.ipva(base).toPlainString()));
            }
            case "transferirConfirmado" -> {
                String novoCpf = Validacao.cpf(Json.text(data.path("novoDono"), "cpf"));
                String originalUf = find(store.records(), placa, "Veículo").path("uf").asText();
                yield mutate(request, originalUf, rows -> {
                    ObjectNode row = find(rows, placa, "Veículo");
                    String antigoCpf = row.path("proprietarioCpf").asText();
                    if (antigoCpf.equals(novoCpf)) throw new RegraException("MESMO_PROPRIETARIO", "O veículo já pertence ao CPF informado.");
                    ((com.fasterxml.jackson.databind.node.ArrayNode) row.get("transferencias")).add(
                            Json.obj("de", antigoCpf, "para", novoCpf, "em", Instant.now().toString()));
                    row.put("proprietarioCpf", novoCpf); rows.set(placa, row); return row.deepCopy();
                });
            }
            default -> throw new RegraException("OPERACAO_INVALIDA", "Operação desconhecida em veículos.");
        };
    }
}
