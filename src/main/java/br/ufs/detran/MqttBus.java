package br.ufs.detran;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

final class MqttBus implements AutoCloseable {
    final String prefix;
    private final MqttClient client;
    private final String replies;
    private final String service;
    private final Map<String, CompletableFuture<ObjectNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, BiConsumer<String, ObjectNode>> subscriptions = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService recovery = Executors.newSingleThreadExecutor();
    private volatile Runnable reconnected = () -> {};
    private volatile boolean closed;

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
    MqttBus(String service, Path dataDir) throws Exception {
        this.service = service;
        prefix = env("MQTT_PREFIX", "detran");
        if (!prefix.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("MQTT_PREFIX deve ser um nome simples.");
        String id = service == null ? "cli-" + UUID.randomUUID().toString().substring(0, 12)
                : "svc-" + service + "-" + Integer.toHexString(prefix.hashCode());
        replies = prefix + "/respostas/" + id;
        var persistence = service == null ? new MemoryPersistence()
                : new MqttDefaultFilePersistence(Files.createDirectories(dataDir.resolve("mqtt")).toString());
        client = new MqttClient(env("MQTT_BROKER", "tcp://localhost:1883"), id, persistence);
        client.setTimeToWait(8000);
        client.setCallback(new MqttCallbackExtended() {
            public void connectComplete(boolean reconnect, String serverURI) {
                if (reconnect && !closed) recovery.submit(() -> {
                    try {
                        client.subscribe(replies, 1);
                        for (String filter : subscriptions.keySet()) client.subscribe(filter, 1);
                        ready(); reconnected.run();
                    } catch (Exception e) { System.err.println("[MQTT] Recuperação: " + e.getMessage()); }
                });
            }
            public void connectionLost(Throwable cause) { if (!closed) System.err.println("[MQTT] Conexão perdida; reconexão automática ativada."); }
            public void deliveryComplete(IMqttDeliveryToken token) {}
            public void messageArrived(String topic, MqttMessage message) {
                try {
                    ObjectNode body = Json.parse(message.getPayload());
                    if (topic.equals(replies)) {
                        var future = pending.get(body.path("id").asText());
                        if (future != null) future.complete(body);
                    } else subscriptions.forEach((filter, handler) -> {
                        if (matches(filter, topic)) handler.accept(topic, body);
                    });
                } catch (Exception e) { System.err.println("[MQTT] Mensagem inválida: " + e.getMessage()); }
            }
        });
        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setAutomaticReconnect(true);
        options.setCleanSession(service == null);
        options.setConnectionTimeout(5);
        options.setKeepAliveInterval(20);
        if (service != null) options.setWill(prefix + "/status/" + service,
                Json.MAPPER.writeValueAsBytes(Json.obj("servico", service, "online", false)), 1, true);
        Exception last = null;
        for (int attempt = 1; attempt <= 20; attempt++) {
            try { client.connect(options); last = null; break; }
            catch (Exception e) { last = e; Thread.sleep(1000); }
        }
        if (last != null) { recovery.shutdownNow(); client.close(); throw last; }
        client.subscribe(replies, 1);
    }
    void onReconnect(Runnable action) { reconnected = action; }
    void subscribe(String filter, BiConsumer<String, ObjectNode> handler) throws Exception {
        subscriptions.put(filter, handler); client.subscribe(filter, 1);
    }
    void publish(String topic, ObjectNode payload, boolean retained) throws Exception {
        client.publish(topic, Json.MAPPER.writeValueAsBytes(payload), 1, retained);
    }
    void ready() throws Exception {
        if (service != null) publish(prefix + "/status/" + service,
                Json.obj("servico", service, "online", true), true);
    }
    ObjectNode call(String target, String uf, String operation, ObjectNode data) throws Exception {
        return call(target, uf, operation, data, UUID.randomUUID().toString());
    }
    ObjectNode call(String target, String uf, String operation, ObjectNode data, String id) throws Exception {
        ObjectNode request = Json.obj("id", id, "uf", Validacao.uf(uf), "operacao", operation,
                "dados", data, "responderEm", replies);
        CompletableFuture<ObjectNode> future = new CompletableFuture<>();
        if (pending.putIfAbsent(id, future) != null) throw new IllegalArgumentException("ID já está aguardando resposta.");
        try {
            Exception last = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    publish(prefix + "/" + request.path("uf").asText() + "/comandos/" + target, request, false);
                    return future.get(Duration.ofSeconds(12).toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception e) { last = e; if (future.isDone()) return future.get(); }
            }
            throw new java.io.IOException("Sem resposta de " + target + ". Confira os serviços e o broker.", last);
        } finally { pending.remove(id, future); }
    }
    static String childId(String parent, String operation) {
        return UUID.nameUUIDFromBytes((parent + "/" + operation).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static boolean matches(String filter, String topic) {
        String[] wanted = filter.split("/", -1), actual = topic.split("/", -1);
        for (int i = 0; i < wanted.length; i++) {
            if (wanted[i].equals("#")) return true;
            if (i >= actual.length || (!wanted[i].equals("+") && !wanted[i].equals(actual[i]))) return false;
        }
        return wanted.length == actual.length;
    }
    public void close() {
        closed = true;
        try {
            if (client.isConnected()) {
                if (service != null) publish(prefix + "/status/" + service, Json.obj("servico", service, "online", false), true);
                client.disconnect(1000);
            }
        } catch (Exception ignored) { try { client.disconnectForcibly(0, 1000); } catch (Exception ignoredAgain) {} }
        recovery.shutdownNow();
        try { client.close(); } catch (Exception ignored) {}
    }
}
