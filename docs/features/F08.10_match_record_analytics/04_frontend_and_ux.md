# F08.10 / 04: フロントエンド画面・導線・UX・チャート・composable・i18n

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-13（多競技対応＝競技別 composable の動的 import §G.16・ターン制最小 UI §G.16a・観戦者ビュー §G.17 を追補）
> **関連機能番号**: F08.10（試合記録・分析）／ F02.2 ダッシュボード ／ F02.2.1 ウィジェット min_role ／ F19.1 個人プロフィール公開
> **関連ドキュメント**:
> - [02_playing_time_and_aggregation.md](./02_playing_time_and_aggregation.md) — 集計 API・チャート種別対応・空状態
> - [03_permissions_and_recording_modes.md](./03_permissions_and_recording_modes.md) — 画面の編集可否・他者統計閲覧の認可
> - 流用元: `frontend/app/composables/wallet-group-show/useWakeLockWithFallback.ts`（WakeLock）／ `frontend/app/components/activity/ActivityStatsPanel.vue`（chart.js 利用パターン）
> - [sports/01_soccer.md](./sports/01_soccer.md) — **サッカー固有 UX 細部**（プリセットボタン・GOAL⇔ASSIST 連鎖・理由コード選択 UI・タイマー状態機械の具体ピリオド・選手グリッド配置・チャート指標・i18n namespace・§8/§9）

本書は **G（フロントエンド画面・導線・UX）** を具体化する。
**ライブ入力 UX の骨格（4 入口・3 タップ・タイマー状態機械・イベント連鎖・undo・409・チャート枠組み・オフライン・a11y・composable 配置）は競技非依存のコア＝本書**。**競技固有の具体（プリセットボタンの並び・得点/アシスト/警告/交代の具体フロー・理由コード選択 UI・選手グリッドのポジション配置・サッカー向けチャート指標・i18n のサッカーラベル群）は [sports/01_soccer.md](./sports/01_soccer.md)（§8・§9）** に分離する。

---

## G. フロントエンド画面・UX

### G.1 画面一覧

| 画面 | パス | 役割 |
|------|------|------|
| 単独試合一覧 | `pages/teams/[id]/matches/index.vue` | チームの全試合（kind 別フィルタ・W/D/L 表示・進行中バッジ・**「＋試合を記録」FAB**＝入口 2・G.1a-2） |
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

### G.1a-2 入口（エントリーポイント）の統一設計【マスター御裁可】

**4 つの入口がすべて同一のライブ記録ページ（`live.vue` のタイムライン）に合流する**統一設計を採る。違いは **カードが事前生成（大会）か、その場起票（練習）か** だけであり、最終的に同じライブ記録ページに合流する。

| 入口 | 種別 | 導線・発見可能性 | 入力 | 合流先 |
|------|------|------------------|------|--------|
| **入口 1: 大会（リーグ/トーナメント）** | TOURNAMENT / LEAGUE | **大会の対戦表ページ（節（matchday）/会場・日付でグルーピング表示）から、自動生成済みの対戦カード（fixture）の「記録」ボタンを押下**（カードは事前生成済・05 §H）。押下時に `GET .../matches/by-fixture/{fixtureId}` で既存 match を解決し、既存があれば live へ復帰・無ければ `tournament_fixtureId` を引き継いで作成（二重起票防止・05 §H.0／06 §I.2） | カード由来（相手・日時は fixture から確定済） | → `live.vue` タイムライン |
| **入口 2: 練習試合 — 試合一覧の FAB** | PRACTICE / FRIENDLY | `pages/teams/[id]/matches/index.vue` の **「＋試合を記録」FAB**（画面右下固定） | 種別（練習/親善）＋相手名の最小入力（G.1b） | → 即 `live.vue` |
| **入口 3: 練習試合 — ダッシュボードのクイックアクション** | PRACTICE / FRIENDLY | F02.2 ダッシュボードの **クイックアクション「試合を記録」**（思い立った時の最短導線） | 同上（種別＋相手名） | → 即 `live.vue` |
| **入口 4: 練習試合 — カレンダー（F03.1）の予定から** | PRACTICE / FRIENDLY | F03.1 カレンダーの**予定詳細から「この試合を記録」**ボタン（予定登録済みの練習試合に表示） | **日時・相手を予定から引き継ぎ、入力ほぼゼロ**（`matches.schedule_id` で予定に紐付け・01 §B.1） | → 入力ほぼゼロで `live.vue` |

