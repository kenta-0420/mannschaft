# F08.7.1 / 02: 成績ウィジェット（3 種）

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-05-31
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要・トレーサビリティ
> - [F02.2_dashboard.md](../F02.2_dashboard.md) — マイダッシュボード（widget_key 一覧の正本・データソース）
> - [F02.2.1_dashboard_widget_role_visibility.md](../F02.2.1_dashboard_widget_role_visibility.md) — ロール別可視性（min_role 表の正本・CI 双方向検証）
> - [F22.1_swipe_scope_dashboard/04_widgets.md](../F22.1_swipe_scope_dashboard/04_widgets.md) — 横スワイプ版（`SWIPE_` 別名前空間・混同防止）
> - [F08.7_tournament_league.md](../F08.7_tournament_league.md) — 大会成績・順位表 API（データソース）

本書は確定要件 ⑤（成績ウィジェット 3 種＝チーム=自チーム成績／組織=主催大会サマリ／順位表、表示 ON/OFF は各団体が設定）を具体化する。

---

## 1. 概要・名前空間

ダッシュボードに大会成績ウィジェットを 3 種追加する。これらはすべて **F02.2 系の詳細ダッシュボード**（チーム/組織ダッシュボードページ）に置く。

> **名前空間の整理（混同防止）**: F22.1 横スワイプダッシュボードの厳選 8 ウィジェットは `SWIPE_` プレフィックスの別名前空間であり、本書の成績ウィジェットとは**混ぜない**。成績ウィジェットは F02.2 系（詳細ダッシュボード）に属する。F22.1 側 `04_widgets.md` にもこの旨の相互参照を 1 行追加する（README §B 参照）。

可視性インフラ（`dashboard_widget_role_visibility` ＝admin 設定 / `dashboard_widget_settings` ＝個人の表示 ON/OFF・並び順）は既存で、**`WidgetKey` enum に 3 件追加するだけ**で各団体の表示 ON/OFF 設定に自動で乗る（テーブル変更不要・`widget_key VARCHAR(50)`）。

---

## 2. ウィジェット定義表

| ウィジェット | scope | FE key（kebab-case） | BE `WidgetKey` enum | デフォルト min_role | 使用 API | 既存流用 / 新規 |
|------------|:-----:|---------------------|--------------------|:------------------:|---------|--------------|
| 自チーム成績 | TEAM | `team-standings-record` | `TEAM_TOURNAMENT_RECORD` | SUPPORTER | 既存 `GET /api/v1/teams/{id}/tournament-stats` ＋ `/tournament-history` | 既存流用 |
| 主催大会サマリ | ORGANIZATION | `org-tournament-summary` | `ORG_TOURNAMENT_SUMMARY` | MEMBER | **新設** `GET /api/v1/organizations/{orgId}/tournaments/summary` | 新規 |
| 順位表 | TEAM | `team-division-standings` | `TEAM_DIVISION_STANDINGS` | SUPPORTER | 既存 `getTeamTournamentHistory` → 最新エントリの org/tournament/division id → `getStandings` の 2 段 | 既存流用 |

### 2.1 各ウィジェットの内容

#### ① 自チーム成績（`TEAM_TOURNAMENT_RECORD` / team）
- 自チームの大会通算成績（通算勝/分/敗・勝点・得失点・参加大会数）と直近の順位履歴を表示。
- データソース＝既存 `GET /teams/{id}/tournament-stats`（通算）＋ `GET /teams/{id}/tournament-history`（順位履歴）。**新規 API 不要**。
- `linkTo`: `/teams/{id}/tournaments`（既存の参加履歴ページ）。

#### ② 主催大会サマリ（`ORG_TOURNAMENT_SUMMARY` / organization）
- 組織が主催する各大会 × 各部の「首位チーム名・参加チーム数・大会 status」だけを一覧表示（運営の俯瞰用）。
- データソース＝**新設** `GET /api/v1/organizations/{orgId}/tournaments/summary`。

```jsonc
// レスポンス例（N+1 回避：1 クエリで首位・参加数を集約）
{
  "data": {
    "tournaments": [
      {
        "tournament_id": 12, "name": "大分県リーグ 2026", "status": "IN_PROGRESS",
        "divisions": [
          { "division_id": 30, "name": "1部", "participant_count": 8, "leader_team_name": "FC大分" },
          { "division_id": 31, "name": "2部", "participant_count": 8, "leader_team_name": "別府SC" }
        ]
      }
    ]
  }
}
```

