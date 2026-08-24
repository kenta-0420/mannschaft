#!/usr/bin/env python3
"""docs/task-list.md の明記された GitHub 番号だけを読み取り専用で同期する。

成功時だけスナップショットを置き換える。認証・通信・API エラー時は既存の
スナップショットを変更せず、status ファイルにエラーを残す。
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TASK_LIST = ROOT / "docs" / "task-list.md"
SNAPSHOT = ROOT / "docs" / "prototypes" / "beta-inventory-board-github.json"
STATUS = ROOT / "docs" / "prototypes" / "beta-inventory-board-github-status.json"
GITHUB_REFERENCE_MINIMUM = 100  # 現台帳の実GitHub参照は#902以上。#6/#7等の内部手順番号を除外する。


def cmp_refs(source: str) -> dict[str, list[int]]:
    refs: dict[str, list[int]] = {}
    for line in source.splitlines():
        match = re.match(r"^\|\s*(CMP(?:-|$).*)", line)
        if not match:
            continue
        cmp_id = match.group(1).split("|", 1)[0].strip()
        numbers = sorted({number for value in re.findall(r"(?<![A-Za-z0-9])#(\d+)", line) if (number := int(value)) >= GITHUB_REFERENCE_MINIMUM})
        refs[cmp_id] = numbers
    return refs


def run_gh(repo: str, numbers: list[int]) -> dict:
    if not numbers:
        return {"data": {}}
    aliases = []
    for number in numbers:
        aliases.append(
            f"n{number}: issueOrPullRequest(number: {number}) {{"
            "__typename ... on Issue { number title state url updatedAt } "
            "... on PullRequest { number title state url updatedAt headRefOid }"
            "}"
        )
    query = "query($owner:String!, $name:String!){ repository(owner:$owner,name:$name){ " + " ".join(aliases) + " } }"
    owner, name = repo.split("/", 1)
    command = ["gh", "api", "graphql", "-f", f"query={query}", "-F", f"owner={owner}", "-F", f"name={name}"]
    result = subprocess.run(
        command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace", check=False
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or f"gh exit {result.returncode}")
    payload = json.loads(result.stdout)
    if payload.get("errors"):
        raise RuntimeError("; ".join(error.get("message", "GitHub GraphQL error") for error in payload["errors"]))
    return payload.get("data", {}).get("repository", {})


def run_checks(repo: str, sha: str) -> dict:
    if not sha:
        return {"status": "unavailable", "reason": "PRのhead SHAを取得できませんでした", "checks": []}
    result = subprocess.run(
        ["gh", "api", f"repos/{repo}/commits/{sha}/check-runs", "--paginate"],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace", check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or f"checks exit {result.returncode}")
    payload = json.loads(result.stdout)
    latest_by_name = {}
    for item in payload.get("check_runs", []):
        name = item.get("name") or f"check-{item.get('id')}"
        sort_key = (item.get("completed_at") or item.get("started_at") or "", item.get("id") or 0)
        if name not in latest_by_name or sort_key > latest_by_name[name][0]:
            latest_by_name[name] = (
                sort_key,
                {
                    "name": name,
                    "status": item.get("status"),
                    "conclusion": item.get("conclusion"),
                    "url": item.get("html_url"),
                    "completedAt": item.get("completed_at"),
                },
            )
    checks = [value[1] for value in sorted(latest_by_name.values(), key=lambda value: value[1]["name"])]
    conclusions = [item["conclusion"] for item in checks if item.get("conclusion")]
    if not checks:
        state = "empty"
    elif any(value in ("failure", "cancelled", "timed_out", "action_required") for value in conclusions):
        state = "failure"
    elif any(value is None for value in [item.get("conclusion") for item in checks]):
        state = "pending"
    else:
        state = "success"
    return {"status": state, "checks": checks}


def write_status(status: str, *, error: str | None = None, synchronized_at: str | None = None, count: int = 0) -> None:
    STATUS.write_text(json.dumps({"status": status, "error": error, "synchronizedAt": synchronized_at, "referenceCount": count}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default="kenta-0420/mannschaft")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    references = cmp_refs(TASK_LIST.read_text(encoding="utf-8"))
    numbers = sorted({number for values in references.values() for number in values})
    if args.dry_run:
        print(json.dumps({"cmpCount": len(references), "referenceCount": len(numbers), "numbers": numbers}, ensure_ascii=False))
        return
    synchronized_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
    try:
        objects = run_gh(args.repo, numbers)
        records = {}
        for number in numbers:
            item = objects.get(f"n{number}")
            if not item:
                records[str(number)] = {"number": number, "kind": "missing", "state": "unknown", "title": "", "url": "", "updatedAt": None, "ci": None}
                continue
            record = {"number": number, "kind": "pull_request" if item.get("__typename") == "PullRequest" else "issue", "state": str(item.get("state", "")).lower(), "title": item.get("title", ""), "url": item.get("url", ""), "updatedAt": item.get("updatedAt"), "ci": None}
            if record["kind"] == "pull_request":
                record["ci"] = run_checks(args.repo, item.get("headRefOid", ""))
            records[str(number)] = record
        payload = {"schemaVersion": 1, "repository": args.repo, "synchronizedAt": synchronized_at, "status": "synced", "error": None, "references": references, "items": records}
        SNAPSHOT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        write_status("synced", synchronized_at=synchronized_at, count=len(numbers))
        print(json.dumps({"status": "synced", "referenceCount": len(numbers), "snapshot": str(SNAPSHOT)}, ensure_ascii=False))
    except (OSError, RuntimeError, json.JSONDecodeError) as error:
        write_status("error", error=str(error), count=len(numbers))
        print(json.dumps({"status": "error", "error": str(error), "referenceCount": len(numbers)}, ensure_ascii=False))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
