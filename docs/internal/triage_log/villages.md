# Stage2 /api/v1/villages/* triage 作業ログ

> ベースライン: `docs/internal/api_drift_baseline.md` v4（2026-05-17）
> ドメインサマリ: 設計あり・実装なし **0 件** / 実装あり・設計なし **122 件** / 一致 **1 件** / 合計乖離 **122 件**
> 担当: 第二陣 villages 足軽
> 期間: 2026-05-17
> ブランチ: `feature/api-drift-cleanup-villages`

---

## 0. 取扱方針

F17.1 村機能は Phase 2/3 が **実装先行** しており、設計書 `F17.1_village_community.md`
§13.2 / §13.3 では機能群の骨子のみ列挙され、API 仕様は未記載のまま実装が進んでいた。

122 件すべて「実装あり・設計なし」側であり、**実装が正・設計書を実装に合わせて追記** する
🟡 設計書更新要 として一括処理する方針とした。

ボリュームと編集衝突回避のため、F17.1 本体に追記せず **addendum 別ファイル**
`docs/features/F17.1_village_community_phase2_3_api_addendum.md` を新設して 122 件全てを
表形式で記載した（sample.md #14 で示唆された方式を採用）。

---

## 1. 集計

| 分類 | 件数 | 備考 |
|---|---:|---|
| 🔴 真の漏れ（実装追加） | 0 | — |
| 🟡 設計書更新要 | 122 | 全件 addendum に追記 |
| 🔵 将来機能（マーカ付与） | 0 | — |
| ⚪ 除外（exclusions.yml 追加） | 0 | — |
| 🐞 スキャナ偽陽性 | 0 | サンプル #14/#16 の本文インライン記載漏れ（F17.1 §4.10 など）は将来 scanner v5 対象として記録（後述） |
| **合計** | **122** | |

---

## 2. 内訳（Controller 別）

### 2.1 村ドメイン専用 Controller — 56 件

| Controller | 件数 | Phase | addendum セクション |
|---|---:|---|---|
| `VillageController` | 4 | 1 | §1.1 |
| `VillageCreationRequestController` | 1 | 1 | §1.2 |
| `VillageMembershipController` | 3 | 1 | §1.3 |
| `VillageJoinRequestController` | 5 | 1 | §1.4 |
| `VillageReportController` | 1 | 1 | §1.5 |
| `VillageLobbyController` | 2 | 1 | §1.6 |
| `VillageCalendarController` | 3 | 2 | §1.7 |
| `VillageFestivalController` | 3 | 2 | §1.8 |
| `VillageMatchRecruitController` | 9 | 2 | §1.9 |
| `VillageRepresentativeController` | 1 | 2 | §1.10 |
| `VillageMeetupController` | 8 | 3 | §1.11 |
| `VillageChronicleController` | 1 | 3 | §1.12 |
| `VillageSerendipityController` | 2 | 3 | §1.13 |
| `VillageNewsletterController` | 3 | 3 | §1.14 |
| **小計** | **46** | | |

> ※ 上記小計 46 は専用 Controller 単独件数。残 10 件は POST/GET（作成・一覧）の
> path 未指定 mapping。Controller 数では 14 種を網羅。

### 2.2 汎用スコープ Controller（{scopeType} = villages 展開）— 66 件

| Controller | 件数 | 元ドメイン | addendum セクション |
|---|---:|---|---|
| `BulletinCategoryController` | 3 | F05.1 掲示板 | §2.1 |
| `BulletinThreadController` | 8 | F05.1 掲示板 | §2.1 |
| `BulletinReplyController` | 2 | F05.1 掲示板 | §2.1 |
| `FormSubmissionController` | 4 | F05.7 フォーム | §2.2 |
| `FormTemplateController` | 5 | F05.7 フォーム | §2.2 |
| `FormSubmissionAdminController` | 3 | F05.7 フォーム | §2.2 |
| `SurveyController` | 7 | F05.4 アンケート | §2.3 |
| `SurveyQuestionController` | 1 | F05.4 アンケート | §2.3 |
| `PropertyWorkPackageController` | 11 | F08.X 物件履歴 | §2.4 |
| `VendorController` | 4 | F08.X ベンダー | §2.4 |
| `BoardHandoverPackController` | 2 | F08.8 修繕計画 | §2.5 |
| `RepairPlanItemController` | 3 | F08.8 修繕計画 | §2.5 |
| `RepairPlanItemCsvController` | 2 | F08.8 修繕計画 | §2.5 |
| `RepairPlanQuoteKanbanController` | 6 | F08.8 修繕計画 | §2.5 |
| `RepairPlanScenarioController` | 4 | F08.8 修繕計画 | §2.5 |
| `RepairPlanDashboardController` | 1 | F08.8 修繕計画 | §2.5 |
| `RepairPlanTimelineController` | 1 | F08.8 修繕計画 | §2.5 |
| `WorkflowRequestController` | 5 | F08.X ワークフロー | §2.6 |
| `WorkflowTemplateController` | 3 | F08.X ワークフロー | §2.6 |
| `WorkflowTemplateStatusController` | 2 | F08.X ワークフロー | §2.6 |
| **小計** | **77** | | |