- **N+1 回避**: 各大会×各部の首位は順位計算済みの `tournament_standings`（または順位ビュー）から「`rank=1` の行のみ」を IN 句バッチ取得する。参加数は `GROUP BY division_id COUNT`。大会本体ループ内で個別クエリを撃たない。
- `linkTo`: `/organizations/{id}/tournaments`。

#### ③ 順位表（`TEAM_DIVISION_STANDINGS` / team）
- 自チームが**現在参加中**のディビジョンの順位表（全チームの順位・勝点・勝分敗）を表示。
- データソース＝既存 2 段:
  1. `getTeamTournamentHistory(teamId)` → 最新（進行中優先）エントリの `organizationId` / `tournamentId` / `divisionId` を取得。
  2. `getStandings(tournamentId, divisionId)` で順位表本体を取得。
- 複数大会に同時参加している場合はウィジェット内セレクタで切替（§5.1）。
- `linkTo`: `/teams/{id}/tournaments`（該当大会の順位表へ）。

---

## 3. データソースの org 非依存（要件⑧との整合）

順位表・通算成績の集計は **team_id 串刺し**で行われ、organization で絞っていないことが前提（03 §8 で検証）。これにより、別組織の大会へ移籍したチームでも通算成績・順位履歴が連続して表示される。本書のウィジェットはこの前提に乗る（追加実装不要）。

---

## 4. 編集箇所

### 4.1 バックエンド（必須）

- `backend/.../dashboard/WidgetKey.java` に enum **3 件追加**: `TEAM_TOURNAMENT_RECORD` / `ORG_TOURNAMENT_SUMMARY` / `TEAM_DIVISION_STANDINGS`。
  - これは admin 可視性 UI の源泉（`forScope()`）であり、**追加必須**。enum に無いと F02.2.1 の可視性設定画面に出ず、CI 双方向整合性テストも落ちる。
- `MODULE_SLUG_MAP`（`WidgetKey.java:81`・実コードで確認）に大会モジュール依存を登録 → **大会モジュール未導入の団体にはウィジェットが出ない**ようにする。
  - **モジュールスラッグ名は推測で断定しない（Y-1 訂正）**。現行 `MODULE_SLUG_MAP` には `performance`/`project`/`chat`/`analytics` の 4 スラッグのみ登録があり、**大会用スラッグは未登録**（実装時に新規登録が必要）。スラッグの正式名（`tournament` か別名か）・モジュール番号（「#14」は仮）は、**実装時に `WidgetKey.java` の `MODULE_SLUG_MAP` および選択式モジュール定義の正本を grep して確定**すること。本書では仮に `tournament` と記すが、これは確定値ではない。
- 新設 API `GET /api/v1/organizations/{orgId}/tournaments/summary`（②用）。Controller / Service / DTO を tournament ドメインに追加。認可は組織所属 MEMBER 以上（§6 の min_role に従う）。`@Transactional(readOnly=true)` は tournament ドメイン内に閉じる。

> **【path 変数の scope id は slug を受理する（必須）】** ①の `GET /teams/{teamId}/tournament-stats`・`GET /teams/{teamId}/tournament-history`、②の `GET /api/v1/organizations/{orgId}/tournaments/summary` は、ダッシュボードが URL 識別子（slug。例 `team-000017` / `org-000001`）を渡す。Controller は `@PathVariable String` で受け、`teamService.resolveTeamId(slug)` / `organizationService.resolveOrgId(slug)`（survey の `resolveScopeId` 流儀）で内部 BIGINT に解決してから認可・サービスへ渡すこと。`@PathVariable Long` のままだと Spring の型変換に失敗して 400 となり、ウィジェットが（`captureQuiet`+空配列で握り潰され）空表示になる。なお③順位表の `getStandings(orgId, ...)` の `orgId` は `tournament-history` レスポンスの `organizationId`（数値 BIGINT）から FE が取得して渡すため、こちらの org 系 path（standings/matrix/rankings）は数値のまま（`@PathVariable Long`）でよい。

### 4.2 フロントエンド

- `frontend/app/composables/useDashboardWidgets.ts`: `ALL_WIDGETS` ＋ `WidgetKeyMap` ＋ `WidgetDefaultMinRoleMap` に 3 件追加。
- `frontend/app/components/ScopeDashboard.vue`: `DATA_WIDGET_KEYS` に 3 キー（成績は横長のため `col-span=2`）、データ描画分岐、`linkTo`（§2.1 の各遷移先）を追加。
- 新規コンポーネント 3 つ:
  - `components/widgets/WidgetTeamTournamentRecord.vue`
  - `components/widgets/WidgetOrgTournamentSummary.vue`
  - `components/widgets/WidgetTeamDivisionStandings.vue`

