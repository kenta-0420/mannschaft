#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ArchUnit 凍結負債を番人・発生元トップレベルドメイン別に集計する。

利用例:
    python backend/scripts/collect_archunit_debt.py
    python backend/scripts/collect_archunit_debt.py --store-dir /path/to/archunit_store

標準出力は JSON: {"D-1": {"total": 2, "domains": {"team": 2}}, ...}。
total は重複を含む物理行数、domains は発生元ごとの行数。空ストアは 0 / {}。
D-2b は境界負債返済計画の対象外で、可視化のために集計する。
入力は変更せず、不整合時は標準エラーへ診断を出し非0終了する。
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys


RULE_IDS = {
    "no cross-domain entity dependency (D-1)": "D-1",
    "entities should extend UuidV7Entity (D-2b)": "D-2b",
    "transactional should not span other-domain repositories (D-3)": "D-3",
    "transactional entry should not transitively reach other-domain repositories (D-3T)": "D-3T",
    "no cross-domain repository dependency (D-5)": "D-5",
    "service API must not expose entities in signature (D-1 API boundary)": "ServiceAPI",
    "public controller endpoints must have an authorization signal (Wave4)": "AUTHZ_WAVE4",
}
UUID = re.compile(r"[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")
IDENTIFIER = r"[A-Za-z_$][A-Za-z0-9_$]*"
SOURCE = rf"com\.mannschaft\.app\.([a-z][a-z0-9_]*)\.{IDENTIFIER}(?:\.{IDENTIFIER})*"
# 行頭の発生元と番人固有の識別部分だけを読む。target は集計しない。
SOURCE_PATTERNS = {
    "D-1": re.compile(rf"^{SOURCE} \(domain '[^']+'\) depends on other-domain entity "),
    "D-2b": re.compile(rf"^Class <{SOURCE}> is not assignable to com\.mannschaft\.app\.common\.entity\.UuidV7Entity "),
    "D-3": re.compile(rf"^{SOURCE} \(domain '[^']+', @Transactional\) depends on other-domain repository "),
    "D-3T": re.compile(rf"^@Transactional entry {SOURCE}\([^\r\n]*?\) \(domain '[^']+'\) reaches other-domain repository "),
    "D-5": re.compile(rf"^{SOURCE} \(domain '[^']+'\) depends on other-domain repository "),
    "ServiceAPI": re.compile(rf"^{SOURCE}\([^\r\n]*?\) exposes @Entity class "),
    "AUTHZ_WAVE4": re.compile(rf"^{SOURCE}\([^\r\n]*?\) is a public Controller Mapping endpoint without an authorization signal "),
}
DEFAULT_STORE = Path(__file__).resolve().parent.parent / "src/test/resources/archunit_store"


def read_registrations(store_dir: Path) -> dict[str, str]:
    """現在の stored.rules の単純な description=UUID 形式を検証する。"""
    registration_file = store_dir / "stored.rules"
    registered: dict[str, str] = {}
    seen_uuids: set[str] = set()
    for number, line in enumerate(registration_file.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith(("#", "!")):
            continue
        description, separator, uuid = line.partition("=")
        description = description.replace("\\ ", " ")
        rule_id = RULE_IDS.get(description)
        if not separator or rule_id is None or not UUID.fullmatch(uuid):
            raise ValueError(f"{registration_file}:{number}: 未知または不正な登録")
        if rule_id in registered or uuid in seen_uuids:
            raise ValueError(f"{registration_file}:{number}: 番人またはUUIDの重複登録")
        registered[rule_id] = uuid
        seen_uuids.add(uuid)

    missing = set(RULE_IDS.values()) - registered.keys()
    if missing:
        raise ValueError(f"{registration_file}: 必須登録が不足: {', '.join(sorted(missing))}")
    orphan_files = sorted(p.name for p in store_dir.iterdir()
                          if p.name != "stored.rules" and p.name not in seen_uuids)
    if orphan_files:
        raise ValueError(f"{registration_file}: 未登録ストア: {', '.join(orphan_files)}")
    return registered


def collect_debt(store_dir: Path) -> dict[str, dict]:
    registered = read_registrations(store_dir)
    result = {}
    for rule_id, uuid in sorted(registered.items()):
        store_file = store_dir / uuid
        domains: Counter[str] = Counter()
        for number, line in enumerate(store_file.read_text(encoding="utf-8").splitlines(), 1):
            match = SOURCE_PATTERNS[rule_id].match(line)
            if match is None:
                raise ValueError(f"{store_file}:{number}: {rule_id}の未知または空の違反行")
            domains[match.group(1)] += 1
        result[rule_id] = {"total": sum(domains.values()), "domains": dict(sorted(domains.items()))}
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--store-dir", type=Path, default=DEFAULT_STORE,
                        help="stored.rules と全7ストアのディレクトリ（既定: 自身のbackend内）")
    args = parser.parse_args()
    try:
        result = collect_debt(args.store_dir)
    except (OSError, UnicodeError, ValueError) as error:
        print(f"[ERROR] {error}", file=sys.stderr)
        return 1
    # 全ストアの検証成功後に一度だけ出力し、途中成功のJSONを残さない。
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
