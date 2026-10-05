package br.ufs.detran;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ValidacaoTest {
    @Test void cpfComMascaraEVerificadores() {
        assertEquals("52998224725", Validacao.cpf("529.982.247-25"));
        assertEquals("52998224725", Demo.cpf(529982247));
    }
    @Test void cpfInvalidoOuRepetido() {
        for (String cpf : new String[]{"11111111111", "00000000000", "52998224724", "123", "abcdefghijk"})
            assertEquals("CPF_INVALIDO", assertThrows(RegraException.class, () -> Validacao.cpf(cpf)).codigo);
    }
    @Test void placaAntigaEMercosul() {
        assertEquals("ABC1234", Validacao.placa("abc-1234"));
        assertEquals("ABC1D23", Validacao.placa("abc1d23"));
        for (String placa : new String[]{"AB12345", "ABC12D3", "ABC1D234", "1234567"})
            assertThrows(RegraException.class, () -> Validacao.placa(placa));
    }
    @Test void dinheiroNaoUsaPontoFlutuante() {
        assertEquals(new BigDecimal("1000.00"), Validacao.ipva(Validacao.valor("50000.00")));
        assertEquals(new BigDecimal("246.91"), Validacao.ipva(Validacao.valor("12345,67")));
        assertEquals(new BigDecimal("0.01"), Validacao.ipva(Validacao.valor("0.25")));
        assertEquals(new BigDecimal("0.00"), Validacao.ipva(Validacao.valor("0.24")));
    }
    @Test void valorDeveSerPositivoComAteDoisDecimais() {
        for (String valor : new String[]{"0", "-1", "1.234", "50.000,00", "NaN", "Infinity"})
            assertEquals("VALOR_INVALIDO", assertThrows(RegraException.class, () -> Validacao.valor(valor)).codigo);
    }
    @Test void anoEPontuacao() {
        assertEquals(2026, Validacao.ano("2026"));
        assertEquals(7, Validacao.pontos("7"));
        assertThrows(RegraException.class, () -> Validacao.ano("1899"));
        assertThrows(RegraException.class, () -> Validacao.ano("2101"));
        assertThrows(RegraException.class, () -> Validacao.ano("2026.5"));
        assertThrows(RegraException.class, () -> Validacao.pontos("0"));
        assertThrows(RegraException.class, () -> Validacao.pontos("-5"));
    }
    @Test void todasAsUfsEDistritoFederal() {
        assertEquals(27, Validacao.UFS.size());
        assertEquals("DF", Validacao.uf("df"));
        assertThrows(RegraException.class, () -> Validacao.uf("ZZ"));
    }
}
