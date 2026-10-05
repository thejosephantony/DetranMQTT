package br.ufs.detran;

final class RegraException extends RuntimeException {
    final String codigo;
    RegraException(String codigo, String mensagem) { super(mensagem); this.codigo = codigo; }
}
