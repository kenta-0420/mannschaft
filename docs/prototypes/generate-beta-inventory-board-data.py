#!/usr/bin/env python3
"""正本からローカル棚卸ボード用のJavaScriptデータを生成する。

外部サービスへ送信せず、feature-inventory.yaml と task-list.md の相対パスを
読み込んで beta-inventory-board-data.js を上書きする。B段階やGate前提工事は
正本に軸がないため、推測で補完しない。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INVENTORY_PATH = ROOT / "docs" / "inventory" / "feature-inventory.yaml"
TASK_LIST_PATH = ROOT / "docs" / "task-list.md"
OUTPUT_PATH = ROOT / "docs" / "prototypes" / "beta-inventory-board-data.js"
DECISIONS_PATH = ROOT / "docs" / "prototypes" / "beta-inventory-board-decisions.json"
GATE_PATH = ROOT / "docs" / "prototypes" / "beta-inventory-board-gate.json"


def git_commit_for(path: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "log", "-1", "--format=%H", "--", path.relative_to(ROOT).as_posix()],
            cwd=ROOT,
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip() or "未取得"
    except (OSError, subprocess.CalledProcessError):
        return "未取得"


def text(value: object, fallback: str = "未設定") -> str:
    if value is None or value == "":
        return fallback
    return str(value)


def parse_scalar(value: str):
    value = value.strip()
    if value in ("null", "~"):
        return None
    if value == "[]":
        return []
    if value == "{}":
        return {}
    if value.startswith("{") and value.endswith("}"):
        status = re.search(r"status:\s*([^,}]+)", value)
        evidence = re.search(r"evidence:\s*\[([^]]*)\]", value)
        return {
            **({"status": status.group(1).strip().strip("'\"")} if status else {}),
            "evidence": [] if not evidence or not evidence.group(1).strip() else [evidence.group(1).strip()],
        }
    return value.strip("'\"")


def parse_inventory(source: str) -> dict:
    """この台帳で使う限定的なYAML構造を標準ライブラリだけで読む。"""
    records = []
    current = None
    section = None
    for line in source.splitlines():
        match = re.match(r"^  - feature_key:\s*(.+)$", line)
        if match:
            if current is not None:
                records.append(current)
            current = {
                "feature_key": parse_scalar(match.group(1)),
                "design_docs": [],
                "implementation": {},
                "verification": {},
                "release": {},
                "blockers": [],
            }
            section = None
            continue
        if current is None:
            continue
        top = re.match(r"^ {4}([a-z0-9_]+): *(.*)$", line)
        if top:
            key, value = top.groups()
            section = key if value == "" else None
            if value != "":
                current[key] = parse_scalar(value)
            continue
        nested = re.match(r"^ {6}([a-z0-9_]+): *(.*)$", line)
        if nested and section in ("implementation", "verification", "release"):
            key, value = nested.groups()
            current[section][key] = parse_scalar(value)
            continue
        blocker = re.match(r"^      - (.*)$", line)
        if blocker and section == "blockers":
            current["blockers"].append(parse_scalar(blocker.group(1)))
        design_doc = re.match(r"^      - (.*)$", line)
        if design_doc and section == "design_docs":
            current["design_docs"].append(parse_scalar(design_doc.group(1)))
    if current is not None:
        records.append(current)
    return {"records": records}


def official_status(record: dict) -> str:
    unknown = "\u4e0d\u660e"
    broken = "\u4e0d\u5177\u5408\u3042\u308a"
    not_implemented = "\u672a\u5b9f\u88c5"
    partial = "\u90e8\u5206\u5b9f\u88c5"
    implemented = "\u5b9f\u88c5\u6e08"
    not_applicable = "\u5bfe\u8c61\u5916"
    implementation = record.get("implementation") or {}
    values = list(implementation.values())
    if record.get("blockers") or broken in values:
        return "blocked"
    if values and all(value == unknown for value in values):
        return "unknown"
    if any(value in (not_implemented, partial) for value in values):
        return "incomplete"
    verification = record.get("verification") or {}
    statuses = [value.get("status") if isinstance(value, dict) else value for value in verification.values()]
    if values and all(value in (implemented, not_applicable) for value in values):
        if any(status in ("\u672a\u5b9f\u884c", "\u5931\u6557\u4e2d", "\u672a\u4f5c\u6210") for status in statuses):
            return "verifying"
        if statuses and all(status in ("\u901a\u904e", not_applicable) for status in statuses) and any(status == "\u901a\u904e" for status in statuses):
            return "ready"
    return "unknown"


def split_table_row(line: str) -> list[str]:
    # task-listの表セルには通常パイプを含めない。外側の空セルだけ除く。
    cells = line.strip().split("|")
    if cells and cells[0].strip() == "":
        cells = cells[1:]
    if cells and cells[-1].strip() == "":
        cells = cells[:-1]
    return [cell.strip() for cell in cells]


def parse_campaigns(markdown: str) -> list[dict]:
    campaigns = []
    for line in markdown.splitlines():
        if not re.match(r"^\|\s*CMP(?:-|$)", line):
            continue
        cells = split_table_row(line)
        cells = (cells + [""] * 7)[:7]
        campaign_id, title, status, prerequisite, acceptance, evidence, refs = cells[:7]
        status_text = status.strip()
        status_key = normalize_campaign_status(status_text)
        feature_refs = re.findall(r"[A-Za-z][A-Za-z0-9_-]+", title + " " + acceptance)
        tags = campaign_tags(status_key)
        campaigns.append(
            {
                "id": campaign_id,
                "title": title,
                "status": status_key,
                "statusLabel": status_text or "未設定",
                "stage": "未設定",
                "priority": "未設定",
                "audiences": [],
                "featureKey": None,
                "updated": "未設定",
                "summary": "task-list.mdの正本表から生成。",
                "nextAction": acceptance or "未設定",
                "acceptance": [acceptance] if acceptance else [],
                "blocker": prerequisite or "未設定",
                "issues": [{"label": evidence or "未設定", "state": "unknown"}],
                "prs": [evidence or "未設定"],
                "ci": "正本に記載された証拠を確認してください。",
                "refs": [refs] if refs else [],
                "source": "docs/task-list.md",
                "sourceTokens": feature_refs,
                "tags": tags,
            }
        )
    return campaigns


def campaign_tags(status_key: str) -> list[str]:
    """task-list.mdの状態列を、フィルター用の進捗タグへ機械的に写像する。"""
    labels = {"done": "完了", "working": "進行中", "blocked": "停止中", "unknown": "未整理"}
    return [labels.get(status_key, "未整理")]


def normalize_campaign_status(status_text: str) -> str:
    """状態列の先頭語を、画面の4進捗へ機械的に正規化する。"""
    normalized = status_text.replace("**", "").strip()
    if normalized.startswith(("凍結", "停止")):
        return "blocked"
    if normalized.startswith("完了"):
        return "done"
    if normalized.startswith(("設計中", "実装中", "実装済", "検証待ち", "実機検証待ち", "実装完了", "実装・実機E2E完了", "型確立PR進行中", "一部完了")):
        return "working"
    return "unknown"


def feature_view(record: dict) -> dict:
    release = record.get("release") or {}
    implementation = record.get("implementation") or {}
    verification = record.get("verification") or {}
    blockers = record.get("blockers") or []
    status = official_status(record)
    status_labels = {
        "blocked": "不備あり",
        "unknown": "未棚卸",
        "incomplete": "実装未完",
        "verifying": "検証中",
        "ready": "β準備完了",
    }
    refs = record.get("design_docs") or []
    refs = [str(ref) for ref in refs]
    is_core = record.get("layer") == "\u80fd\u529b" and release.get("beta") == "\u30b3\u30a2"
    classification = "core" if is_core else "noncore" if record.get("layer") == "\u30c9\u30e1\u30a4\u30f3" else "未設定"
    return {
        "key": text(record.get("feature_key"), "未設定"),
        "title": text(record.get("name")),
        "stage": "未設定",
        "phase": text(release.get("beta")),
        "status": status,
        "statusLabel": status_labels[status],
        "statusSource": "implementation/blockersから機械導出",
        "priority": "未設定",
        "audiences": [],
        "summary": "feature-inventory.yamlの正本レコード。",
        "why": text(record.get("notes")),
        "acceptance": [],
        "blocker": " / ".join(str(blocker) for blocker in blockers) if blockers else "未設定",
        "refs": refs,
        "layer": text(record.get("layer")),
        "implementation": implementation,
        "verification": verification,
        "release": release,
        "blockers": blockers,
        "classification": classification,
        "publication": "未設定",
        "gate": "未設定",
        "source": "docs/inventory/feature-inventory.yaml",
    }


def build_data() -> dict:
    inventory_source = INVENTORY_PATH.read_text(encoding="utf-8")
    inventory = parse_inventory(inventory_source)
    records = inventory.get("records") or []
    task_list = TASK_LIST_PATH.read_text(encoding="utf-8")
    features = [feature_view(record) for record in records]
    campaigns = parse_campaigns(task_list)
    decisions = json.loads(DECISIONS_PATH.read_text(encoding="utf-8"))
    gate_overlay = json.loads(GATE_PATH.read_text(encoding="utf-8"))
    layer_counts = {}
    for record in records:
        layer = text(record.get("layer"))
        layer_counts[layer] = layer_counts.get(layer, 0) + 1
    core = [feature for feature in features if feature["classification"] == "core"]
    statuses = ("blocked", "unknown", "incomplete", "verifying", "ready")
    core_status_counts = {status: sum(feature["status"] == status for feature in core) for status in statuses}
    blockers_count = sum(bool(feature["blockers"]) for feature in features)
    raw_feature_count = len(re.findall(r"^  - feature_key:", inventory_source, flags=re.MULTILINE))
    raw_campaign_count = len(re.findall(r"^\|\s*CMP(?:-|\|)", task_list, flags=re.MULTILINE))
    actual = {"features": len(features), "campaigns": len(campaigns), "core": len(core), "noncore": sum(feature["classification"] == "noncore" for feature in features), "blockers": blockers_count, "coreStatus": core_status_counts}
    errors = []
    decision_features = decisions.get("features", {})
    allowed_stages = {"B0", "B1", "B2", "B3", "B4"}
    allowed_audiences = {"soccer", "alumni", "both"}
    allowed_priorities = {"must", "should", "could", "defer"}
    allowed_decision_statuses = {"proposed", "confirmed"}
    allowed_gate_statuses = {"done", "working", "blocked", "unknown"}
    if set(decision_features) != {feature["key"] for feature in features}:
        errors.append("Phase 2分類が43機能と完全一致しません")
    if any(
        decision.get("stage") not in allowed_stages
        or decision.get("audience") not in allowed_audiences
        or decision.get("priority") not in allowed_priorities
        or decision.get("decisionStatus", decisions.get("decisionStatusDefault")) not in allowed_decision_statuses
        or not decision.get("reason")
        for decision in decision_features.values()
    ):
        errors.append("Phase 2分類に許可値外または根拠なしの項目があります")
    if len(features) != raw_feature_count:
        errors.append(f"機能行の解析漏れ: raw={raw_feature_count} parsed={len(features)}")
    if len(campaigns) != raw_campaign_count:
        errors.append(f"CMP行の解析漏れ: raw={raw_campaign_count} parsed={len(campaigns)}")
    if len({feature["key"] for feature in features}) != len(features):
        errors.append("feature_keyが重複")
    if len({campaign["id"] for campaign in campaigns}) != len(campaigns):
        errors.append("CMP IDが重複")
    gate_items = gate_overlay.get("items") or []
    if not gate_items:
        errors.append("Gate overlayの項目がありません")
    if len({item.get("id") for item in gate_items}) != len(gate_items):
        errors.append("Gate IDが重複")
    for item in gate_items:
        if item.get("status") not in allowed_gate_statuses:
            errors.append(f"Gate statusの許可値外: {item.get('id')}")
        if item.get("decisionStatus") not in allowed_decision_statuses:
            errors.append(f"Gate decisionStatusの許可値外: {item.get('id')}")
        if not item.get("title") or not item.get("detail") or not item.get("sourceRefs"):
            errors.append(f"Gateの根拠または表示項目なし: {item.get('id')}")
        if item.get("status") != "unknown" and not item.get("evidence"):
            errors.append(f"Gateの判定にevidenceがありません: {item.get('id')}")
    if actual["core"] + actual["noncore"] != len(features):
        errors.append("Core/非Coreに分類できない機能あり")
    if sum(core_status_counts.values()) != len(core):
        errors.append("Core公式状態の集計不一致")
    if errors:
        raise SystemExit("正本検算不一致: " + " / ".join(errors))
    return {
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "sources": {
            "inventory": "docs/inventory/feature-inventory.yaml",
            "taskList": "docs/task-list.md",
            "decisions": "docs/prototypes/beta-inventory-board-decisions.json",
            "gate": "docs/prototypes/beta-inventory-board-gate.json",
            "inventoryCommit": git_commit_for(INVENTORY_PATH),
            "taskListCommit": git_commit_for(TASK_LIST_PATH),
            "inventorySha256": hashlib.sha256(INVENTORY_PATH.read_bytes()).hexdigest(),
            "taskListSha256": hashlib.sha256(TASK_LIST_PATH.read_bytes()).hexdigest(),
            "decisionsSha256": hashlib.sha256(DECISIONS_PATH.read_bytes()).hexdigest(),
            "gateSha256": hashlib.sha256(GATE_PATH.read_bytes()).hexdigest(),
        },
        "sourceCounts": {
            "features": len(features),
            "campaigns": len(campaigns),
            "layer": layer_counts,
            "blockers": blockers_count,
            "coreStatus": core_status_counts,
        },
        "verification": {
            "raw": {"features": raw_feature_count, "campaigns": raw_campaign_count},
            "parsed": actual,
            "passed": True,
        },
        "warnings": [
            "B0〜B4・対象者・優先度は正本とは分離したPhase 2A提案であり、確定値ではない。",
            "Core／非Coreはlayerとrelease.betaから機械導出。foundationは正本にないため未設定。",
            "Gate前提工事はbeta-inventory-board-gate.jsonの根拠付きoverlayから表示。未確認項目は公開候補に含めない。",
            "Issue／PR／CIはGitHub連携していないため、task-list.mdの証拠欄だけを表示。",
        ],
        "features": features,
        "decisions": decisions,
        "featureClassification": {},
        "featurePublication": {},
        "gateFoundation": gate_items,
        "campaigns": campaigns,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true", help="件数だけ表示して書き込まない")
    args = parser.parse_args()
    data = build_data()
    if args.dry_run:
        print(json.dumps(data["sourceCounts"], ensure_ascii=False))
        return
    payload = "window.BETA_INVENTORY_DATA = " + json.dumps(data, ensure_ascii=False, indent=2) + ";\n"
    OUTPUT_PATH.write_text(payload, encoding="utf-8")
    print(f"生成: {OUTPUT_PATH}")
    print(json.dumps(data["sourceCounts"], ensure_ascii=False))


if __name__ == "__main__":
    main()

