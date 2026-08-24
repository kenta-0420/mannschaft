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
import os
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
GITHUB_PATH = ROOT / "docs" / "prototypes" / "beta-inventory-board-github.json"
GITHUB_STATUS_PATH = ROOT / "docs" / "prototypes" / "beta-inventory-board-github-status.json"

# 複合親は、独立した受入条件と公開時期を持つ能力だけを表示単位へ展開する。
# 親の実装・Gate証拠は子へコピーするが、子自身の実測とは扱わない。
CAPABILITY_SPLITS = {
    "team-create": [("create", "チーム作成"), ("view", "チーム閲覧")],
    "team-invite": [("invite", "チーム招待"), ("join", "チーム参加")],
    "team-admin": [("member-view", "チームメンバー閲覧"), ("member-manage", "チームメンバー管理"), ("permissions", "チーム権限管理")],
    "team-modules": [("module-view", "チーム機能閲覧"), ("module-manage", "チーム機能管理")],
    "organization-members": [("member-view", "組織メンバー閲覧"), ("member-manage", "組織メンバー管理・権限")],
    "village-join": [("village-view", "村閲覧"), ("village-join", "村参加")],
    "village-members": [("member-view", "村の構成員閲覧"), ("member-manage", "村の構成員管理")],
    "village-events": [("schedule-create", "予定作成"), ("schedule-view-manage", "予定閲覧・管理"), ("attendance-request", "出欠募集"), ("attendance-response", "出欠回答"), ("attendance-summary", "出欠集計"), ("calendar-view", "統合カレンダー閲覧"), ("calendar-sharing-level", "予定の公開範囲"), ("calendar-visibility-boundary", "カレンダー可視性境界")],
    "dashboard": [("personal-view", "個人ダッシュボード閲覧")],
    "survey": [("create", "アンケート作成"), ("publish", "アンケート公開"), ("response", "アンケート回答"), ("results", "アンケート結果")],
    "account-settings": [("settings", "設定"), ("withdrawal", "退会")],
    "auth": [("login", "ログイン"), ("two-factor", "2FA")],
    "organization-manage": [("organization-create", "組織作成"), ("organization-admin", "組織管理")],
    "notification-inbox": [("notification-delivery", "通知配信"), ("inbox", "受信箱")],
    "pointcard": [("wallet", "ウォレット"), ("points", "ポイント")],
    "tournament": [("tournament-management", "大会運営"), ("match-record", "試合記録")],
    "todo-memo": [("todo-create", "TODO作成"), ("todo-share", "TODO共有"), ("memo-quick-create", "ポイっとメモ作成"), ("memo-view", "ポイっとメモ閲覧・所有者境界")],
    "timeline": [("post", "タイムライン投稿"), ("view", "タイムライン閲覧"), ("sharing", "タイムライン共有範囲")],
    "corkboard": [("bulletin", "掲示板"), ("corkboard", "コルクボード")],
    "shift": [("shift", "シフト"), ("shift-budget", "シフト予算")],
    "billing-payment": [("billing", "請求"), ("payment", "決済"), ("membership-fee", "会費")],
    "facility": [("equipment", "備品"), ("facility", "施設"), ("venue", "会場"), ("parking", "駐車場")],
    "property-repairplan": [("property", "不動産"), ("repairplan", "修繕計画")],
    "weather-health": [("weather", "気象"), ("health", "健康")],
    "skill-resume": [("skill", "スキル"), ("resume", "履歴書")],
    "succession-proxy": [("succession", "事業承継"), ("proxy-vote", "代理投票")],
    "translation-search": [("translation", "翻訳"), ("search", "検索"), ("analytics", "分析")],
    "promotion": [("advertising", "広告"), ("promotion", "販促"), ("signage", "サイネージ")],
    "workflow-forms": [("workflow", "ワークフロー"), ("forms", "フォーム")],
    "family-care": [("school", "学校"), ("family", "家族"), ("safety-watch", "見守り")],
    "moderation-incident": [("moderation", "モデレーション"), ("incident", "インシデント")],
    "webhook-sync": [("webhook", "Webhook"), ("external-sync", "外部同期"), ("line-link", "LINE連携")],
    "gamification": [("gamification", "ゲーミフィケーション"), ("supporter", "サポーター")],
}

