package br.ufs.detran;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

final class Validacao {
    static final List<String> UFS = List.of("AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA", "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");
    private Validacao() {}
    static String uf(String value) {
        String uf = value.toUpperCase(Locale.ROOT);
        if (!UFS.contains(uf)) throw new RegraException("UF_INVALIDA", "UF inválida: " + value);
        return uf;
    }
    static String cpf(String value) {
        String cpf = value.replaceAll("[.\\-\\s]", "");
        if (!cpf.matches("[0-9]{11}") || cpf.chars().distinct().count() == 1)
            throw new RegraException("CPF_INVALIDO", "CPF deve possuir 11 dígitos e verificadores válidos.");
        for (int size = 9; size <= 10; size++) {
            int sum = 0;
            for (int i = 0; i < size; i++) sum += (cpf.charAt(i) - '0') * (size + 1 - i);
            int digit = 11 - sum % 11;
            if (digit >= 10) digit = 0;
            if (digit != cpf.charAt(size) - '0') throw new RegraException("CPF_INVALIDO", "Dígitos verificadores do CPF inválidos.");
        }
        return cpf;
    }
    static String placa(String value) {
        String placa = value.toUpperCase(Locale.ROOT).replace("-", "").trim();
        if (!placa.matches("[A-Z]{3}[0-9]{4}|[A-Z]{3}[0-9][A-Z][0-9]{2}"))
            throw new RegraException("PLACA_INVALIDA", "Use placa AAA1234 ou AAA1A23.");
        return placa;
    }
    static int ano(String value) {
        int ano = numero(value, "ano");
        if (ano < 1900 || ano > 2100) throw new RegraException("ANO_INVALIDO", "Ano deve estar entre 1900 e 2100.");
        return ano;
    }
    static int numero(String value, String campo) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new RegraException("DADOS_INVALIDOS", campo + " deve ser inteiro."); }
    }
    static int pontos(String value) {
        int pontos = numero(value, "pontuacao");
        if (pontos <= 0) throw new RegraException("PONTUACAO_INVALIDA", "Pontuação deve ser positiva.");
        return pontos;
    }
    static BigDecimal valor(String value) {
        String normalized = value.replace(',', '.');
        if (!normalized.matches("[0-9]+(\\.[0-9]{1,2})?"))
            throw new RegraException("VALOR_INVALIDO", "Valor deve ser positivo, com até duas casas decimais e sem separador de milhar.");
        BigDecimal valor = new BigDecimal(normalized).setScale(2);
        if (valor.signum() <= 0) throw new RegraException("VALOR_INVALIDO", "Valor deve ser positivo.");
        return valor;
    }
    static BigDecimal ipva(BigDecimal valor) { return valor.multiply(new BigDecimal("0.02")).setScale(2, RoundingMode.HALF_UP); }
}
