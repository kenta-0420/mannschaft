# API 乖離スキャナ v6 検証記録

> 2026-05-17 / Stage 3 終盤「scanner v6 改修」検証ログ。
> 対応 PR: `feature/api-drift-scanner-v6`。

## 1. 目的

Stage 3 第三陣以降で繰り返し報告された 2 大偽陽性パターンを根治する。

1. **同一設計書内の重複抽出**
   - §4 の API 一覧表（例: `| GET | /api/v1/foo | ... |`）と
     §4.x 詳細セクションのヘッダ（例: `#### GET /api/v1/foo`）で
     **同一 (method, path)** が 2 度抽出されてしまう
   - スキャナはこれを「設計記載 2 件」として独立カウントするため、
     a. 設計 raw 件数（matched / only_design）が水増しされる
     b. 実装側の 1 件が複数の設計記載と乖離しているように誤検出される
2. **末尾セグメントのリネーム揺れ**
   - succession 軍が triage_log で報告したペア例:
     - 設計 `/api/v1/foo/{id}/first-approve` ↔ 実装 `/api/v1/foo/{id}/approve`
     - 設計 `/api/v1/foo/{id}/evidence-zip` ↔ 実装 `/api/v1/foo/{id}/evidence-package`
     - 設計 `/api/v1/foo/{id}/evidence-rebuild` ↔ 実装 `/api/v1/foo/{id}/evidence-package`
   - 末尾セグメントだけ意味的等価リネームになっているケースを別物扱いしてしまい、
     missing_impl / missing_design 両方に同じものが乗ってしまう

これらを v6 で次の改修により根治する。

## 2. 改修内容

### V6-1: 同一設計書内 (method, path) 重複排除（既定 ON）

| 観点 | 内容 |
|---|---|
| 追加 | `dedup_design_within_file(endpoints)` ヘルパ |
| 適用 | `scan_design_docs()` の末尾で各 .md ファイル単位に dedup |
| 保持ルール | 最初の出現を保持（一覧表 → 詳細ヘッダの順なら一覧表側） |
| 集約しないケース | 異なる .md ファイル間の同一 (method, path) は保持（複数設計書からの相互参照は正当） |
| フラグ | `--no-v6-dedup` で従来挙動に戻る（既定 ON） |

**狙い**: 設計書 raw unique 件数を 1 セクションあたり 1 件に正規化し、
matched 水増しと missing_design の連鎖偽陽性を断つ。

### V6-2: 末尾セグメントリネーム辞書による準一致（既定 OFF）

| 観点 | 内容 |
|---|---|
| 追加 | `RENAME_PAIRS_V6` ホワイトリスト（双方向ペア配列） |
| 追加 | `find_rename_match(path, candidates)` ヘルパ |
| 動作 | `only_design` と `only_impl` の組で「末尾セグメントが辞書ペア、それ以外完全一致」のものを準一致として除外 |
| 注釈 | レポートで `(matched by rename normalization)` を付与 |
| フラグ | `--v6-rename` で有効化（**既定 OFF**） |

**ホワイトリスト初期セット**:

```python
RENAME_PAIRS_V6 = (
    ("first-approve", "approve"),
    ("evidence-zip", "evidence-package"),
    ("evidence-rebuild", "evidence-package"),
)
```

**狙い**: succession 軍 triage_log で確定したペアだけを安全に救済し、
意図しない合体（例: arbitrary `delete` ↔ `remove`）は排除する。

**なぜ既定 OFF か**:
- 末尾セグメントリネームは「動詞同義語」と「全く別 API」の境界が曖昧
- 自動で合体させて見落としを生むより、明示 ON で運用判断を残す方が安全
- baseline 上のドメイン固有改修（succession の first-approve など）に限定して
  オペレータが ON にする運用設計

## 3. baseline 数値 before/after

> 本 PR では baseline 再生成は行わない（後続 PR で別途実施）。
> 以下は scanner 本体を v6 にした状態で `cd backend && python scripts/scan_api_drift.py`
> を擬似実行した数値（実際の baseline.md ファイル更新はコミット対象外）。

| 指標 | v5 相当（`--no-v6-dedup`） | v6 既定（dedup ON, rename OFF） | Δ |
|---|---:|---:|---:|
| 設計 endpoints raw | 4,421 | **3,050** | **-1,371** |
| 実装 endpoints raw | 2,491 | 2,491 | ±0 |
| missing_impl (only_design) | 1,089 | **1,069** | **-20** |
| missing_design (only_impl) | 756 | 756 | ±0 |
| matched | 1,584 | 1,581 | -3 |

### 解説

