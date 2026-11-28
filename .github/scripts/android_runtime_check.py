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


def run(args, check=True):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT)
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
        ["adb", "logcat", "-b", "all", "-d", "-t", "8000"],
        check=False
    )
    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    LOG_PATH.write_text(output + "\n", encoding="utf-8")
    return output


def relevant_lines(log):
    terms = (
        "FATAL EXCEPTION", "AndroidRuntime", "Fatal signal", "tombstone",
        "Process: " + PACKAGE, "am_crash", "am_anr", PACKAGE,
        "ANR in ", "ActivityTaskManager"
    )
    return [
        line for line in log.splitlines()
        if any(term.lower() in line.lower() for term in terms)
    ]


def main():
    run(["adb", "wait-for-device"])
    booted = False
    for _ in range(120):
        value, _ = run(
            ["adb", "shell", "getprop", "sys.boot_completed"],
            check=False
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
            check=False
        )
        if code == 0:
            installed = True
            break
        run(["adb", "devices", "-l"], check=False)
        time.sleep(5)
    if not installed:
        raise RuntimeError("Não foi possível instalar o APK no emulador.")

    run(["adb", "logcat", "-c"])
    run(["adb", "shell", "am", "force-stop", PACKAGE], check=False)

    # Start the known launcher activity directly. The Monkey command can itself
    # stall when Android system services are overloaded, producing a false
    # app-start failure without actually launching the application.
    start_output, start_code = run([
        "adb", "shell", "am", "start", "-W", "-n",
        f"{PACKAGE}/{ACTIVITY}"
    ], check=False)
    if start_code != 0 or "Status: ok" not in start_output:
        log = collect_logcat()
        print("=== Registros relevantes após falha ao iniciar ===", flush=True)
        print("\n".join(relevant_lines(log)[-300:]), flush=True)
        raise RuntimeError(
            "O Android não confirmou a abertura da tela principal "
            "pela atividade inicial do aplicativo."
        )

    pid = ""
    for _ in range(15):
        pid, _ = run(
            ["adb", "shell", "pidof", PACKAGE],
            check=False
        )
        if pid:
            break
        time.sleep(2)

    time.sleep(30)
    final_pid, _ = run(
        ["adb", "shell", "pidof", PACKAGE],
        check=False
    )
    log = collect_logcat()

    print("=== Linhas relevantes do Android ===", flush=True)
    print("\n".join(relevant_lines(log)[-300:]), flush=True)

    app_markers = (
        "FATAL EXCEPTION",
        "Fatal signal",
        "am_crash",
        "am_anr",
        "ANR in " + PACKAGE
    )
    detected = [
        marker for marker in app_markers
        if marker.lower() in log.lower()
    ]
    if detected:
        raise RuntimeError(
            "Falha/crash do aplicativo detectado: " + ", ".join(detected)
        )
    if not final_pid:
        raise RuntimeError(
            "A atividade foi chamada, mas o processo do aplicativo "
            "não permaneceu ativo após a janela de validação."
        )

    print(
        f"Processo ativo após a abertura e 30 segundos: {final_pid}",
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
