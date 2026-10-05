package br.ufs.detran;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Function;

final class Store {
    private final Path arquivo;
    private ObjectNode state;
    Store(Path arquivo) throws IOException {
        this.arquivo = arquivo;
        Files.createDirectories(arquivo.toAbsolutePath().getParent());
        state = Files.exists(arquivo) ? Json.parse(Files.readAllBytes(arquivo))
                : Json.obj("schema", 1, "version", 0, "records", Json.obj(), "processed", Json.obj());
        if (!state.path("records").isObject() || !state.path("processed").isObject() || state.path("schema").asInt() != 1)
            throw new IOException("Arquivo de dados inválido: " + arquivo);
    }
    synchronized ObjectNode snapshot() { return state.deepCopy(); }
    synchronized ObjectNode records() { return ((ObjectNode) state.get("records")).deepCopy(); }
    synchronized ObjectNode transaction(String id, String assinatura, String servico, String uf,
            Function<ObjectNode, ObjectNode> operation) throws IOException {
        var previous = state.path("processed").get(id);
        if (previous != null) {
            if (!previous.path("assinatura").asText().equals(assinatura))
                throw new RegraException("ID_REUTILIZADO", "O identificador já foi usado com outros dados.");
            return ((ObjectNode) previous.get("resposta")).deepCopy();
        }
        ObjectNode next = state.deepCopy();
        ObjectNode result = operation.apply((ObjectNode) next.get("records"));
        long version = state.path("version").asLong() + 1;
        next.put("version", version);
        ObjectNode response = Json.ok(result);
        response.set("meta", Json.obj("servico", servico, "uf", uf, "versao", version));
        ((ObjectNode) next.get("processed")).set(id, Json.obj("assinatura", assinatura, "resposta", response.deepCopy()));
        save(next);
        state = next;
        notifyAll();
        return response.deepCopy();
    }
    synchronized void apply(Function<ObjectNode, Boolean> update) throws IOException {
        ObjectNode next = state.deepCopy();
        if (!update.apply((ObjectNode) next.get("records"))) return;
        next.put("version", state.path("version").asLong() + 1);
        save(next); state = next; notifyAll();
    }
    private void save(ObjectNode data) throws IOException {
        Path tmp = Files.createTempFile(arquivo.toAbsolutePath().getParent(), ".gravando-", ".json");
        try {
            byte[] bytes = Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
            try (FileChannel file = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            try { Files.move(tmp, arquivo, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, arquivo, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
}
