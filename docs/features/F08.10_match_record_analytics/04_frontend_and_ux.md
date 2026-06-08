# F08.10 / 04: フロントエンド画面・導線・UX・チャート・composable・i18n

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-08
> **関連機能番号**: F08.10（試合記録・分析）／ F02.2 ダッシュボード ／ F02.2.1 ウィジェット min_role ／ F19.1 個人プロフィール公開
> **関連ドキュメント**:
> - [02_playing_time_and_aggregation.md](./02_playing_time_and_aggregation.md) — 集計 API・チャート種別対応・空状態
> - [03_permissions_and_recording_modes.md](./03_permissions_and_recording_modes.md) — 画面の編集可否・他者統計閲覧の認可
> - 流用元: `frontend/app/composables/wallet-group-show/useWakeLockWithFallback.ts`（WakeLock）／ `frontend/app/components/activity/ActivityStatsPanel.vue`（chart.js 利用パターン）

本書は **G（フロントエンド画面・導線・UX）** を具体化する。

---

## G. フロントエンド画面・UX

### G.1 画面一覧

| 画面 | パス | 役割 |
|------|------|------|
| 単独試合一覧 | `pages/teams/[id]/matches/index.vue` | チームの全試合（kind 別フィルタ・W/D/L 表示・進行中バッジ） |
| 試合作成 | `pages/teams/[id]/matches/new.vue` | 練習/親善/大会試合の新規作成（クイックスタート・G.1b） |
| ライブ記録 | `pages/teams/[id]/matches/[matchId]/live.vue` | タイムライン入力（**本機能の肝**・G.2） |
| 個人分析（自分） | `pages/me/match-analytics.vue` | 自分のキャリア統計チャート（マイページ「試合分析」タブ・G.9） |
| 個人分析（メンバー） | `pages/teams/[id]/members/[userId]/match-analytics.vue` | チームメンバーのキャリア統計（teamId 必須・権限に従う・03 §C.4） |
| チーム分析 | `pages/teams/[id]/match-analytics.vue` | 勝敗・得失点・選手別ランキング |
| ダッシュボードウィジェット | `components/widgets/WidgetTeamMatchSummary.vue` | チーム試合サマリ（F02.2 ウィジェット・min_role=MEMBER） |

### G.1a 導線（ページ遷移フロー）【致命的指摘の根治】

機能の発見可能性を担保するため、最低限以下の導線を実装する。

- **チームページ → 試合一覧 → ライブ記録**: チームページに「試合」メニュー → `matches/index.vue` → 各試合カードから `live.vue`。
- **試合一覧の進行中バッジ → ライブ記録復帰**: `status=IN_PROGRESS` の試合に「進行中」バッジを表示し、タップで `live.vue` へ復帰（記録の中断・再開）。
- **ダッシュボードウィジェット → 記録再開 CTA**: `WidgetTeamMatchSummary` に進行中試合があれば「記録を再開」CTA を表示し `live.vue` へ。
- **マイページ → 試合分析タブ**（G.9）／ **チームメンバー一覧各行 → 分析リンク**（G.9）。

### G.1b 試合作成の最小項目（クイックスタート）【致命的指摘の根治】

ADHD 配慮（入力摩擦ゼロ）として、試合作成の**必須は `kind`（種別）＋ 相手名 のみ**。

- `venue` / `duration_minutes` / `scorekeeper`（記録係）は**任意**で「後で設定可」。
- **種別タップで即記録開始**のクイックスタート UX: 種別（練習/親善/大会）を選び相手名を入れたら即 `live.vue` へ遷移できる（残りは試合中・試合後に補完）。
- 相手は登録チーム選択 or 未登録相手名の自由入力（`opponent_team_id` or `opponent_name`）。
- **後で整理可能（ADHD 配慮）**: `duration_minutes` / `venue` / `scorekeeper`（記録係）等の任意項目は、**COMPLETED 直後に表示する試合詳細編集画面で後から補完できる**（入力摩擦ゼロで記録を開始し、整理は試合後に回せる）。`duration_minutes` のみ COMPLETED 遷移時に必須化される点に注意（02 §E.3）。

### G.1c 選手グリッドの取得源【致命的指摘の根治】

ライブ記録の選手グリッド初期表示は、以下の**優先順位でフォールバック**する。

1. **roster 登録済み**（大会・F08.7.1 の `tournament_match_rosters` 由来。fixture 連携時の先発リスト）。
2. **チームメンバー一覧**（roster 未設定の練習試合等。team ドメインのメンバー一覧から取得）。
3. **手入力追加**（上記に無い選手・`player_name` 直接入力＝未登録選手）。