- **統一思想**: 入口 1 は「カードが事前生成済（fixture）」、入口 2〜4 は「その場起票（練習）」という違いだけで、**最終的に同じライブ記録ページ（`live.vue`）に合流する**。記録体験（3 タップ UX・タイマー・自動算出）は入口に依らず一貫する。
- **発見可能性**: 入口 2 = 試合一覧右下の FAB、入口 3 = ダッシュボード上部のクイックアクション群、入口 4 = カレンダー予定詳細のアクションボタン。いずれも「思い立った瞬間に最短で記録を開始できる」配置とする（ADHD 配慮・入力摩擦ゼロ）。
- **入口 4 の予定引き継ぎ**: F03.1 の予定（練習試合として登録済み）から起票すると、`matches.schedule_id` に当該予定 ID を保持し、`kickoff_at`（日時）・相手（`opponent_name`/`opponent_team_id`）・`venue` を予定から事前充填する（再入力不要）。予定に紐付かない起票（入口 2/3）では `schedule_id=NULL`。
- **入口 1 の段階出陣（実装フェーズ）**: 入口①は **第一陣（BE）= by-fixture 解決（`GET .../matches/by-fixture/{fixtureId}`）＋順位連携リスナー（`MatchCompletedEvent` → 既存 `updateScore` → 順位再計算・05 §H.0）を先行実装済み**。**第二陣（FE）= 大会の対戦表ページ（節/会場・日付グルーピング）の「記録」ボタン → by-fixture 解決 → live 合流の導線**は別出陣で実装する（06 §I.2）。FE は fixture の participant → team 解決を行い、記録時に `tournament_fixtureId` を必須で引き継ぐ。

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

#### リスト型タイムライン（骨格＝コア）

- 画面中央に時系列イベントのリスト（最新が上）。各行はワンタップで編集ダイアログ、スワイプで削除（即時 undo トースト付き・G.5）。
- **記録は 3 タップで完了する**のが骨格（種別ボタン → 選手 → 補助選択）。**競技ごとの具体的なボタン（得点/交代/カードの 3 タップフロー）は競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §8.1 参照**（サッカー＝[得点]/[交代]/[カード]の 3 タップ・カード確定で理由コード選択へ連続）。
- **タイムライン各行に記録チーム識別インジケーター**（自チーム/相手チームのアイコン・薄背景）を表示（G.10）。
- **タイムライン各行に「理由・メモ」（`note`）と連鎖（`linked_event_id`）を視覚化**して表示する（G.2a・G.2b）。

#### イベント種別の選択 UI（プリセット＋その他）— 骨格（コア）

- イベント種別は**選択式**: よく使う種別をプリセット大ボタンで提示し、**「＋その他」**で網羅する、という**骨格（プリセット＋その他パターン）が競技非依存のコア**。プリセット大ボタンは画面下部固定で 3 タップの速さを維持する。
- **競技ごとの具体的なプリセットボタンの並びは競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §8.1 参照**（サッカー＝`[得点]` `[アシスト]` `[警告]` `[交代]` の 4 プリセット ＋ `[その他]`）。
- `[その他]` を選ぶと **自由入力**（`custom_label`＝ラベル名 ＋ `note`＝理由メモ手書き）を受け付ける（`event_type=OTHER`・01 §D.2）。OTHER はスコア・出場時間に影響しない（競技非依存のコア機構。各競技の event_type 影響表はサッカー＝[sports/01_soccer.md](./sports/01_soccer.md) §2.1）。
- 各イベントには **「選手（背番号/名前）」＋「理由・メモ」自由記述枠（`note`）** を付帯できる（任意）。例: アシストに「7 番、コーナーキックから」。