### 4.3 i18n

- `frontend/app/locales/{ja,en,zh,ko,es,de}/tournament.json` にウィジェット内ラベルを追記（6 言語必須・未翻訳は日本語流用）。追加キー例:

```jsonc
{
  "dashboard_widgets": {
    "team_record_title": "大会成績",
    "org_summary_title": "主催大会サマリ",
    "division_standings_title": "順位表",
    "rank": "順位", "points": "勝点", "win_draw_loss": "勝/分/敗",
    "leader": "首位", "participant_count": "参加チーム数",
    "select_tournament": "大会を選択",
    "empty_team_record": "参加した大会がまだありません",
    "empty_org_summary": "主催している大会がまだありません",
    "empty_division_standings": "現在参加中の大会がありません"
  }
}
```

> ウィジェットの**タイトル文字列**自体は F02.2 既存仕様どおり `$t` 非経由のリテラル運用（ScopeDashboard 側の既存パターン）に合わせる。ウィジェット**内部**のラベル（順位/勝点/空状態等）は上記 i18n を必ず経由する（直書き禁止）。

---

## 5. 留意点

### 5.1 順位表の縦長・複数大会切替
- 順位表は縦長になりやすいため `max-h-96 overflow-y-auto` 制約内に収める（モバイル配慮）。
- 複数大会に同時参加する場合、ウィジェット内セレクタで大会/ディビジョンを切り替える。**ソート基準＝「進行中（IN_PROGRESS）優先 → 最新 startsAt 降順」**。デフォルト選択は最上位（最も新しい進行中の大会）。

### 5.2 空状態
- いずれのウィジェットも、データ 0 件時はフラグで握りつぶさず**正直に空状態**を表示する（§4.3 の i18n キー）。

### 5.3 PUBLIC 露出時の非公開大会保護（セキュリティ精査項目）
- F02.2.1 の管理者設定で min_role を `PUBLIC` に下げた場合でも、**ウィジェット API 側で大会の visibility を再チェック**し、非公開（draft / private）大会の成績が PUBLIC 閲覧者に漏れないようにする。
- ②主催大会サマリは特に注意：未公開（DRAFT）の大会は PUBLIC 閲覧者向けレスポンスから除外する。
- これは docs/security のスコープ露出方針（README §B で追記）と整合させる。

---

## 6. 可視性インフラとの連携（CI 双方向検証）

- 3 ウィジェットの表示/非表示・並び順は既存 `dashboard_widget_settings` を `widget_key = 'TEAM_TOURNAMENT_RECORD'` 等でそのまま使用（テーブル変更なし）。
- ロール別可視性（`dashboard_widget_role_visibility` / F02.2.1）も新 key 単位で適用。デフォルト min_role:

| widget_key | scope | デフォルト min_role | 理由 |
|-----------|:-----:|:------------------:|------|
| `TEAM_TOURNAMENT_RECORD` | TEAM | SUPPORTER | 自チームの大会成績は広報・サポーター関心層に見せてよい |
| `TEAM_DIVISION_STANDINGS` | TEAM | SUPPORTER | 順位表は公開性が高い（リーグ表は本来公開情報） |
| `ORG_TOURNAMENT_SUMMARY` | ORGANIZATION | MEMBER | 主催運営の俯瞰情報。参加数・首位は内部運用寄り |

- **CI 双方向整合性テスト**（FE `WidgetKeyMap` ⇔ BE `WidgetKey`、`WidgetDefaultMinRoleMap` ⇔ フロント定義）に通すため、本書の追加 3 件は **F02.2 のウィジェット一覧表**と **F02.2.1 の完全対応表・デフォルト min_role 表**の両方に同期する（README §B で実施）。同期漏れは CI で検出される。

---

## 7. 精査ログ

### 7.1 1 回目
- **不備**: 3 ウィジェットの FE key / BE enum / API / linkTo / 空状態を §2〜§5 で網羅。BE enum 追加必須を明記。
- **セキュリティ**: PUBLIC 露出時の非公開大会再チェック（§5.3）、min_role デフォルトをセキュア寄りに（§6）。
- **ユーザビリティ**: モバイル縦長制約、複数大会セレクタ、空状態（§5）。
- **見落とし**: MODULE_SLUG_MAP 登録（未導入団体に出さない）、i18n 6 言語、CI 双方向同期（F02.2/F02.2.1）。
- **保守性**: テーブル変更ゼロ（enum 追加のみ）、新設 API の N+1 回避、`@Transactional` ドメイン内。

### 7.2 未解決事項

**現時点でなし。**
