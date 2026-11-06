#!/bin/sh
# Az összes mérés egymás után (a GPU egyszerre egy feladatot visel). Napló: results/run_all.log
cd "$(dirname "$0")"
export PYTHONIOENCODING=utf8
DOC=../docproc/.venv/Scripts/python
VOICE=../voice/ai_env/Scripts/python.exe
for m in medgemma:latest medgemma:27b; do $DOC lab_eval.py --model $m; done
$VOICE findings_eval.py medgemma:latest medgemma:27b
$VOICE review_eval.py medgemma:latest medgemma:27b
$DOC history_eval.py medgemma:latest medgemma:27b
$DOC clinical_eval.py medgemma:latest qwen3.5:9b medgemma:27b
echo KÉSZ
