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
GITHUB_GRAPHQL_BATCH_SIZE = 25


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


def run_ci_graphql(repo: str, number: int) -> dict:
    owner, name = repo.split("/", 1)
    query = "query($owner:String!, $name:String!, $number:Int!){ repository(owner:$owner,name:$name){ issueOrPullRequest(number:$number) { ... on PullRequest { commits(last: 1) { nodes { commit { statusCheckRollup { state } } } } } } } }"
    command = ["gh", "api", "graphql", "-f", f"query={query}", "-F", f"owner={owner}", "-F", f"name={name}", "-F", f"number={number}"]
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace", check=False)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or f"CI query exit {result.returncode}")
    payload = json.loads(result.stdout)
    if payload.get("errors"):
        raise RuntimeError("; ".join(error.get("message", "GitHub GraphQL error") for error in payload["errors"]))
    return payload.get("data", {}).get("repository", {}).get("issueOrPullRequest") or {}


def ci_from_rollup(item: dict) -> dict:
    """GraphQLのstatusCheckRollupを既存ci.statusへ写像する。"""
    nodes = (((item.get("commits") or {}).get("nodes") or []))
    state = (((nodes[0].get("commit") or {}).get("statusCheckRollup") or {}).get("state") if nodes else None)
    mapping = {"SUCCESS": "success", "FAILURE": "failure", "ERROR": "failure", "PENDING": "pending", "EXPECTED": "pending"}
    return {"status": mapping.get(state, "empty" if state is None else "unavailable"), "checks": [], "source": "GraphQL statusCheckRollup"}


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
        objects = {}
        for offset in range(0, len(numbers), GITHUB_GRAPHQL_BATCH_SIZE):
            # alias数とGraphQL複雑度を抑えつつ、N+1 REST呼び出しは発生させない。
            chunk = numbers[offset:offset + GITHUB_GRAPHQL_BATCH_SIZE]
            try:
                objects.update(run_gh(args.repo, chunk))
            except (OSError, RuntimeError, json.JSONDecodeError) as error:
                raise RuntimeError(f"GitHub参照 #{chunk[0]}-#{chunk[-1]} の取得失敗: {error}") from error
        records = {}
        for number in numbers:
            item = objects.get(f"n{number}")
            if not item:
                records[str(number)] = {"number": number, "kind": "missing", "state": "unknown", "title": "", "url": "", "updatedAt": None, "ci": None}
                continue
            record = {"number": number, "kind": "pull_request" if item.get("__typename") == "PullRequest" else "issue", "state": str(item.get("state", "")).lower(), "title": item.get("title", ""), "url": item.get("url", ""), "updatedAt": item.get("updatedAt"), "ci": None}
            if record["kind"] == "pull_request":
                if record["state"] == "open":
                    try:
                        record["ci"] = ci_from_rollup(run_ci_graphql(args.repo, number))
                    except (OSError, RuntimeError, json.JSONDecodeError) as error:
                        record["ci"] = {"status": "unavailable", "reason": f"CIロールアップ取得失敗: {error}", "checks": [], "source": "GraphQL statusCheckRollup"}
                else:
                    record["ci"] = {"status": "unavailable", "reason": "終了済みPRのCIは同期対象外", "checks": [], "source": "GraphQL statusCheckRollup"}
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