#### G.2a イベントの時系列連鎖（双方向・linked_event_id）— 骨格（コア）

イベント連鎖の**機構**（2 つの独立イベントを `linked_event_id`（01 §B.2 自己参照）で双方向に結び、いずれの順序からでも連鎖を作れる）は**競技非依存のコア**。

- **連鎖の機構（コア）**: 連鎖する 2 イベントはそれぞれ固有の選手・背番号・理由（`note`）を持つ独立イベントであり、`linked_event_id` で双方向に結ぶ。一方を起点にしても他方を起点にしても同じ連鎖を作れる（速い道／物語る道）。連鎖（紐付け）は 3 タップに続く**任意の拡張ステップ**（スキップ可）。
- **集計は二重計上しない**: 02 §F の集計はイベント単体カウントであり、`linked_event_id` は表示・関連付けのメタ情報（01 §B.2）。
- **競技固有の具体（GOAL ⇔ ASSIST の連鎖フロー）は競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §8.2 参照**（サッカー＝得点起点「＋アシストを紐付け」／アシスト起点「→得点へつなぐ」）。

#### G.2b タイムライン表示での連鎖の束ね（骨格＝コア）

- `linked_event_id` で連鎖したイベントは、タイムライン上で**視覚的に束ねて**表示する（束ね表示・各行の `note` 併記）。**機構（束ね表示・一方削除で他方が単独化）が競技非依存のコア**。
- 連鎖の一方を削除しても他方は残る（`ON DELETE SET NULL`・01 §B.2）。残った側は連鎖表示が解除され単独イベントとして表示される。
- **競技固有の表示例（「7番 アシスト ⤵ / 9番 得点」等）は競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §8.2 参照**。

#### G.2c 警告・退場の理由コード選択（選択式＋補足メモ）— 骨格（コア）

警告/退場を選んだら、**当該競技の標準理由コードを選択式リスト**で提示する（`card_reason_code`・01 §B.2）。構造化コード＋補足の自由記述（`note`）の**両方**を 1 イベントに付けられる、という**骨格が競技非依存のコア**。

- **最短操作（ADHD 配慮）**: 「**コードをタップ → 確定**」で完了できる短い操作にする。3 タップの速さは維持し、理由コード選択は**その流れに続く任意ステップ**（スキップ可）。
- **さらに補足の自由記述（`note`）枠を併記**する。コードで分類しきれない事情はここに書く。
- **デフォルトは未選択可**（後で補完できる）。ただし**公式戦（記録係あり・03 §C.1）では理由コードの記録を推奨**する旨を UI で案内する（必須にはしない）。
- コード記号は言語非依存・固定。短ラベルは i18n（`match.card_reason.*`・G.6）で 6 言語表示する。
- **競技固有の具体（提示するコード一覧・選択肢ラベル）は競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §8.3 参照**（サッカー＝警告で `CautionCode` C1〜C8＋2 枚目黄は CS 併せ提示／退場で `SendingOffCode` S1〜S6・01 §D.5）。

#### G.2d タイムライン表示での理由コード表示（骨格＝コア）

- タイムライン各行で、警告/退場イベントは **カードアイコン（色＋形状）＋理由コード＋短ラベル＋選手**を束ねて表示する、という**骨格が競技非依存のコア**。理由コード未選択のカードはカード＋選手のみ表示。補足の `note` があれば併記。
- 色覚配慮（G.12）どおり色だけに依存せず、形状・テキストラベル（コード＋短ラベル）を併用する。
- **競技固有の表示例（「🟨 C2 ラフプレー（7 番）」等）は競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §8.4 参照**。

