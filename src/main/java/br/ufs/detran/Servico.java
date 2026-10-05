package br.ufs.detran;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

abstract class Servico implements AutoCloseable {
    final String name;
    final Store store;
    final MqttBus bus;
    private final java.util.concurrent.ExecutorService workers = Executors.newCachedThreadPool();
    private final java.util.concurrent.ScheduledExecutorService publisher = Executors.newSingleThreadScheduledExecutor();
    private volatile long published = -1;
    private record Execucao(String assinatura, java.util.concurrent.CompletableFuture<ObjectNode> resposta) {}
    private final java.util.concurrent.ConcurrentHashMap<String, Execucao> emAndamento = new java.util.concurrent.ConcurrentHashMap<>();
    Servico(String name) throws Exception {
        this.name = name;
        Path dir = Path.of(MqttBus.env("DATA_DIR", "./dados/" + name));
        store = new Store(dir.resolve("estado.json"));
        bus = new MqttBus(name, dir);
    }
    void start() throws Exception {
        bus.subscribe(bus.prefix + "/+/comandos/" + name, (topic, request) -> workers.submit(() -> process(request)));
        if (!name.equals("denatran")) {
            publisher.scheduleWithFixedDelay(this::publishSnapshots, 0, 2, TimeUnit.SECONDS);
            bus.onReconnect(() -> publisher.execute(() -> { published = -1; publishSnapshots(); }));
        }
        bus.ready();
        System.out.println("[" + name.toUpperCase() + "] PRONTO - comandos via MQTT; dados persistidos por serviço.");
    }
    private void process(ObjectNode request) {
        String id = request.path("id").asText();
        String reply = request.path("responderEm").asText();
        if (!reply.startsWith(bus.prefix + "/respostas/") || reply.contains("#") || reply.contains("+")) return;
        ObjectNode response;
        try {
            UUIDCheck(id); Validacao.uf(Json.text(request, "uf"));
            if (!request.path("dados").isObject()) throw new RegraException("DADOS_INVALIDOS", "dados deve ser um objeto JSON.");
            String operation = Json.text(request, "operacao");
            String assinatura = Json.obj("uf", request.get("uf"), "operacao", operation, "dados", request.get("dados")).toString();
            Execucao atual = new Execucao(assinatura, new java.util.concurrent.CompletableFuture<>());
            Execucao existente = emAndamento.putIfAbsent(id, atual);
            if (existente != null) {
                if (!existente.assinatura().equals(assinatura)) throw new RegraException("ID_REUTILIZADO", "Identificador em uso com outros dados.");
                response = existente.resposta().get().deepCopy();
            } else {
                try {
                    response = operation.equals("ping") ? Json.ok(Json.obj("servico", name, "status", "pronto", "versao", store.snapshot().path("version"))) : execute(request);
                    atual.resposta().complete(response.deepCopy());
                } catch (Exception e) { atual.resposta().completeExceptionally(e); throw e; }
                finally { emAndamento.remove(id, atual); }
            }
            if (!name.equals("denatran") && response.has("meta")) publisher.execute(this::publishSnapshots);
        } catch (RegraException e) { response = Json.obj("ok", false, "erro", Json.obj("codigo", e.codigo, "mensagem", e.getMessage())); }
        catch (Exception e) {
            Throwable origem = e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
            if (origem instanceof RegraException regra) {
                response = Json.obj("ok", false, "erro", Json.obj("codigo", regra.codigo, "mensagem", regra.getMessage()));
            } else {
                e.printStackTrace(System.err);
                response = Json.obj("ok", false, "erro", Json.obj("codigo", "FALHA_INTERNA", "mensagem", e.getMessage() == null ? "Falha interna." : e.getMessage()));
            }
        }
        response.put("id", id);
        try { bus.publish(reply, response, false); }
        catch (Exception e) { System.err.println("[" + name + "] Não foi possível enviar a resposta: " + e.getMessage()); }
    }
    private static void UUIDCheck(String id) {
        try { java.util.UUID.fromString(id); }
        catch (IllegalArgumentException e) { throw new RegraException("DADOS_INVALIDOS", "id deve ser UUID."); }
    }
    abstract ObjectNode execute(ObjectNode request) throws Exception;
    ObjectNode mutate(ObjectNode request, String uf, Function<ObjectNode, ObjectNode> change) throws Exception {
        return store.transaction(Json.text(request, "id"),
                Json.obj("operacao", request.get("operacao"), "uf", request.get("uf"), "dados", request.get("dados")).toString(),
                name, uf, change);
    }
    static ObjectNode find(ObjectNode records, String key, String type) {
        JsonNode node = records.get(key);
        if (node == null) throw new RegraException("NAO_ENCONTRADO", type + " não encontrado: " + key);
        return (ObjectNode) node.deepCopy();
    }
    private void publishSnapshots() {
        try {
            ObjectNode state = store.snapshot(); long version = state.path("version").asLong();
            if (version == published) return;
            for (String uf : Validacao.UFS) {
                var rows = Json.array();
                state.path("records").forEach(row -> { if (row.path("uf").asText().equals(uf)) rows.add(row); });
                bus.publish(bus.prefix + "/" + uf + "/eventos/" + name,
                        Json.obj("servico", name, "uf", uf, "versao", version, "registros", rows), true);
            }
            published = version;
        } catch (Exception e) { System.err.println("[" + name + "] Publicação de estado pendente: " + e.getMessage()); }
    }
    public void close() { publisher.shutdownNow(); workers.shutdownNow(); bus.close(); }
}