B0_PLAN_PATH = ROOT / "docs" / "prototypes" / "beta-inventory-board-b0-alicization.json"
B0_COVERAGE_PATH = ROOT / "docs" / "prototypes" / "beta-inventory-board-b0-coverage.json"
B0_LOCAL_DIR = ROOT / "docs" / "prototypes" / ".b0-local"
B0_ALICIZATION_PLAN = json.loads(B0_PLAN_PATH.read_text(encoding="utf-8"))
B0_COVERAGE = json.loads(B0_COVERAGE_PATH.read_text(encoding="utf-8"))
B0_RUN_OVERLAY = None
if os.environ.get("B0_INCLUDE_LOCAL_OVERLAY") == "true" and B0_LOCAL_DIR.exists():
    run_files = sorted([*B0_LOCAL_DIR.glob("run-*.json"), *B0_LOCAL_DIR.glob("blocked-*.json")], key=lambda item: item.stat().st_mtime, reverse=True)
    if run_files:
        B0_RUN_OVERLAY = json.loads(run_files[0].read_text(encoding="utf-8"))

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
        github_refs = sorted({number for value in re.findall(r"(?<![A-Za-z0-9])#(\d+)", line) if (number := int(value)) >= 100})
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
                "githubRefs": github_refs,
                "github": [],
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


def capability_views(record: dict) -> list[dict]:
    """親レコードを能力カードへ展開する。分割対象外は親自身を1カードにする。"""
    parent = feature_view(record)
    splits = CAPABILITY_SPLITS.get(parent["key"], [(parent["key"], parent["title"])])
    result = []
    for suffix, title in splits:
        child = {**parent}
        child["key"] = f'{parent["key"]}-{suffix}' if len(splits) > 1 else parent["key"]
        child["title"] = title
        child["parentFeatureKey"] = parent["key"]
        child["gateGroup"] = parent["release"].get("gate_key")
        child["isCapability"] = len(splits) > 1
        child["statusSource"] = f'{parent["key"]}由来・子能力未実測'
        child["evidenceSource"] = "親feature_keyの実装/Gate証拠を継承（子能力の独自証拠ではない）"
        result.append(child)
    return result


