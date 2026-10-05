#!/usr/bin/env python3
"""Teste opcional fora do Docker: requer Java 17+ e um executável Mosquitto 2.x."""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import uuid

RAIZ = Path(__file__).resolve().parents[1]
PARSER = argparse.ArgumentParser(description=__doc__)
PARSER.add_argument("--mosquitto", default=shutil.which("mosquitto"))
PARSER.add_argument("--jar", type=Path, default=RAIZ / "target" / "detran.jar")
PARSER.add_argument("--artefatos", type=Path, default=RAIZ / "evidencias" / "integracao")


def executar(args):
    if not args.mosquitto or not Path(args.mosquitto).is_file():
        raise RuntimeError("Informe --mosquitto /caminho/para/mosquitto (ou use o teste via Docker no README).")
    if not args.jar.is_file():
        raise RuntimeError("JAR ausente. Execute mvn package ou informe --jar dist/detran.jar.")
    raiz = args.artefatos.resolve() / (time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6])
    raiz.mkdir(parents=True)
    dados = raiz / "dados"
    (dados / "broker").mkdir(parents=True)
    with socket.socket() as reserva:
        reserva.bind(("127.0.0.1", 0))
        porta = reserva.getsockname()[1]
    conf = raiz / "mosquitto.conf"
    conf.write_text(
        f"listener {porta} 127.0.0.1\nallow_anonymous true\npersistence true\n"
        f"persistence_location {(dados / 'broker').as_posix()}/\nautosave_interval 1\nlog_dest stdout\n"
        + ("user root\n" if hasattr(os, "geteuid") and os.geteuid() == 0 else ""), encoding="utf-8")
    ambiente = dict(os.environ, MQTT_BROKER=f"tcp://127.0.0.1:{porta}", MQTT_PREFIX="teste_" + uuid.uuid4().hex[:12])
    java = ["java", "-Xms32m", "-Xmx128m", "-Dfile.encoding=UTF-8", "-Duser.timezone=America/Fortaleza", "-jar", str(args.jar.resolve())]
    processos, arquivos = {}, []
    etapas = []

    def parar(nome):
        processo = processos.pop(nome, None)
        if processo is None:
            return
        if processo.poll() is None:
            processo.terminate()
            try:
                processo.wait(timeout=12)
            except subprocess.TimeoutExpired:
                processo.kill()
                processo.wait(timeout=5)

    def iniciar(nome):
        log = (raiz / f"{nome}.log").open("ab")
        arquivos.append(log)
        if nome == "broker":
            comando = [str(Path(args.mosquitto).resolve()), "-c", str(conf)]
            env = ambiente
        else:
            comando = java + ["servico", nome]
            env = dict(ambiente, DATA_DIR=str(dados / nome))
        processos[nome] = subprocess.Popen(comando, env=env, cwd=RAIZ, stdout=log, stderr=subprocess.STDOUT)
        if nome == "broker":
            limite = time.monotonic() + 8
            while True:
                if processos[nome].poll() is not None:
                    raise RuntimeError("Broker encerrou; consulte broker.log.")
                try:
                    with socket.create_connection(("127.0.0.1", porta), timeout=0.3):
                        break
                except OSError:
                    if time.monotonic() > limite:
                        raise RuntimeError("Broker não abriu a porta.")
                    time.sleep(0.1)

    def cli(nome, *comando):
        resultado = subprocess.run(java + list(comando), cwd=RAIZ, env=ambiente, capture_output=True, text=True, encoding="utf-8", timeout=180)
        (raiz / (nome + ".log")).write_text(resultado.stdout + resultado.stderr, encoding="utf-8")
        if resultado.returncode != 0:
            raise RuntimeError(nome + " falhou:\n" + (resultado.stdout + resultado.stderr)[-3000:])
        return resultado.stdout

    def resumo(nome):
        return json.loads(cli(nome, "cliente", "SE", "resumo"))["resultado"]

    def conferir(valor, esperado):
        obtido = tuple(valor[chave] for chave in ("condutores", "veiculos", "multas"))
        if obtido != esperado:
            raise RuntimeError(f"Totais incorretos: {obtido}; esperado: {esperado}")

    def etapa(texto):
        etapas.append(texto)
        print("[OK] " + texto, flush=True)

    try:
        iniciar("broker")
        for nome in ("cadastro", "veiculos", "multas", "denatran"):
            iniciar(nome)
        saida = cli("demonstracao-1", "verificar")
        if "SUCESSO: 10/10" not in saida:
            raise RuntimeError("Demonstração não confirmou as dez funções.")
        conferir(resumo("resumo-1"), (6, 6, 8))
        etapa("Dez operações, duas UFs, histórico de multa, filtros, ranking, reenvios e entradas inválidas")

        parar("broker")
        iniciar("broker")
        cli("auditoria-reconexao", "auditar")
        conferir(resumo("resumo-reconexao"), (6, 6, 8))
        etapa("Reconexão automática dos quatro serviços após reinício do broker")

        for nome in ("denatran", "multas", "veiculos", "cadastro", "broker"):
            parar(nome)
        iniciar("broker")
        for nome in ("cadastro", "veiculos", "multas", "denatran"):
            iniciar(nome)
        cli("auditoria-reinicio", "auditar")
        conferir(resumo("resumo-reinicio"), (6, 6, 8))
        etapa("Persistência após reinício completo do broker e dos serviços")

        parar("denatran")
        shutil.rmtree(dados / "denatran")  # somente dados isolados criados por este teste
        iniciar("denatran")
        cli("auditoria-reconstrucao", "auditar")
        conferir(resumo("resumo-reconstrucao"), (6, 6, 8))
        etapa("Reconstrução do DENATRAN vazio a partir de snapshots MQTT retidos")

        cli("demonstracao-2", "verificar")
        conferir(resumo("resumo-2"), (12, 12, 16))
        etapa("Demonstração repetida sem apagar os registros anteriores")

        cadastros = []
        for uf in ("AC", "DF"):
            cpf = cpf_ficticio(int(uuid.uuid4().hex[:8], 16) % 999_999_999)
            processo = subprocess.Popen(java + ["cliente", uf, "cadastrar-condutor", "cpf=" + cpf, "nome=Cliente paralelo " + uf],
                                        env=ambiente, cwd=RAIZ, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8")
            cadastros.append((uf, processo))
        for uf, processo in cadastros:
            texto, _ = processo.communicate(timeout=90)
            (raiz / ("cliente-paralelo-" + uf + ".log")).write_text(texto, encoding="utf-8")
            if processo.returncode != 0 or not json.loads(texto).get("ok"):
                raise RuntimeError("Cliente paralelo falhou: " + texto)
        cli("auditoria-final", "auditar")
        conferir(resumo("resumo-final"), (14, 12, 16))
        etapa("Dois clientes simultâneos, com DETRANs de AC e DF")

        relatorio = {"ok": True, "etapas": etapas, "resumoFinal": resumo("resumo-relatorio"), "diretorio": str(raiz)}
        (raiz / "resultado.json").write_text(json.dumps(relatorio, ensure_ascii=False, indent=2), encoding="utf-8")
        print("INTEGRAÇÃO OK. Logs: " + str(raiz), flush=True)
    finally:
        for nome in list(processos):
            parar(nome)
        for arquivo in arquivos:
            arquivo.close()


def cpf_ficticio(base):
    corpo = f"{base:09d}"
    for tamanho in (9, 10):
        digito = 11 - sum(int(corpo[i]) * (tamanho + 1 - i) for i in range(tamanho)) % 11
        corpo += str(0 if digito >= 10 else digito)
    return corpo


if __name__ == "__main__":
    try:
        executar(PARSER.parse_args())
    except Exception as erro:
        print("INTEGRAÇÃO FALHOU: " + str(erro), file=sys.stderr)
        sys.exit(1)
