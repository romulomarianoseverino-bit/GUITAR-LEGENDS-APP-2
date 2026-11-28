#!/usr/bin/env python3
import os
import subprocess
import sys
import time
from pathlib import Path

PACKAGE = "com.aistudio.applet.kpfcic"
APK = "app/build/outputs/apk/release/app-release.apk"
LOG_PATH = Path(os.environ.get("RUNNER_TEMP", ".")) / "android-runtime-errors.log"

def run(args, check=True):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT)
    output = result.stdout or ""
    if output:
        print(output, end="" if output.endswith("\n") else "\n", flush=True)
    if check and result.returncode:
        raise RuntimeError(f"Comando falhou ({result.returncode}): {' '.join(args)}")
    return output.strip(), result.returncode

def collect_logcat():
    output, _ = run(["adb", "logcat", "-b", "all", "-d", "-t", "4000"], check=False)
    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    LOG_PATH.write_text(output + "\n", encoding="utf-8")
    return output

def main():
    run(["adb", "wait-for-device"])
    booted = False
    for _ in range(120):
        value, _ = run(["adb", "shell", "getprop", "sys.boot_completed"], check=False)
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
        _, code = run(["adb", "install", "--no-streaming", "-r", APK], check=False)
        if code == 0:
            installed = True
            break
        run(["adb", "devices", "-l"], check=False)
        time.sleep(5)
    if not installed:
        raise RuntimeError("Não foi possível instalar o APK no emulador.")

    run(["adb", "logcat", "-c"])
    run(["adb", "shell", "am", "force-stop", PACKAGE], check=False)
    run(["adb", "shell", "monkey", "-p", PACKAGE, "1"])
    time.sleep(30)

    pid, _ = run(["adb", "shell", "pidof", PACKAGE], check=False)
    log = collect_logcat()
    markers = ("FATAL EXCEPTION", "Fatal signal", "am_crash", "am_anr",
               "ANR in " + PACKAGE)
    detected = [marker for marker in markers if marker.lower() in log.lower()]

    print("=== Linhas relevantes do Android ===", flush=True)
    terms = ("FATAL EXCEPTION", "AndroidRuntime", "Fatal signal", "tombstone",
             "Process: " + PACKAGE, "am_crash", "am_anr", PACKAGE)
    matches = [line for line in log.splitlines()
               if any(term.lower() in line.lower() for term in terms)]
    print("\n".join(matches[-250:]), flush=True)

    if detected:
        raise RuntimeError("Falha/crash detectado: " + ", ".join(detected))
    if not pid:
        raise RuntimeError("O processo do aplicativo não permaneceu ativo após 30 segundos.")
    print(f"Processo ativo após 30 segundos: {pid}", flush=True)

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