#### 各ステップ UI 仕様（要改善の根治）

得点/交代/カードの各フローは**ボトムシート（モバイル）/ モーダル（広画面）**で表示し、以下を明確に配置する。

- **確定** / **スキップ**（任意ステップを飛ばす・得点のアシスト等）/ **キャンセル（戻る）** を各ステップに配置。
- **交代 OUT → IN 中の誤タップキャンセル**: OUT 選手を選んだ後 IN 選択中にキャンセルすると OUT も含めて操作全体を破棄（中途半端な SUB_OUT のみ残さない）。

#### タイマー状態機械（要改善の根治）— 骨格（コア）

タイマーは明示的な状態機械で管理する。**状態機械の骨格（WAITING で停止 → ピリオド進行 → COMPLETED で停止／PERIOD_START 基準で minute 自動補完／ピリオド切替ボタンで `period` 更新＋PERIOD_START/PERIOD_END イベント自動記録／minute は数値ステッパー＋アディショナル `+N` で手動訂正・編集・削除可）が競技非依存のコア**。

- ピリオド切替ボタンで `period` を更新（PERIOD_START/PERIOD_END イベントを自動記録）。
- minute は数値ステッパー＋アディショナル（`+N`）入力欄で**手動訂正・編集・削除可**（タイマーずれ・後追い入力に対応）。
- **競技ごとの具体的なピリオド遷移（前半→ハーフタイム→後半→延長→PK 等）と各状態のタイマー挙動表は競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §8.5 参照**（サッカー＝`WAITING → FIRST_HALF → HALF_TIME → SECOND_HALF → [EXTRA_FIRST → EXTRA_SECOND] → [PENALTY_SHOOTOUT] → COMPLETED`）。多競技ではクォーター制等、各競技カタログの period に従う。

#### 選手グリッド（骨格＝コア）

- 背番号＋名前の**大タップターゲット**（最低 44px＝2.75rem・G.13）のグリッド。
- **先発を上段・控えを下段**に配置（02 §E の is_starter 連動）。
- 取得源は G.1c（roster → メンバー一覧 → 手入力）。未登録選手は「手入力で追加」ボタン。
- **競技ごとのポジション配置（先発の並び順＝GK/DF/MF/FW 等）は競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §7・§8.5 参照**。

#### その他 UX

- **よく使うイベント大ボタン**（プリセット＋「その他」）を画面下部に固定配置（種別選択 UI・G.2・**具体ボタンは [sports/01_soccer.md](./sports/01_soccer.md) §8.1**）。
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

- チャート種別（radar/line/doughnut/bar）と `BaseChart` ラッパーの枠組みが競技非依存のコア。**各チャートに差し込む競技固有の指標（radar の守備軸・doughnut のポジション傾向 GK/DF/MF/FW 等）は競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §8.6 参照**。

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

### G.16 競技別 composable の分割と動的 import（lazy-load）【多競技の保守性・バンドル肥大化回避】

多競技の進行管理（タイマー/セット/ターン）は状態モデルが根本的に異なるため、**共通シェル（`live.vue`）＋競技別 composable** に分割し、**競技モジュールを動的 import（lazy-load）で遅延読込**してバンドル肥大化を防ぐ。