- **V6-1 dedup の効果は raw 抽出段階で大きい（-1,371 件）**。これは同一 .md
  ファイル内で一覧表 + 詳細ヘッダ + インラインコード補助抽出が重複していた
  ものを 1 件に集約した結果。
- 集計段階の missing_impl も **-20 件削減**。これは「設計側のみに重複登録があり、
  実装側 1 件と組まれていなかった残骸」が正しく除去されたことを意味する。
- matched が -3 件しか減らないのは、(method, path) キーの dict 集約段階で
  既に重複ヒットが 1 件に集約されていたためで、これは v3 バグ2 根治の効果。
  v6 でさらに「同一ファイル内 (method, path) を最初の出現にする」ことで、
  source_file / line_number 表示の正確性が上がる（matched 件数自体は微減）。

| 指標 | v6 既定 | v6 + `--v6-rename` | Δ |
|---|---:|---:|---:|
| missing_impl | 1,069 | 1,069 | ±0 |
| missing_design | 756 | 756 | ±0 |
| V6-2 リネーム辞書準一致 | 0 件（OFF） | 0 件 | ±0 |

**rename ON 時に削減 0 件である理由**:
- 現 main の baseline ではすでに `evidence-zip` / `evidence-rebuild` が
  設計 / 実装の独立 path として残っていない（V5-1 逆引き or V5-3 命名揺れで
  別経路で吸収済み、または triage_log と実 main の対応関係が時間差で動いている）
- V6-2 はホワイトリスト方式の「将来の再発防衛策」として有効。実害が出ていない
  以上 0 件はむしろ「誤合体していない」良い状態
- 後続 PR で `baseline 再生成 v6` を回すときに、もし対象 path が再出現したら
  rename 準一致が発火し triage で `(matched by rename normalization)` 注釈が
  確認できるようになる

## 4. テスト結果

```
$ cd backend && python -m unittest scripts.test_scan_api_drift
............................................................................
Ran 77 tests in 0.547s
OK
```

- 既存テスト: 58 件（v5 まで）→ 全 PASS
- 新規追加: **19 件**
  - `TestV6DedupWithinFile`: 7 件
    - 一覧表 + 詳細ヘッダ重複の dedup 確認
    - 最初の出現保持（行番号・source_file）の確認
    - 異なるファイル間は集約しないこと
    - `--no-v6-dedup` で従来挙動に戻ること
    - method 違いは重複扱いしないこと
    - `dedup_design_within_file` ヘルパの入力順保持
    - dedup により only_design 件数が削減されること
  - `TestV6RenamePairs`: 12 件
    - `_RENAME_LOOKUP_V6` が双方向であること
    - `find_rename_match` の基本動作
    - 末尾以外が違うとマッチしないこと（誤合体回避）
    - ホワイトリスト外（delete↔remove 等）はマッチしないこと
    - デフォルト OFF で別物のまま残ること
    - `--v6-rename` ON で first-approve↔approve / evidence-zip↔evidence-package が準一致になること
    - arbitrary なペアは ON でもマッチしないこと
    - 他セグメントが違う場合は ON でも合体しないこと
    - method 不一致はマッチしないこと
    - 双方向（設計 plain ↔ 実装 first）でもマッチすること
    - レポート出力で OFF 時に「無効化されている」旨が表示されること

## 5. 誤合体検出時の対処方針

V6-2 を ON にしたあと、もし「意図しない合体」が triage で検出された場合の対処:

1. **検出**: triage_log で `matched by rename normalization` 注釈付きペアを
   レビューし、意味的に等価でないと判断したペアを記録する
2. **暫定対処**: 該当 CI 実行を `--v6-rename` 無しで再走させてマージブロック回避
3. **根治**: 該当ペアを `RENAME_PAIRS_V6` から除外。さらに「除外すべきペア」を
   `RENAME_FALSE_POSITIVES_V6`（将来追加検討）にネガティブ登録するか、
   ペアそのものの片側 path を baseline 個別レビューに回す
4. **記録**: `scanner_v6_validation.md` 末尾の「誤合体事例ログ」に追記
   （初版時点では未発生のため空）

## 6. 後続作業

- 本 PR: scanner code 改修 + テスト + 検証記録のみ（baseline 再生成しない）
- 次 PR: `baseline 再生成 v6`（殿が指揮）
  - `cd backend && python scripts/scan_api_drift.py` を回して baseline.md を更新
  - 必要なら `--v6-rename` ON 版も別ファイル `api_drift_baseline_v6_rename.md`
    として並走し、Stage 3 triage で差分確認
- 拡張候補:
  - Stage 3 triage_log を再走査し、`RENAME_PAIRS_V6` への追加ペアを抽出
  - succession 以外ドメインでも明らかなリネーム揺れがあるか調査

## 7. 誤合体事例ログ

（初版時点で報告なし）
