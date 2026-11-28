#!/usr/bin/env python3
import os
import subprocess
import sys
import time
from pathlib import Path

PACKAGE = "com.aistudio.applet.kpfcic"
ACTIVITY = "com.example.MainActivity"
APK = "app/build/outputs/apk/release/app-release.apk"
LOG_PATH = Path(os.environ.get("RUNNER_TEMP", ".")) / "android-runtime-errors.log"


def run(args, check=True, timeout=90):
    try:
        result = subprocess.run(
            args, text=True, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, timeout=timeout
        )
    except subprocess.TimeoutExpired as exc:
        output = exc.stdout or ""
        if isinstance(output, bytes):
            output = output.decode("utf-8", errors="replace")
        if output:
            print(output, end="" if output.endswith("\n") else "\n", flush=True)
        if check:
            raise RuntimeError(
                f"Comando excedeu {timeout}s: {' '.join(args)}"
            )
        return output.strip(), 124

    output = result.stdout or ""
    if output:
        print(output, end="" if output.endswith("\n") else "\n", flush=True)
    if check and result.returncode:
        raise RuntimeError(
            f"Comando falhou ({result.returncode}): {' '.join(args)}"
        )
    return output.strip(), result.returncode


def collect_logcat():
    output, _ = run(
        ["adb", "logcat", "-b", "all", "-d", "-t", "12000"],
        check=False, timeout=60
    )
    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    LOG_PATH.write_text(output + "\n", encoding="utf-8")
    return output


def relevant_lines(log):
    terms = (
        PACKAGE, "FATAL EXCEPTION", "Fatal signal",
        "am_crash", "am_anr", "ANR in "
    )
    return [
        line for line in log.splitlines()
        if any(term.lower() in line.lower() for term in terms)
    ]


def app_crashes(log):
    lines = log.splitlines()
    failures = []
    for index, line in enumerate(lines):
        lower = line.lower()
        if "fatal exception" in lower:
            context = "\n".join(lines[index:index + 18])
            if f"Process: {PACKAGE}".lower() in context.lower():
                failures.append(line)
        elif "fatal signal" in lower:
            # Native crash records identify the crashing process in the same
            # line or in the immediately following log context.
            context = "\n".join(lines[max(0, index - 2):index + 5])
            if PACKAGE.lower() in context.lower():
                failures.append(line)
        elif "am_crash" in lower or "am_anr" in lower or "anr in " in lower:
            if PACKAGE.lower() in lower:
                failures.append(line)
    return failures


def main():
    run(["adb", "wait-for-device"], timeout=120)
    booted = False
    for _ in range(120):
        value, _ = run(
            ["adb", "shell", "getprop", "sys.boot_completed"],
            check=False, timeout=20
        )
        if value.strip() == "1":
            booted = True
            break
        time.sleep(2)
    if not booted:
        run(["adb", "devices", "-l"], check=False)
        raise RuntimeError("O emulador não concluiu a inicialização.")

    if not Path(APK).is_file():
        raise RuntimeError(f"APK não encontrado: {APK}")

    installed = False
    for attempt in range(1, 4):
        print(f"Tentativa de instalação {attempt}/3", flush=True)
        _, code = run(
            ["adb", "install", "--no-streaming", "-r", APK],
            check=False, timeout=180
        )
        if code == 0:
            installed = True
            break
        run(["adb", "devices", "-l"], check=False)
        time.sleep(5)
    if not installed:
        raise RuntimeError("Não foi possível instalar o APK no emulador.")

    run(["adb", "logcat", "-c"], timeout=30)
    run(["adb", "shell", "am", "force-stop", PACKAGE],
        check=False, timeout=30)

    # Do not use "am start -W": on a heavily loaded emulator it can time out
    # even after Android has launched the app. Start asynchronously and
    # independently verify that this app's process stays alive.
    start_output, start_code = run([
        "adb", "shell", "am", "start", "-n",
        f"{PACKAGE}/{ACTIVITY}"
    ], check=False, timeout=90)

    if start_code != 0 or "Error type 3" in start_output:
        log = collect_logcat()
        print("=== Registros relevantes após falha ao iniciar ===", flush=True)
        print("\n".join(relevant_lines(log)[-300:]), flush=True)
        raise RuntimeError(
            "O Android não conseguiu enviar o comando de abertura "
            "para a atividade principal."
        )

    pid = ""
    for _ in range(60):
        pid, _ = run(
            ["adb", "shell", "pidof", PACKAGE],
            check=False, timeout=20
        )
        if pid:
            break
        time.sleep(2)

    if not pid:
        log = collect_logcat()
        print("=== Registros relevantes sem processo ativo ===", flush=True)
        print("\n".join(relevant_lines(log)[-300:]), flush=True)
        raise RuntimeError(
            "O Android recebeu o comando de abertura, mas o processo "
            "do Guitar Legends não apareceu."
        )

    print(f"Processo do Guitar Legends iniciado: {pid}", flush=True)
    time.sleep(30)

    final_pid, _ = run(
        ["adb", "shell", "pidof", PACKAGE],
        check=False, timeout=20
    )
    log = collect_logcat()

    print("=== Registros relevantes do aplicativo ===", flush=True)
    print("\n".join(relevant_lines(log)[-300:]), flush=True)

    detected = app_crashes(log)
    if detected:
        raise RuntimeError(
            "Falha do Guitar Legends detectada: " + " | ".join(detected)
        )
    if not final_pid:
        raise RuntimeError(
            "O processo do Guitar Legends iniciou, mas não permaneceu "
            "ativo durante os 30 segundos de observação."
        )

    print(
        f"Processo do Guitar Legends ativo após 30 segundos: {final_pid}",
        flush=True
    )


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"::error::{exc}", flush=True)
        try:
            collect_logcat()
        except Exception as log_exc:
            print(f"Não foi possível salvar o logcat: {log_exc}", flush=True)
        sys.exit(1)
