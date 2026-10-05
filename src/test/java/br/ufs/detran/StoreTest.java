package br.ufs.detran;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreTest {
    @TempDir Path dir;
    @Test void reinicioPreservaRegistrosERespostaDaMesmaMensagem() throws Exception {
        Path arquivo = dir.resolve("estado.json"); Store antes = new Store(arquivo);
        var resposta = antes.transaction("mensagem-1", "cadastrar-ana", "cadastro", "SE", rows -> {
            var row = Json.obj("cpf", "52998224725", "nome", "Ana", "uf", "SE"); rows.set("52998224725", row); return row;
        });
        Store depois = new Store(arquivo);
        assertEquals("Ana", depois.records().path("52998224725").path("nome").asText());
        // JSON persistido é comparado como o contrato recebido pela rede, sem distinguir IntNode/LongNode.
        assertEquals(Json.MAPPER.readTree(resposta.toString()), depois.transaction("mensagem-1", "cadastrar-ana", "cadastro", "SE", rows -> {
            fail("Mensagem repetida não deve executar a operação novamente."); return Json.obj();
        }));
        assertEquals(1, depois.snapshot().path("version").asLong());
    }
    @Test void erroNaoSalvaMudancaParcial() throws Exception {
        Path arquivo = dir.resolve("estado.json"); Store store = new Store(arquivo);
        assertThrows(RegraException.class, () -> store.transaction("m-1", "x", "cadastro", "SE", rows -> {
            rows.set("parcial", Json.obj("nome", "Não salvar")); throw new RegraException("TESTE", "Falha");
        }));
        assertTrue(store.records().isEmpty()); assertFalse(Files.exists(arquivo));
        assertEquals(0, store.snapshot().path("version").asLong());
    }
    @Test void identificadorNaoPodeSerReutilizadoComOutroConteudo() throws Exception {
        Store store = new Store(dir.resolve("estado.json"));
        store.transaction("m-1", "a", "cadastro", "SE", rows -> Json.obj("nome", "Ana"));
        var error = assertThrows(RegraException.class, () -> store.transaction("m-1", "b", "cadastro", "SE", rows -> Json.obj("nome", "Bruno")));
        assertEquals("ID_REUTILIZADO", error.codigo);
    }
    @Test void consultasNaoExponhemObjetoMutavelDaPersistencia() throws Exception {
        Store store = new Store(dir.resolve("estado.json"));
        store.transaction("m", "a", "cadastro", "SE", rows -> { rows.set("a", Json.obj("nome", "Ana")); return Json.obj(); });
        store.records().removeAll(); store.snapshot().removeAll();
        assertEquals("Ana", store.records().path("a").path("nome").asText());
    }
    @Test void concorrenciaNaoPerdeRegistrosEDeduplicaReenvios() throws Exception {
        Store store = new Store(dir.resolve("estado.json"));
        var pool = Executors.newFixedThreadPool(4);
        var tarefas = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int i = 0; i < 24; i++) {
                String id = "m-" + (i % 12);
                tarefas.add(pool.submit(() -> {
                    try { store.transaction(id, id, "cadastro", "SE", rows -> { rows.set(id, Json.obj("nome", id)); return Json.obj("id", id); }); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }));
            }
            for (var tarefa : tarefas) tarefa.get(5, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(12, store.records().size()); assertEquals(12, store.snapshot().path("version").asLong());
        assertEquals(12, new Store(dir.resolve("estado.json")).records().size());
    }
}
