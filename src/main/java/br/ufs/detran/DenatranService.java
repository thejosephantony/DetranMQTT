package br.ufs.detran;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

final class DenatranService extends Servico {
    private final java.util.concurrent.ExecutorService events = Executors.newSingleThreadExecutor();
    DenatranService() throws Exception { super("denatran"); }
    @Override void start() throws Exception {
        bus.subscribe(bus.prefix + "/+/eventos/+", (topic, message) -> events.submit(() -> apply(topic, message)));
        super.start();
    }
    private void apply(String topic, ObjectNode event) {
        try {
            String service = Json.text(event, "servico"), uf = Validacao.uf(Json.text(event, "uf"));
            if (!List.of("cadastro", "veiculos", "multas").contains(service)
                    || !topic.equals(bus.prefix + "/" + uf + "/eventos/" + service)
                    || !event.path("registros").isArray() || !event.path("versao").canConvertToLong()) return;
            store.apply(rows -> {
                if (!rows.has(service)) rows.set(service, Json.obj());
                ObjectNode group = (ObjectNode) rows.get(service);
                JsonNode old = group.get(uf);
                if (old != null && old.path("versao").asLong() >= event.path("versao").asLong()) return false;
                group.set(uf, event.deepCopy()); return true;
            });
        } catch (Exception e) { System.err.println("[DENATRAN] Evento pendente: " + e.getMessage()); }
    }
    private List<JsonNode> rows(String service) {
        List<JsonNode> result = new ArrayList<>();
        store.records().path(service).forEach(state -> state.path("registros").forEach(result::add));
        return result;
    }
    private ArrayNode filter(String service, Predicate<JsonNode> predicate, String key) {
        ArrayNode result = Json.array();
        rows(service).stream().filter(predicate).sorted(Comparator.comparing(row -> row.path(key).asText())).forEach(result::add);
        return result;
    }
    ObjectNode execute(ObjectNode request) throws Exception {
        ObjectNode data = (ObjectNode) request.get("dados");
        return switch (Json.text(request, "operacao")) {
            case "sincronizar" -> {
                String service = Json.text(data, "servico"), uf = Validacao.uf(Json.text(data, "uf"));
                long version = data.path("versao").asLong(-1);
                if (!List.of("cadastro", "veiculos", "multas").contains(service) || version < 0)
                    throw new RegraException("DADOS_INVALIDOS", "Versão ou serviço inválido.");
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (store.records().path(service).path(uf).path("versao").asLong(-1) < version) {
                    if (System.nanoTime() >= deadline) throw new RegraException("SINCRONIZACAO_PENDENTE", "Estado ainda não chegou ao DENATRAN.");
                    Thread.sleep(40);
                }
                yield Json.ok(Json.obj("sincronizado", true, "servico", service, "uf", uf, "versao", version));
            }
            case "veiculosAno" -> {
                int ano = Validacao.ano(Json.text(data, "ano"));
                yield Json.ok(filter("veiculos", row -> row.path("anoEmplacamento").asInt() == ano, "placa"));
            }
            case "multasVeiculoAno" -> {
                int ano = Validacao.ano(Json.text(data, "ano")); String placa = Validacao.placa(Json.text(data, "placa"));
                yield Json.ok(filter("multas", row -> row.path("ano").asInt() == ano && row.path("placa").asText().equals(placa), "id"));
            }
            case "multasCondutorAno" -> {
                int ano = Validacao.ano(Json.text(data, "ano")); String cpf = Validacao.cpf(Json.text(data, "cpf"));
                yield Json.ok(filter("multas", row -> row.path("ano").asInt() == ano && row.path("condutor").path("cpf").asText().equals(cpf), "id"));
            }
            case "multasAno" -> {
                int ano = Validacao.ano(Json.text(data, "ano"));
                yield Json.ok(filter("multas", row -> row.path("ano").asInt() == ano, "id"));
            }
            case "top5" -> {
                Map<String, ObjectNode> scores = new HashMap<>();
                for (JsonNode fine : rows("multas")) {
                    var driver = fine.path("condutor"); String cpf = driver.path("cpf").asText();
                    ObjectNode score = scores.computeIfAbsent(cpf, ignored -> Json.obj("cpf", cpf,
                            "nome", driver.path("nome").asText(), "uf", driver.path("uf").asText(), "totalPontos", 0L, "totalMultas", 0L));
                    score.put("totalPontos", score.path("totalPontos").asLong() + fine.path("pontuacao").asInt());
                    score.put("totalMultas", score.path("totalMultas").asLong() + 1);
                }
                ArrayNode result = Json.array();
                scores.values().stream().sorted(Comparator.<ObjectNode>comparingLong(row -> row.path("totalPontos").asLong())
                        .reversed().thenComparing(row -> row.path("cpf").asText())).limit(5).forEach(result::add);
                yield Json.ok(result);
            }
            case "resumo" -> {
                List<JsonNode> drivers = rows("cadastro"), vehicles = rows("veiculos"), fines = rows("multas");
                TreeSet<String> ufs = new TreeSet<>();
                java.util.stream.Stream.of(drivers, vehicles, fines).flatMap(List::stream).forEach(row -> ufs.add(row.path("uf").asText()));
                yield Json.ok(Json.obj("abrangencia", "nacional", "ufsComDados", ufs, "condutores", drivers.size(), "veiculos", vehicles.size(), "multas", fines.size()));
            }
            case "estado" -> Json.ok(Json.obj("condutores", rows("cadastro"), "veiculos", rows("veiculos"), "multas", rows("multas")));
            default -> throw new RegraException("OPERACAO_INVALIDA", "Consulta desconhecida no DENATRAN.");
        };
    }
    @Override public void close() { events.shutdownNow(); super.close(); }
}