- 練習試合（roster 未設定）は ② を既定とする（フォールバック）。
- **「全員先発」ワンタップ**で表示中メンバーを一括 STARTER 化＋**先発選択画面（スタメン設定）**で個別調整。

### G.2 ライブ入力 UX（GoalNote 上位互換の核）

**設計目標**: 会場でスマホ片手・片手親指で、得点や交代を**3 タップで完了**できること（ADHD 配慮＝入力摩擦最小）。

#### リスト型タイムライン

- 画面中央に時系列イベントのリスト（最新が上）。各行はワンタップで編集ダイアログ、スワイプで削除（即時 undo トースト付き・G.5）。
- **得点 = 3 タップ**: [得点ボタン] → [得点者を選手グリッドから選択] → [任意でアシスト者選択 or スキップ]。
- **交代 = 3 タップ**: [交代ボタン] → [OUT 選手] → [IN 選手]。SUB_OUT/SUB_IN の 2 イベントを 1 操作で生成。
- **カード = 3 タップ**: [カードボタン] → [選手] → [黄/赤]（赤・2 枚目黄は out 確定・02 §E）。
- **タイムライン各行に記録チーム識別インジケーター**（自チーム/相手チームのアイコン・薄背景）を表示（G.10）。

#### 各ステップ UI 仕様（要改善の根治）

得点/交代/カードの各フローは**ボトムシート（モバイル）/ モーダル（広画面）**で表示し、以下を明確に配置する。

- **確定** / **スキップ**（任意ステップを飛ばす・得点のアシスト等）/ **キャンセル（戻る）** を各ステップに配置。
- **交代 OUT → IN 中の誤タップキャンセル**: OUT 選手を選んだ後 IN 選択中にキャンセルすると OUT も含めて操作全体を破棄（中途半端な SUB_OUT のみ残さない）。

#### タイマー状態機械（要改善の根治）

タイマーは明示的な状態機械で管理する。

```
WAITING → FIRST_HALF → HALF_TIME → SECOND_HALF → [EXTRA_FIRST → EXTRA_SECOND] → [PENALTY_SHOOTOUT] → COMPLETED
（[] = 任意ピリオド。延長・PK 戦は試合により省略される）
```

| 状態 | タイマー挙動 | 手動訂正 |
|------|-------------|---------|
| WAITING | 停止（00:00） | 不可 |
| FIRST_HALF | 動作（PERIOD_START 基準で minute 自動補完） | minute・stoppage を手動訂正可 |
| HALF_TIME | 停止 | 可 |
| SECOND_HALF | 動作 | 可 |
| EXTRA_FIRST/SECOND | 動作 | 可 |
| PENALTY_SHOOTOUT | 停止（PK は分概念なし） | — |
| COMPLETED | 停止 | イベント訂正は権限に従う（03 §C） |

- ピリオド切替ボタンで `period` を更新（PERIOD_START/PERIOD_END イベントを自動記録）。
- minute は数値ステッパー＋アディショナル（`+N`）入力欄で**手動訂正・編集・削除可**（タイマーずれ・後追い入力に対応）。

#### 選手グリッド

- 背番号＋名前の**大タップターゲット**（最低 44px＝2.75rem・G.13）のグリッド。
- **先発を上段・控えを下段**に配置（02 §E の is_starter 連動）。
- 取得源は G.1c（roster → メンバー一覧 → 手入力）。未登録選手は「手入力で追加」ボタン。

#### その他 UX

- **よく使うイベント大ボタン**（得点・交代・カード）を画面下部に固定配置。
- **即時 undo トースト**（直前操作の取り消し・G.5）。
- **WakeLock**（`useWakeLockWithFallback` 流用）で記録中の画面消灯を防止。
- **オフライン耐性は MVP に最低限組込む**（G.11）。
- 共同記録モードの 409（楽観ロック競合）UX は G.7。

### G.5 undo の定義（要改善の根治）

- **1 ステップ undo**（直前 1 操作の取り消し）を基本とする。
- **交代ペア（SUB_OUT + SUB_IN）の undo は原子的**（片方だけ残さず両方取り消す）。
- スワイプ削除 ＋ undo トーストを基本パターンとする。
- **相手チームが記録したイベントへの undo は不可**（自チーム分のみ・03 §C）。

### G.7 409（楽観ロック競合）UX（要改善の根治）

