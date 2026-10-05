package br.ufs.detran;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Scanner;

final class Menu {
    static void executar(String ufInicial) throws Exception {
        String uf = Validacao.uf(ufInicial);
        Scanner entrada = new Scanner(System.in);
        try (Cliente cliente = new Cliente()) {
            while (true) {
                System.out.println("\nDETRAN " + uf + " | consultas nacionais do DENATRAN");
                System.out.println("1 Emplacar veículo          2 Calcular IPVA");
                System.out.println("3 Transferir proprietário   4 Cadastrar condutor");
                System.out.println("5 Lançar multa              6 Veículos por ano");
                System.out.println("7 Multas do veículo/ano     8 Multas do condutor/ano");
                System.out.println("9 Multas por ano           10 Top 5 por pontos");
                System.out.println("11 Trocar UF               12 Resumo nacional");
                System.out.println("0 Sair");
                String escolha = ler(entrada, "Opção");
                if (escolha == null || escolha.equals("0")) return;
                try {
                    String operacao; ObjectNode dados = Json.obj();
                    switch (escolha) {
                        case "1" -> {
                            operacao = "emplacar";
                            campo(entrada, dados, "placa", "Placa"); campo(entrada, dados, "modelo", "Modelo");
                            campo(entrada, dados, "valor", "Valor (ex.: 50000.00)"); campo(entrada, dados, "cpf", "CPF do proprietário");
                            String ano = ler(entrada, "Ano de emplacamento (Enter = ano atual)");
                            if (ano == null) return;
                            if (!ano.isBlank()) dados.put("ano", ano);
                        }
                        case "2" -> { operacao = "ipva"; campo(entrada, dados, "placa", "Placa"); }
                        case "3" -> {
                            operacao = "transferir"; campo(entrada, dados, "placa", "Placa");
                            campo(entrada, dados, "novoCpf", "CPF do novo proprietário");
                        }
                        case "4" -> {
                            operacao = "cadastrar-condutor"; campo(entrada, dados, "cpf", "CPF"); campo(entrada, dados, "nome", "Nome");
                        }
                        case "5" -> {
                            operacao = "lancar-multa"; campo(entrada, dados, "ano", "Ano da multa");
                            campo(entrada, dados, "descricao", "Descrição"); campo(entrada, dados, "pontuacao", "Pontuação");
                            campo(entrada, dados, "placa", "Placa");
                        }
                        case "6" -> { operacao = "veiculos-ano"; campo(entrada, dados, "ano", "Ano"); }
                        case "7" -> {
                            operacao = "multas-veiculo"; campo(entrada, dados, "placa", "Placa"); campo(entrada, dados, "ano", "Ano");
                        }
                        case "8" -> {
                            operacao = "multas-condutor"; campo(entrada, dados, "cpf", "CPF"); campo(entrada, dados, "ano", "Ano");
                        }
                        case "9" -> { operacao = "multas-ano"; campo(entrada, dados, "ano", "Ano"); }
                        case "10" -> operacao = "top5";
                        case "11" -> { uf = Validacao.uf(ler(entrada, "Nova UF")); continue; }
                        case "12" -> operacao = "resumo";
                        default -> { System.out.println("Opção inválida."); continue; }
                    }
                    System.out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(cliente.executar(uf, operacao, dados)));
                } catch (Exception e) { System.err.println("Erro: " + e.getMessage()); }
            }
        }
    }
    private static String ler(Scanner entrada, String prompt) {
        System.out.print(prompt + ": ");
        return entrada.hasNextLine() ? entrada.nextLine().trim() : null;
    }
    private static void campo(Scanner entrada, ObjectNode dados, String chave, String prompt) {
        String valor = ler(entrada, prompt);
        if (valor == null) throw new RegraException("ENTRADA_ENCERRADA", "Entrada encerrada.");
        dados.put(chave, valor);
    }
}
