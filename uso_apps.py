#!/usr/bin/env python3
"""Lee el uso de apps del Fire TV (solo lectura: `adb shell dumpsys usagestats`) y escribe datos/uso_apps.json.

El launcher ordena la fila de apps por esos segundos. No modifica nada en el Fire TV, salvo con --empujar,
que copia el JSON a la carpeta de datos de la app (adb push, reversible borrando el archivo).

Uso:
  python uso_apps.py --ip <IP-del-Fire-TV>
  python uso_apps.py --ip <IP-del-Fire-TV> --empujar
"""
import argparse
import json
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path

RAIZ = Path(__file__).resolve().parent
FILA = re.compile(r'package=(\S+) totalTime="([\d:]+)"')


def a_segundos(t):
    p = [int(x) for x in t.split(":")]
    if len(p) == 2:
        return p[0] * 60 + p[1]
    return p[0] * 3600 + p[1] * 60 + p[2]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip", required=True, help="IP del Fire TV (adb por red)")
    ap.add_argument("--seccion", default="monthly", choices=["daily", "weekly", "monthly", "yearly"])
    ap.add_argument("--empujar", action="store_true")
    a = ap.parse_args()
    adb = ["adb", "-s", f"{a.ip}:5555"]
    out = subprocess.run(adb + ["shell", "dumpsys", "usagestats"], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=90).stdout
    if not out:
        sys.exit("adb no devolvió nada: ¿está conectado el Fire TV?")
    m = re.search(rf"In-memory {a.seccion} stats(.*?)(?=\n  In-memory |\Z)", out, re.S)
    seg = {}
    for p, t in FILA.findall(m.group(1) if m else out):
        s = a_segundos(t)
        if s > 0:
            seg[p] = seg.get(p, 0) + s
    seg = dict(sorted(seg.items(), key=lambda kv: -kv[1]))
    destino = RAIZ / "datos" / "uso_apps.json"
    destino.write_text(json.dumps({"generado": datetime.now().isoformat(timespec="seconds"), "seccion": a.seccion, "segundos": seg}, indent=2) + "\n", encoding="utf-8")
    print(f"{len(seg)} paquetes con uso; escrito {destino}")
    for p, s in list(seg.items())[:12]:
        print(f"  {s // 3600}h{s % 3600 // 60:02d}  {p}")
    if a.empujar:
        r = subprocess.run(adb + ["push", str(destino), "/sdcard/Android/data/co.davidvega.launcher/files/uso_apps.json"], capture_output=True, text=True)
        print(r.stdout.strip() or r.stderr.strip())


if __name__ == "__main__":
    main()