- 楽観ロックはイベント行単位なので衝突確率は低い（03 §C.5・02 §E.2）。
- 入力フォーム展開中に競合が起きても**フォームを閉じない**。「他の記録者が更新しました」と通知し、**確定押下時に再試行（リトライ）**する UX とする（入力中データを失わない）。
- サイレントに握りつぶさず（根治治療）、再取得後に差分を反映する。

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

### G.8 チャート空状態・null フォールバック（要改善の根治）

- 各チャートに**空状態**を用意する（「まだ試合記録がありません」＋作成 CTA）。データ 0 件で空のキャンバスを描かない。
- **`goalsPer90` の null フォールバック**: totalMinutes=0 で `goalsPer90=null`（02 §F.1・§未解決 4）のとき、ライン/radar のデータポイントは**除外** or 「—」表示とする（0 や NaN を描かない）。

### G.9 個人分析の発見可能性（要改善の根治）

- **マイページに「試合分析」タブを追加**（`pages/me/match-analytics.vue` への入口）。
- **チームメンバー一覧の各行に「分析」リンク**（`teams/[id]/members/[userId]/match-analytics.vue`・閲覧は 03 §C.4 の teamId 必須認可・F19.1 公開設定連動）。
- **ダッシュボードウィジェットから分析ショートカット**。

### G.10 相手分編集フィードバック（要改善の根治）

- タイムライン各行に**記録チーム識別インジケーター**（自/相手のアイコン・薄背景色）を表示。
- **相手チームが記録した行をタップ**すると「この記録は ○○ チームが入力しました。訂正は ○○ チームの記録者へ依頼してください」メッセージ（直接編集不可・03 §C.5 異議フロー導線）。

### G.11 オフライン耐性（MVP に最低限組込）【要改善の根治・殿裁可】

屋外会場前提（電波不安定）かつ GoalNote はオフライン動作のため、**MVP でも最低限**を含める。

- **送信失敗時のローカルキュー ＋ 再送**（`dexie` の軽量版を利用。イベント POST が失敗したら IndexedDB にキューし、通信復帰時に再送）。
- **通信エラー時の入力データ一時保持**（フォーム展開中に通信エラーでもフォームデータを消失させない）。
- フル同期（オフライン中の長時間記録・コンフリクト解決）は**後段 Phase**（[06](./06_implementation_plan.md) §I.1）。
- **殿よりマスターへリスク提示済**（会場の電波状況により優先度が変わる旨）。

### G.12 色覚多様性（軽微）

- カードは**色＋形状＋テキストラベル併用**（黄/赤の色だけに依存しない・形＋「警告」「退場」ラベル）。
- チャートは **Okabe-Ito 等の色盲フレンドリーパレット**を用いる。

### G.13 タップターゲット（軽微）

- 最小タップターゲットは **44px（＝2.75rem）で表記統一**（pt 表記は廃止）。
- 選手グリッドの**最大列数を定義**（画面幅に応じた折返し）。
- **最小サイズを CSS で保証**（`min-width`/`min-height: 2.75rem`）。

### G.4 / G.14 composable（配置を `composables/match/` に集約）

| composable | 配置 | 対象 API | 主メソッド |
|------------|------|----------|-----------|
| `useMatchApi` | `composables/match/useMatchApi.ts` | 単独試合 CRUD | `listMatches` / `getMatch` / `createMatch` / `updateMatch` / `changeStatus` / `deleteMatch` |
| `useMatchEventApi` | `composables/match/useMatchEventApi.ts` | イベント記録 | `listEvents` / `recordEvent` / `recordSubstitution` / `recordGoal` / `updateEvent` / `deleteEvent` / `listAppearances` |
| `useMatchAnalytics` | `composables/match/useMatchAnalytics.ts` | 集計取得 | `getUserStats` / `getUserTimeline` / `getTeamStats` |

- **`composables/match/` ディレクトリに集約**（既存 `useMatchRoster.ts`（F08.7.1・tournament roster）との混同を回避）。
- 型は `types/match.ts` に集約（**`any` 禁止**・CLAUDE.md）。生成型（`types/generated`）が整備されたら優先利用し、手動型は段階移行。
- composable のエラーは握りつぶさず `useNotification` で表示し再 throw（既存 `useMatchRoster` パターン踏襲）。

