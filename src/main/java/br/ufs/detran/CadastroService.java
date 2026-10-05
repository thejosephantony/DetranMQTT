package br.ufs.detran;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;

final class CadastroService extends Servico {
    CadastroService() throws Exception { super("cadastro"); }
    ObjectNode execute(ObjectNode request) throws Exception {
        ObjectNode data = (ObjectNode) request.get("dados");
        String uf = Validacao.uf(Json.text(request, "uf"));
        return switch (Json.text(request, "operacao")) {
            case "cadastrar" -> {
                String cpf = Validacao.cpf(Json.text(data, "cpf"));
                String nome = Json.text(data, "nome");
                if (nome.length() > 120) throw new RegraException("DADOS_INVALIDOS", "Nome deve ter até 120 caracteres.");
                yield mutate(request, uf, rows -> {
                    if (rows.has(cpf)) throw new RegraException("JA_CADASTRADO", "CPF já cadastrado.");
                    ObjectNode row = Json.obj("cpf", cpf, "nome", nome, "uf", uf, "cadastradoEm", Instant.now().toString());
                    rows.set(cpf, row); return row.deepCopy();
                });
            }
            case "obter" -> Json.ok(find(store.records(), Validacao.cpf(Json.text(data, "cpf")), "Condutor"));
            case "transferir" -> {
                String placa = Validacao.placa(Json.text(data, "placa"));
                String cpf = Validacao.cpf(Json.text(data, "novoCpf"));
                ObjectNode novoDono = find(store.records(), cpf, "Novo proprietário");
                ObjectNode response = bus.call("veiculos", uf, "transferirConfirmado",
                        Json.obj("placa", placa, "novoDono", novoDono), MqttBus.childId(Json.text(request, "id"), "transferir"));
                Json.result(response);
                response.remove("id"); yield response;
            }
            default -> throw new RegraException("OPERACAO_INVALIDA", "Operação desconhecida no cadastro.");
        };
    }
}