- **共通シェル**: `live.vue` は薄いオーケストレータ（既存設計踏襲）。イベント記録 API・選手グリッド・undo・オフラインキュー・観戦配信受信（G.17）等の**競技非依存の骨格**を持つ。
- **競技別 composable（状態モデル類型ごと・01 §D.6）**:

  | composable | 状態モデル類型 | 対象競技 | 役割 |
  |------------|----------------|----------|------|
  | `useMatchTimerSoccer` | CONTINUOUS_TIME | SOCCER / FUTSAL | 前後半・延長・PK のタイマー状態機械（既存・[sports/01_soccer.md](./sports/01_soccer.md) §8.5。フットサルは流用＝[sports/02_futsal.md](./sports/02_futsal.md) §8.5） |
  | `useMatchTimerBasketball` | CONTINUOUS_TIME | BASKETBALL | 4 クォーター＋OT（複数回 OT）の状態機械（[sports/03_basketball.md](./sports/03_basketball.md) §8.5） |
  | `useMatchSetTracker` | SET_BASED | VOLLEYBALL | セット進行・デュース判定・獲得セット数・試合終了判定（[sports/04_volleyball.md](./sports/04_volleyball.md) §8.5。タイマーではない） |
  | `useMatchTurnTracker` | TURN_BASED | SHOGI / GO | 最小遷移（WAITING→IN_PROGRESS→COMPLETED）・手数/勝者/勝ち方/写真/コメント管理（タイマー無し・[sports/05_shogi.md](./sports/05_shogi.md) §8.5・[sports/06_go.md](./sports/06_go.md) §8.5） |

- **動的 import（バンドル肥大化回避）**: `live.vue` は `matches.sport`／`state_model`（01 §D.6）に応じて**競技モジュールを `import()` で遅延読込**する（例: `const { useTimer } = await import(\`~/composables/match/sport/\${moduleName}\`)`）。全競技の composable・イベント入力シート・i18n を初期バンドルに同梱しない（サッカーしか使わないユーザーがバスケ/将棋のロジックを読込まない）。Nuxt の動的 import（コード分割）に乗せる。
- **競技別イベント入力シート（カタログ駆動）**: イベント種別のプリセットボタン（コア 04 §G.2 の「プリセット＋その他」骨格）は、`SportEventCatalog`（01 §D.3）由来の競技別プリセット定義（FE 側のカタログ定数 or BE から取得）で**カタログ駆動**で描画する。各競技固有のプリセット並びは sports/0N §8.1 が正準（サッカー＝得点/アシスト/警告/交代、バスケ＝2P/3P/FT/リバウンド/ファウル/交代 等）。入力シート本体も動的 import で遅延読込。
- **保守性**: 新競技追加時は (1) 競技 composable を 1 つ追加、(2) カタログ定数に競技を追加、(3) sports/0N 文書を雛形複製、で済む。共通シェル `live.vue` は変更不要（状態モデル類型のいずれかに属する限り）。

### G.16a ターン制（将棋/囲碁）の最小 UI

ターン制は球技のライブタイムライン UI を流用しない（タイマー・選手グリッド・3 タップタイムラインを表示しない）。**最小の結果入力 UI**とする（[sports/05_shogi.md](./sports/05_shogi.md) §8.1）。

- **個人戦**: 対局者（先手/後手・黒/白）→ 勝者選択 → 勝ち方選択（競技別カタログ・01 §D.7）→ 任意で総手数・目数差（囲碁）・局面写真・コメント。タイマー無し・手数任意。
- **団体戦**: ボード数 → 各ボードの対戦カード入力 → 各ボード勝者＋勝ち方 → 親 match 勝敗をボード勝ち星から自動導出（01 §B.6・[sports/05_shogi.md](./sports/05_shogi.md) §4.3）。
- **局面写真**: 既存添付基盤（presign・SVG 除外・サイズ上限・IDOR）を流用（01 §B.7・03 §C.7a）。

### G.17 観戦者ビュー（STOMP 購読・read-only）【WebSocket ライブ観戦】

WebSocket ライブ観戦（[07_realtime_spectator.md](./07_realtime_spectator.md)）の観戦者側 UX。

