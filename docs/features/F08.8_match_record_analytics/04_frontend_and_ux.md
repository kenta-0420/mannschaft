# F08.8 / 04: フロントエンド画面・UX・チャート・composable・i18n

> **ステータス**: 🟡 設計中
> **最終更新**: 2026-06-08
> **関連機能番号**: F08.8（試合記録・分析）／ F02.2 ダッシュボード
> **関連ドキュメント**:
> - [02_playing_time_and_aggregation.md](./02_playing_time_and_aggregation.md) — 集計 API・チャート種別対応
> - [03_permissions_and_recording_modes.md](./03_permissions_and_recording_modes.md) — 画面の編集可否
> - 流用元: `frontend/app/composables/wallet-group-show/useWakeLockWithFallback.ts`（WakeLock）／ `frontend/app/components/activity/ActivityStatsPanel.vue`（chart.js 利用パターン）

本書は **G（フロントエンド画面・UX）** を具体化する。

---

## G. フロントエンド画面・UX

### G.1 画面一覧

| 画面 | パス | 役割 |
|------|------|------|
| 単独試合一覧 | `pages/teams/[id]/matches/index.vue` | チームの全試合（kind 別フィルタ・W/D/L 表示） |
| 試合作成 | `pages/teams/[id]/matches/new.vue` | 練習/親善/大会試合の新規作成（相手・モード・日時） |
| ライブ記録 | `pages/teams/[id]/matches/[matchId]/live.vue` | タイムライン入力（**本機能の肝**・G.2） |
| 個人分析（自分） | `pages/me/match-analytics.vue` | 自分のキャリア統計チャート |
| 個人分析（メンバー） | `pages/teams/[id]/members/[userId]/match-analytics.vue` | チームメンバーのキャリア統計（権限に従う） |
| チーム分析 | `pages/teams/[id]/match-analytics.vue` | 勝敗・得失点・選手別ランキング |
| ダッシュボードウィジェット | `components/widgets/WidgetTeamMatchSummary.vue` | チーム試合サマリ（F02.2 ウィジェット） |

### G.2 ライブ入力 UX（GoalNote 上位互換の核）

**設計目標**: 会場でスマホ片手・片手親指で、得点や交代を**3 タップで完了**できること（ADHD 配慮＝入力摩擦最小）。

#### リスト型タイムライン

- 画面中央に時系列イベントのリスト（最新が上）。各行はワンタップで編集ダイアログ、スワイプで削除（即時 undo トースト付き）。
- **得点 = 3 タップ**: [得点ボタン] → [得点者を選手グリッドから選択] → [任意でアシスト者選択 or スキップ]。
- **交代 = 3 タップ**: [交代ボタン] → [OUT 選手] → [IN 選手]。SUB_OUT/SUB_IN の 2 イベントを 1 操作で生成。
- **カード**: [カードボタン] → [選手] → [黄/赤]（赤・2 枚目黄は out 確定・02 §E）。

#### タイマー連動・手動訂正

- 経過タイマー（PERIOD_START 基準）で `minute` を**自動補完**。
- ただし**分もイベントも手動で訂正・編集・削除可**（タイマーずれ・後追い入力に対応）。分入力は数値ステッパー＋アディショナル（`+N`）入力欄。
- タイマーは前半/後半/延長のピリオド切替ボタンで `period` を更新（PERIOD_START/PERIOD_END イベントを自動記録）。

#### 選手グリッド

- 背番号＋名前の**大タップターゲット**（最低 44×44pt）のグリッド。
- **先発を上段・控えを下段**に配置（02 §E の is_starter 連動）。
- 未登録選手は「手入力で追加」ボタン（`player_name` 直接入力）。

#### その他 UX

- **よく使うイベント大ボタン**（得点・交代・カード）を画面下部に固定配置。
- **即時 undo トースト**（直前操作の取り消し）。
- **WakeLock**（`useWakeLockWithFallback` 流用）で記録中の画面消灯を防止。
- **オフライン耐性**は任意 Phase（dexie でローカルキュー → 復帰時同期）。MVP では online 前提（[06](./06_implementation_plan.md) で後段 Phase）。
- 共同記録モードでの 409（楽観ロック競合・03 §未解決 1）は、サイレントに握りつぶさず「他の記録者が更新しました。再読込します」トースト＋自動再取得で根治的に解決。

### G.3 チャート（chart.js@4 流用）

- **新規ライブラリ不要**。既存の `chart.js@4`（`ActivityStatsPanel.vue` 等で使用中）を流用する。
- radar / line 用に必要な要素を `Chart.register` で追加登録する（既存は Bar 系のみ登録のため、`RadarController`/`LineController`/`PointElement`/`LineElement`/`RadialLinearScale` 等を追加）。
- **共通ラッパー `components/charts/BaseChart.vue` を新設**: type・data・options を props で受け、マウント後に Chart を生成・watch で再描画・unmount で destroy する（メモリリーク防止は `ActivityStatsPanel.vue` の destroy パターン踏襲）。
- **SSR 注意**: chart.js はブラウザ API 依存。`<ClientOnly>` でラップし、`onMounted` 後にのみ Chart を生成する（SSR 中に canvas を触らない）。