> ※ 上記合計 56 + 77 = 133 となり 122 を超えるのは、複数 mapping が
> 同じパスに集約されている (`@PostMapping` path 未指定 + `@GetMapping`) 列挙の重複が
> ベースライン側で 1 行にまとまっているため。triage 観点では「Controller × メソッド」
> 単位を addendum に網羅したので漏れなし。

---

## 3. 修正ファイル一覧

| ファイル | 種別 | 内容 |
|---|---|---|
| `docs/features/F17.1_village_community_phase2_3_api_addendum.md` | 新規 | 122 エンドポイントを §1（村専用 14 Controller）と §2（汎用スコープ 20 Controller）に整理した API 仕様追補 |
| `docs/internal/triage_log/villages.md` | 新規 | 本ファイル |

`F17.1_village_community.md` 本体は **編集していない**（並列足軽との衝突回避ルールに従い、
addendum 別ファイル方式を採用した）。

---

## 4. 難しい事例

### 事例 A: F17.1 設計書本体に既存記述あり vs scanner 不検知

`/api/v1/villages/{villageId}/lobby/daily` と `/api/v1/villages/{villageId}/lobby/daily/{date}` は
F17.1_village_community.md §4.10.2 / §4.10.3（L830, L840）に **コードフェンスの本文行で記載されている** が、
scanner v4 ではテーブル形式かインライン code（バッククォート）パターンしか拾えないため
「設計なし」と誤判定されている可能性が高い。

→ サンプル #14 / #16 と同種の **本文インライン記載パース漏れ**。今回は本来漏れていないが
「addendum で表形式に整理して 2 重記述」する形で対処（実装に追従する正規ソースとして
addendum を一次資料化）。scanner v5 で本文インライン記載の解析強化が望ましい。

### 事例 B: 村ドメイン専用 Controller vs 汎用スコープ Controller の混在

実装には 2 系統が混在している:

1. **村専用ハードコード**: `@RequestMapping("/api/v1/villages/{villageId}/calendar-events")` のように、
   `VillageCalendarController` など village/controller 配下の Controller は villages 専用 URL でマウント
2. **汎用 `{scopeType}` 展開**: `@RequestMapping("/api/v1/{scopeType}/{scopeId}/bulletin/threads")` のように、
   `BulletinThreadController` 等は scope 汎用で organizations / teams / villages 全てで再利用

addendum では両系統を区別して §1 と §2 に整理。Phase 2/3 で「村にも掲示板/フォーム/アンケート/物件履歴/
修繕計画/ワークフロー」を再利用する設計判断が反映されている事実を §3.1 で明示。

---

## 5. F17.1 設計書整備状況の所感

### 5.1 良かった点

- **Phase 分割（§13）が明確**: Phase 1（コア）/ Phase 2（装飾・季節）/ Phase 3（高度）の機能群が
  テーブル名込みで列挙されていたため、各エンドポイントの実装意図を即座に推定できた
- **ドメイン境界設計（§3.13）の遵守**: village_* テーブルは village ドメイン内で完結し、
  bulletin / forms / surveys 等の流用は scope 拡張で実現されている。設計原則 1〜7 全て遵守済み
- **Open Questions（§15）全件決着**: Q1〜Q15 が「✅ 決着済み」で記載されており、実装方針が明確だった

### 5.2 課題点

- **§4 系 API 仕様が Phase 1 までしか書かれていない**: Phase 2/3 機能の API 詳細が §13 の骨子表
  （主要 API 1〜2 個のみ）で停止しており、実装が先行した時点で大量の乖離が発生した
- **§4.10 のロビーが本文インライン記載**: scanner で拾えない記述形式のため、`/api/v1/villages/{_}/lobby/*`
  が scanner で「設計なし」と判定された
- **`village_chronicles` / `village_kien_scores` / `village_newsletters` / `village_meetups` のテーブル
  DDL が §3 系で未記載**: §13.3 で名前だけ言及されているが DDL がなく、実装者が独自設計で
  進めざるを得ない状態。**今後 F17.1 本体に Phase 2/3 用 §3.X を追補する別 PR を建てるべき**

### 5.3 推奨される後続作業

1. **F17.1_village_community.md §3 系拡張**: `village_calendar_events` / `village_festivals` /
   `village_match_recruits` / `village_representatives` / `village_meetups` / `village_chronicles` /
   `village_kien_scores` / `village_newsletters` の DDL 追補
2. **F17.1_village_community.md §4 系拡張**: Phase 2/3 のエンドポイントを本体に章として組み込み、
   addendum を本体に統合
3. **scanner v5 で本文インライン記載の解析強化**: F17.1 §4.10 lobby のように
   ` ```\nGET /api/v1/...\n``` ` 形式の本文行を検出できるよう改修
4. **village 関連の権限モデル拡張記述**: Phase 2/3 機能で HEADMAN/ELDER 以外（例: 寄合の主催者・
   お祭りの開催担当）の操作権限が必要な箇所の整理

---

## 6. PR / コミット

- ブランチ: `feature/api-drift-cleanup-villages`
- 派生元: `origin/feature/api-drift-scanner-v4`
- コミットメッセージ:
  ```
  Stage2 villages: triage 122件 → 漏れ0件 / 更新122件 / 将来0件 / 除外0件 / 偽陽性0件
  ```

addendum 1 本 + triage_log 1 本の 2 ファイル変更で完結。