```ts
// types/match.ts（抜粋・any 禁止）
export type MatchKind = 'PRACTICE' | 'FRIENDLY' | 'TOURNAMENT' | 'LEAGUE'
export type TeamSide = 'HOME' | 'AWAY'
export type MatchStatus = 'SCHEDULED' | 'IN_PROGRESS' | 'COMPLETED' | 'POSTPONED' | 'CANCELLED'
export type MatchEventType =
  | 'STARTER' | 'SUB_IN' | 'SUB_OUT' | 'GOAL' | 'ASSIST' | 'OWN_GOAL'
  | 'PENALTY_GOAL' | 'PENALTY_MISS' | 'PENALTY_SHOOTOUT'
  | 'YELLOW_CARD' | 'RED_CARD' | 'SECOND_YELLOW'
  | 'SAVE' | 'INJURY' | 'PERIOD_START' | 'PERIOD_END'

export interface MatchResponse { id: string; kind: MatchKind; /* ... */ }
export interface MatchEventResponse { id: string; eventType: MatchEventType; minute: number | null; /* ... */ }
export interface UserMatchStatsResponse { totalMatches: number; goalsPer90: number | null; monthlyTrend: MonthlyStat[]; /* ... */ }
```

### G.15 採用する付加機能【殿裁可】

検分の付加価値提案のうち、以下を**採用する付加機能**として実装する（低コスト高価値を優先）。

- **(a) 「前回と同じ先発メンバー」ワンタップコピー**: 直近試合の STARTER を新試合の先発に一括コピー（G.1c の先発選択を高速化）。
- **(b) スコアボード常時表示**: ライブ記録画面上部にスコア＋ピリオド＋タイマーを常時表示。
- **(c) 出場時間タイムバー可視化**: `player_appearances`（in/out 区間）由来の出場タイムバー（低コスト・高価値）。
- **(d) 個人「自己ベスト」ハイライト**: 個人分析画面で最多得点試合・最長出場等の自己ベストを強調。

**実装 Phase 割当**（[06](./06_implementation_plan.md) §I.2）: (a) 前回先発コピー・(b) スコアボード常時表示は **Phase 3-C（ライブ記録 `live.vue`）**、(c) 出場時間タイムバー・(d) 自己ベストハイライトは **Phase 4-B（個人/チーム分析）** に割り当てる。

> クイックリプレイ（イベントの再生）等は**将来余地**として MVP 外。

### G.6 i18n

- `app/locales/{ja,en,zh,ko,es,de}/match.json` を**新設**（6 言語）。未翻訳はとりあえず日本語値で可、後で翻訳（CLAUDE.md i18n ルール）。
- **`nuxt.config.ts` の i18n `files` 配列（言語ごとに 6 ブロック）に `{lang}/match.json` を登録必須**（既存は言語別に列挙されている）。登録漏れはロード不能の典型バグ。
- UI 文字列は**直書き禁止**・必ず `$t('match.xxx')` で参照。

namespace 案:

| namespace | 用途 |
|-----------|------|
| `match.list` | 一覧（フィルタ・W/D/L ラベル・進行中バッジ・空状態） |
| `match.create` | 作成フォーム（クイックスタート・相手・モード・日時・会場） |
| `match.live` | ライブ記録（ボタン・タイマー状態・undo・409 トースト・相手分依頼メッセージ） |
| `match.analytics` | 分析画面（指標名・チャート凡例・期間・空状態・自己ベスト） |
| `match.event_type` | イベント種別ラベル（GOAL→「得点」等） |
| `match.card_type` | カード種別ラベル（YELLOW→「警告」等・形状ラベル併用） |

---

## 未解決事項（全項目解決済み／MVP外の先送り決定を含む）

1. **メンバーのキャリア統計閲覧範囲** — 解決済み（殿裁可）: `members/[userId]/match-analytics.vue` は **teamId 必須**で、`isAdminOrAbove(viewer, teamId)` ＋ 対象 user の当該 team 所属の二重検証。公開可否は **F19.1 個人プロフィール公開設定を正本**に連動（03 §C.4・02 §F.1）。
2. **ライブ入力のオフライン対応 Phase** — 解決済み（殿裁可）: **MVP に最低限（dexie 軽量版のローカルキュー＋再送・入力データ一時保持）を組み込む**。フル同期は後段（G.11）。殿よりマスターへリスク提示済。
3. **BaseChart の汎用度**: F08.10 専用にするか既存 `ActivityStatsPanel` も寄せるか（スコープ拡大注意）。MVP は F08.10 専用で新設し、共通化リファクタは別途（残る未解決・軽微）。
4. **WidgetTeamMatchSummary の min_role** — 解決済み（殿裁可）: **MEMBER 以上（SUPPORTER 除外）**で確定し F02.2.1 のウィジェットロール別可視性（min_role 正本）に登録（CI 双方向検証に乗せる・[06](./06_implementation_plan.md) §I.4）。
