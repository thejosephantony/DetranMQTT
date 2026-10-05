package br.ufs.detran;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();
    private Json() {}
    static ObjectNode obj(Object... pares) {
        ObjectNode node = MAPPER.createObjectNode();
        for (int i = 0; i < pares.length; i += 2) node.set((String) pares[i], MAPPER.valueToTree(pares[i + 1]));
        return node;
    }
    static ArrayNode array() { return MAPPER.createArrayNode(); }
    static ObjectNode parse(byte[] bytes) throws IOException {
        JsonNode node = MAPPER.readTree(bytes);
        if (node == null || !node.isObject()) throw new IOException("A mensagem deve ser um objeto JSON.");
        return (ObjectNode) node;
    }
    static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || (!value.isTextual() && !value.isNumber()) || value.asText().isBlank())
            throw new RegraException("DADOS_INVALIDOS", "Informe " + key + ".");
        return value.asText().trim();
    }
    static ObjectNode ok(JsonNode resultado) { return obj("ok", true, "resultado", resultado); }
    static JsonNode result(ObjectNode response) {
        if (!response.path("ok").asBoolean()) throw new RegraException(
                response.path("erro").path("codigo").asText("ERRO"),
                response.path("erro").path("mensagem").asText("Falha na operação."));
        return response.path("resultado");
    }
}