def build_data() -> dict:
    inventory_source = INVENTORY_PATH.read_text(encoding="utf-8")
    inventory = parse_inventory(inventory_source)
    records = inventory.get("records") or []
    task_list = TASK_LIST_PATH.read_text(encoding="utf-8")
    features = [feature_view(record) for record in records]
    capabilities = [capability for record in records for capability in capability_views(record)]
    campaigns = parse_campaigns(task_list)
    github_snapshot = json.loads(GITHUB_PATH.read_text(encoding="utf-8")) if GITHUB_PATH.exists() else {"status": "unsynced", "items": {}, "references": {}}
    github_status = json.loads(GITHUB_STATUS_PATH.read_text(encoding="utf-8")) if GITHUB_STATUS_PATH.exists() else {"status": "unsynced", "error": None, "synchronizedAt": None}
    for campaign in campaigns:
        campaign["github"] = [github_snapshot.get("items", {}).get(str(number), {"number": number, "kind": "unsynced", "state": "unknown", "title": "", "url": "", "updatedAt": None, "ci": None}) for number in campaign["githubRefs"]]
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
    decision_capabilities = decisions.get("capabilityOverrides", {})
    capability_keys = {capability["key"] for capability in capabilities}
    if not set(decision_capabilities).issubset(capability_keys):
        errors.append("capabilityOverridesに存在しない能力keyがあります")
    # 分割対象外の能力も、親キーをそのまま能力キーとして明示的に保持する。
    for capability in capabilities:
        if capability["key"] not in decision_capabilities:
            parent_decision = decision_features.get(capability["parentFeatureKey"])
            if parent_decision:
                decision_capabilities[capability["key"]] = {
                    **parent_decision,
                    "reason": (
                        "親提案を継承した能力単位の仮説。子能力の独自実測は未実施。"
                        if capability["isCapability"]
                        else parent_decision.get("reason", "")
                    ),
                    "decisionStatus": parent_decision.get("decisionStatus", "proposed"),
                }
    allowed_stages = {"B0", "B1", "B2", "B3", "B4"}
    allowed_audiences = {"soccer", "alumni", "both"}
    allowed_priorities = {"must", "should", "could", "defer"}
    allowed_decision_statuses = {"proposed", "confirmed"}
    allowed_gate_statuses = {"done", "working", "blocked", "unknown"}
    if set(decision_features) != {feature["key"] for feature in features}:
        errors.append("Phase 2分類が43機能と完全一致しません")
    if set(decision_capabilities) != {capability["key"] for capability in capabilities}:
        errors.append("能力単位のPhase 2分類が表示能力と一致しません")
    capability_key_set = {capability["key"] for capability in capabilities}
    coverage_journeys = B0_COVERAGE.get("journeys", {})
    if set(coverage_journeys) != {item["id"] for item in B0_ALICIZATION_PLAN["journeys"]}:
        errors.append("B0 coverage journey ID集合が計画と一致しません")
    for journey in B0_ALICIZATION_PLAN["journeys"]:
        coverage = coverage_journeys.get(journey["id"])
        if not coverage or coverage.get("coverageStatus") not in {"covered", "partial", "missing"}:
            errors.append(f"B0 journey coverageStatus不正: {journey['id']}")
        paths = coverage.get("specPaths", []) if coverage else []
        if not isinstance(paths, list) or len(paths) != len(set(paths)):
            errors.append(f"B0 journey specPathsが配列または一意ではありません: {journey['id']}")
        if coverage and coverage.get("coverageStatus") in {"covered", "partial"} and not paths:
            errors.append(f"B0 journeyカバレッジにspecがありません: {journey['id']}")
        for path in (coverage or {}).get("specPaths", []):
            if not (ROOT / path).is_file():
                errors.append(f"B0 journey specが存在しません: {journey['id']} / {path}")
    for journey in B0_ALICIZATION_PLAN["journeys"]:
        if not set(journey["capabilities"]).issubset(capability_key_set):
            errors.append(f"B0アリシゼーションjourneyの能力key不一致: {journey['id']}")
    if "village-events-attendance-response" not in capability_key_set or "village-events-attendance-summary" not in capability_key_set:
        errors.append("出欠回答・出欠集計の分割能力がありません")
    if "survey-response" not in capability_key_set or "survey-results" not in capability_key_set:
        errors.append("アンケート回答・結果の分割能力がありません")
    if any("reservation" in key and "attendance" in key for key in capability_key_set):
        errors.append("予約と出欠を同一能力keyで表現しています")
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
            "b0Alicization": "docs/prototypes/beta-inventory-board-b0-alicization.json",
            "b0Coverage": "docs/prototypes/beta-inventory-board-b0-coverage.json",
            "gate": "docs/prototypes/beta-inventory-board-gate.json",
            "inventoryCommit": git_commit_for(INVENTORY_PATH),
            "taskListCommit": git_commit_for(TASK_LIST_PATH),
            "inventorySha256": hashlib.sha256(INVENTORY_PATH.read_bytes()).hexdigest(),
            "taskListSha256": hashlib.sha256(TASK_LIST_PATH.read_bytes()).hexdigest(),
            "decisionsSha256": hashlib.sha256(DECISIONS_PATH.read_bytes()).hexdigest(),
            "gateSha256": hashlib.sha256(GATE_PATH.read_bytes()).hexdigest(),
            "githubSnapshot": "docs/prototypes/beta-inventory-board-github.json",
            "githubSnapshotSha256": hashlib.sha256(GITHUB_PATH.read_bytes()).hexdigest() if GITHUB_PATH.exists() else None,
        },
        "sourceCounts": {
            "features": len(features),
            "capabilities": len(capabilities),
            "splitParents": len(CAPABILITY_SPLITS),
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
            f"正本は43大分類、表示・集計は{len(capabilities)}能力単位。分割親は{len(CAPABILITY_SPLITS)}件。",
            "B0〜B4・対象者・優先度は正本とは分離したPhase 2A提案であり、確定値ではない。",
            "Core／非Coreはlayerとrelease.betaから機械導出。foundationは正本にないため未設定。",
            "Gate前提工事はbeta-inventory-board-gate.jsonの根拠付きoverlayから表示。未確認項目は公開候補に含めない。",
            "GitHubはtask-list.mdの各CMP行に明記された#番号だけを同期し、未同期・エラー時は既存スナップショットを保持する。",
        ],
        "features": features,
        "capabilities": capabilities,
        "b0Alicization": B0_ALICIZATION_PLAN,
        "b0Coverage": B0_COVERAGE,
        "b0RunOverlay": B0_RUN_OVERLAY,
        "decisions": {**decisions, "capabilities": decision_capabilities},
        "featureClassification": {},
        "featurePublication": {},
        "gateFoundation": gate_items,
        "campaigns": campaigns,
        "githubSync": {**github_snapshot, "lastAttempt": github_status},
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

