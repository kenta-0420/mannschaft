# リファクタリング第2弾 概要

## 対象ファイル

| 対象 | 変更前行数 | 課題 |
|------|-----------|------|
| `frontend/app/pages/teams/[id]/webhooks.vue` | 903行 | Phase1で分割したorganizations版と同じ構造が重複 |
| `frontend/app/composables/useVillageApi.ts` | 759行 | 60+関数が1 composableに集中、5ドメイン混在 |
| `backend/.../errorreport/service/ErrorReportService.java` | 1049行 | 受信・集約・Kanban・タイムライン・検索の5責務が混在 |

---

## 1. teams/webhooks.vue 分割方針

### 対応方針: Phase1コンポーネントの汎用化

Phase1で作成した Webhook コンポーネント（`WebhookOutgoingTab`・`WebhookIncomingTab`・`WebhookApiKeyTab`）に `scopeType: 'ORGANIZATION' | 'TEAM'` + `scopeId: number` props を追加して汎用化。

```
変更前:
- organizations/[id]/webhooks.vue（905行）← Phase1で分割済み
- teams/[id]/webhooks.vue（903行）         ← 同じロジックが重複

変更後:
- components/webhooks/WebhookOutgoingTab.vue  （scopeType/scopeId props化）
- components/webhooks/WebhookIncomingTab.vue  （同上）
- components/webhooks/WebhookApiKeyTab.vue    （同上）
- organizations/[id]/webhooks.vue → 39行（scope-type="ORGANIZATION"を渡すのみ）
- teams/[id]/webhooks.vue         → 39行（scope-type="TEAM"を渡すのみ）
```

### 設計上の注意点

- イベントタイプ（`team.xxx` / `organization.xxx`）は `scopeType` から動的生成（computed）
- 両ページで同一コンポーネントを共有するため、片方の変更が両方に反映される

---

## 2. useVillageApi.ts 分割方針

### 分割後の構成（5ファイル）

```
frontend/app/composables/village/
├── useVillageApi.ts            （11関数: 村本体CRUD・ロビー・横断フィード）
├── useVillageMembershipApi.ts  （16関数: メンバーシップ・参加申請・村作成申請）
├── useVillageFeatureApi.ts     （14関数: ニックネーム・ピン・通報・代表委任・村紋）
├── useVillageEventApi.ts       （10関数: 歳時記カレンダー・お祭り）
└── useVillageMatchApi.ts       （ 9関数: 練習試合募集・応募）
```

元の `useVillageApi.ts` は re-export 専用ラッパーに置き換え（後方互換維持）。

### 設計上の注意点

- `qs` ヘルパー関数は各サブファイルにそれぞれ含める（共通化より局所性を優先）
- 「ロビー・横断フィード」は村本体と責務的に近いため `useVillageApi` に統合

---

## 3. ErrorReportService 分割方針

### 分割後の構成（4クラス）

```
backend/.../errorreport/service/
├── ErrorReportService.java         （~330行: コア受信・重複集約・ユーティリティ）
├── ErrorReportQueryService.java    （検索・統計・インシデント一覧・findById）
├── ErrorReportKanbanService.java   （Kanban表示変換・buildColumn）
└── ErrorReportTimelineService.java （タイムライン・ワークフロー・担当・コメント）
```

### 設計上の注意点

- `truncate()` は `ErrorReportNotifier`・`ErrorReportWeeklySummaryService` が参照するため `public static` で `ErrorReportService` に残す
- 各クラスに `@Service` + `@Transactional(readOnly = true)` クラスレベル設定
- テストファイル（`ErrorReportTimelineServiceTest`・`ErrorReportKanbanServiceTest`）も分割済み

---

## 実施時期

| フェーズ | 対象 | 実施時期 |
|---------|------|---------|
| 第1弾 | ActionMemoService / useParkingApi / webhooks.vue(org) | 2026-05-16 |
| 第2弾 | ErrorReportService / useVillageApi / webhooks.vue(teams汎用化) | 2026-05-16 |

## 参考

- `docs/refactoring/phase1_overview.md` — 第1弾の概要