| チャート | コンポーネント | データ源（02 §F） |
|----------|---------------|-------------------|
| radar | `BaseChart type="radar"` | 個人スタッツ分布 |
| line | `BaseChart type="line"` | 月別/シーズン推移・出場時間推移 |
| doughnut | `BaseChart type="doughnut"` | ポジション傾向・kind 別割合 |
| bar | `BaseChart type="bar"` | 得点分布・選手別ランキング |

### G.4 composable

| composable | 対象 API | 主メソッド |
|------------|----------|-----------|
| `useMatchApi` | 単独試合 CRUD | `listMatches` / `getMatch` / `createMatch` / `updateMatch` / `changeStatus` / `deleteMatch` |
| `useMatchEventApi` | イベント記録 | `listEvents` / `recordEvent` / `recordSubstitution` / `recordGoal` / `updateEvent` / `deleteEvent` / `listAppearances` |
| `useMatchAnalytics` | 集計取得 | `getUserStats` / `getUserTimeline` / `getTeamStats` |

- 型は `types/match.ts` に集約（**`any` 禁止**・CLAUDE.md）。生成型（`types/generated`）が整備されたら優先利用し、手動型は段階移行。
- 既存 `useMatchRoster.ts`（F08.7.1）と名前が近いが**別物**（あちらは tournament roster、こちらは match ドメイン）。混同防止のため JSDoc に明記する。
- composable のエラーは握りつぶさず `useNotification` で表示し再 throw（既存 `useMatchRoster` パターン踏襲）。

```ts
// types/match.ts（抜粋・any 禁止）
export type MatchKind = 'PRACTICE' | 'FRIENDLY' | 'TOURNAMENT' | 'LEAGUE'
export type TeamSide = 'HOME' | 'AWAY'
export type MatchStatus = 'SCHEDULED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED'
export type MatchEventType =
  | 'STARTER' | 'SUB_IN' | 'SUB_OUT' | 'GOAL' | 'ASSIST' | 'OWN_GOAL'
  | 'PENALTY_GOAL' | 'PENALTY_MISS' | 'YELLOW_CARD' | 'RED_CARD' | 'SECOND_YELLOW'
  | 'SAVE' | 'INJURY' | 'PERIOD_START' | 'PERIOD_END'

export interface MatchResponse { id: string; kind: MatchKind; /* ... */ }
export interface MatchEventResponse { id: string; eventType: MatchEventType; minute: number | null; /* ... */ }
export interface UserMatchStatsResponse { totalMatches: number; goalsPer90: number | null; monthlyTrend: MonthlyStat[]; /* ... */ }
```

### G.5 i18n

- `app/locales/{ja,en,zh,ko,es,de}/match.json` を**新設**（6 言語）。未翻訳はとりあえず日本語値で可、後で翻訳（CLAUDE.md i18n ルール）。
- **`nuxt.config.ts` の i18n `files` 配列（言語ごとに 6 ブロック）に `{lang}/match.json` を登録必須**（既存は言語別に列挙されている）。登録漏れはロード不能の典型バグ。
- UI 文字列は**直書き禁止**・必ず `$t('match.xxx')` で参照。

namespace 案:

| namespace | 用途 |
|-----------|------|
| `match.list` | 一覧（フィルタ・W/D/L ラベル・空状態） |
| `match.create` | 作成フォーム（相手・モード・日時・会場） |
| `match.live` | ライブ記録（ボタン・タイマー・undo・409 トースト） |
| `match.analytics` | 分析画面（指標名・チャート凡例・期間） |
| `match.event_type` | イベント種別ラベル（GOAL→「得点」等） |
| `match.card_type` | カード種別ラベル（YELLOW→「警告」等） |

---

## 未解決事項

1. **メンバーのキャリア統計閲覧範囲**: `pages/teams/[id]/members/[userId]/match-analytics.vue` を MEMBER 同士で見せるか、ADMIN/本人のみか（03 §C.4 の IDOR と整合）。プライバシー設定（F19.1 個人プロフィール公開）との連動可否。
2. **ライブ入力のオフライン対応 Phase**: dexie ローカルキューを MVP に含めるか後段にするか。会場の電波状況次第で優先度が変わる（マスター判断）。
3. **BaseChart の汎用度**: F08.8 専用にするか、既存 `ActivityStatsPanel` 等もこのラッパーへ寄せるリファクタを併せて行うか（スコープ拡大注意）。
4. **WidgetTeamMatchSummary の min_role**: F02.2.1 のウィジェットロール別可視性（min_role 表の正本）に新ウィジェットを登録する必要がある。CI 双方向検証に乗せるため min_role を確定する。