- **観戦専用ビュー**: 試合詳細/ライブ画面を**観戦モード（read-only）**で開くと、`/topic/matches/{matchId}/live` を STOMP 購読し、記録者の HTTP 書き込みがコミット後に配信される差分でタイムライン/スコアをリアルタイム更新する。観戦者は記録ボタン・編集 UI を**一切持たない**（書き込みは記録者の HTTP のみ・07 §J.1）。
- **初期スナップショット＋差分追従**: 購読確立後にまず HTTP で現在状態を取得（`GET /matches/{matchId}`＋`/events`・02 §F.4）→ 以後 topic の差分（`serverSeq` 付き・07 §J.2.1）で追従。**再接続時は HTTP スナップショット再取得**してから差分購読を再開（取りこぼし回復・07 §J.4）。`serverSeq` の飛びを検知したらスナップショット再取得。
- **購読拒否のフィードバック**: 可視性が無い試合（F00・03 §C.8）の購読は BE が拒否する（ERROR フレーム）。FE は「この試合は観戦できません（公開範囲外）」を表示し、HTTP の閲覧可否（`canView`）と一貫した見え方にする。
- **接続状態インジケーター**: 「ライブ接続中／再接続中／オフライン（HTTP 表示）」を表示。WebSocket が落ちても HTTP の最新スナップショットで閲覧は継続（グレースフルデグレード・07 §J.1）。
- **記録者と観戦者の同一画面**: 記録権限のあるユーザーは記録 UI、無いユーザーは観戦ビュー、を `canView`/`canRecordTimeline`（03 §C.3.2）で出し分ける（同じ `live.vue` の権限分岐）。

---

`MatchEventType` 等のユニオン型は**全競技のイベント値を保持する器（コア）**であり、各競技がどの値を使うかは `SportEventCatalog`（01 §D.3）で定義される（各競技の具体集合は sports/0N §2＝サッカーは [sports/01_soccer.md](./sports/01_soccer.md) §2、バスケは [sports/03_basketball.md](./sports/03_basketball.md) §2 等）。**competition 別 namespace の i18n 拡張方針は G.6**（競技を判別してラベルを引く）。

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
  | 'OTHER'

export interface MatchResponse { id: string; kind: MatchKind; /* ... */ }
export interface MatchEventResponse {
  id: string
  eventType: MatchEventType
  minute: number | null
  note: string | null          // 理由・メモ（01 §B.2）
  cardReasonCode: string | null // 警告/退場の標準理由コード C1〜C8 / S1〜S6 / CS（01 §B.2・§D.5）
  customLabel: string | null   // event_type=OTHER 時の自由ラベル名（01 §B.2）
  linkedEventId: string | null // 連鎖の相手イベント UUID（01 §B.2）
  /* ... */
}
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

- `app/locales/{ja,en,zh,ko,es,de}/match.json` を**新設**（6 言語）。**この `match.json` ファイル自体は競技共通**（コアが新設・nuxt.config 登録もコア側）。未翻訳はとりあえず日本語値で可、後で翻訳（CLAUDE.md i18n ルール）。
- **`nuxt.config.ts` の i18n `files` 配列（言語ごとに 6 ブロック）に `{lang}/match.json` を登録必須**（既存は言語別に列挙されている）。登録漏れはロード不能の典型バグ。
- UI 文字列は**直書き禁止**・必ず `$t('match.xxx')` で参照。
- `match.json` は `nuxt.config.ts` の `files` 配列へ登録するため、**理由コード・ポジション等の競技固有ラベルも match.json に内包し、競技固有用に新規ファイル登録は不要**。

namespace（competition-common と sport-specific の所在）:

| namespace | 区分 | 用途・所在 |
|-----------|------|-----------|
| `match.list` | 共通 | 一覧（フィルタ・W/D/L ラベル・進行中バッジ・空状態） |
| `match.create` | 共通 | 作成フォーム（クイックスタート・相手・モード・日時・会場） |
| `match.live` | 共通 | ライブ記録（ボタン・タイマー状態・undo・409 トースト・相手分依頼メッセージ・種別選択／その他／理由・メモ（note）／連鎖ラベル／理由コード選択 UI のラベル・推奨案内）。**個別の種別/コードラベルは競技固有 namespace を参照** |
| `match.analytics` | 共通 | 分析画面（指標名・チャート凡例・期間・空状態・自己ベスト） |
| `match.event_type` | **競技固有** | イベント種別ラベル（GOAL→「得点」・OTHER→「その他」等）。**サッカーのラベル群 → [sports/01_soccer.md](./sports/01_soccer.md) §9 参照** |
| `match.card_type` | **競技固有** | カード種別ラベル（YELLOW→「警告」等・形状ラベル併用）。**→ [sports/01_soccer.md](./sports/01_soccer.md) §9 参照** |
| `match.card_reason` | **競技固有** | 警告/退場の理由コード短ラベル（`C1`…`C8`/`S1`…`S6`/`CS`・01 §D.5）。コード記号は言語非依存・固定、説明文を 6 言語翻訳。**サッカーのラベル群 → [sports/01_soccer.md](./sports/01_soccer.md) §9 参照** |
| `match.position` | **競技固有** | ポジション語彙ラベル（サッカー GK/DF/MF/FW・バスケ PG/SG/SF/PF/C・バレー OH/OP/MB/S/L・フットサル GK/FIXO/ALA/PIVO 等）。**各競技のラベル群 → sports/0N §7・§9 参照**（盤上は不使用） |
| `match.win_method` | **競技固有（ターン制共通 namespace）** | 勝ち方ラベル（将棋＝投了/詰み/千日手 等・囲碁＝投了〔中押し〕/目数差勝ち 等）。**→ [sports/05_shogi.md](./sports/05_shogi.md) §9・[sports/06_go.md](./sports/06_go.md) §9 参照** |
| `match.set` | 共通（セット制） | セット制共通ラベル（「第 N セット」・デュース・セット確定・獲得セット数）。**バレー → [sports/04_volleyball.md](./sports/04_volleyball.md) §9** |
| `match.board` | 共通（団体戦） | 団体戦のボード順ラベル（大将/副将/主将 等・board_number 表示）。**将棋/囲碁 → sports/05_shogi.md §9・06_go.md §9** |

> **competition 別 namespace の拡張方針**: 競技固有 namespace（`match.event_type`/`match.card_reason`/`match.position`/`match.win_method`）は、**競技を判別してラベルを引く**（例 `match.event_type.{sport}.{key}` または `match.sport='BASKETBALL'` 時にバスケのラベル集合を引く FE マッピング）。**`match.json` ファイル自体は競技共通**（コアが新設・新規ファイル登録不要）であり、namespace の中身が競技別。新競技追加時は当該競技のラベル群を `match.json` の namespace に追記する（6 言語・i18n ルール）。

---

## 未解決事項（全項目解決済み／MVP外の先送り決定を含む）

1. **メンバーのキャリア統計閲覧範囲** — 解決済み（殿裁可）: `members/[userId]/match-analytics.vue` は **teamId 必須**で、`isAdminOrAbove(viewer, teamId)` ＋ 対象 user の当該 team 所属の二重検証。公開可否は **F19.1 個人プロフィール公開設定を正本**に連動（03 §C.4・02 §F.1）。
2. **ライブ入力のオフライン対応 Phase** — 解決済み（殿裁可）: **MVP に最低限（dexie 軽量版のローカルキュー＋再送・入力データ一時保持）を組み込む**。フル同期は後段（G.11）。殿よりマスターへリスク提示済。
3. **BaseChart の汎用度**: F08.10 専用にするか既存 `ActivityStatsPanel` も寄せるか（スコープ拡大注意）。MVP は F08.10 専用で新設し、共通化リファクタは別途（残る未解決・軽微）。
4. **WidgetTeamMatchSummary の min_role** — 解決済み（殿裁可）: **MEMBER 以上（SUPPORTER 除外）**で確定し F02.2.1 のウィジェットロール別可視性（min_role 正本）に登録（CI 双方向検証に乗せる・[06](./06_implementation_plan.md) §I.4）。
