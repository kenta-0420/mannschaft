# F08.10 / 03: 記録モード・編集権限・セキュリティ・IDOR・F00 可視性

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-08
> **関連機能番号**: F08.10（試合記録・分析）／ F00（コンテンツ可視性・ロール基盤）／ F19.1 個人プロフィール公開
> **関連ドキュメント**:
> - [01_domain_and_ddl.md](./01_domain_and_ddl.md) — `recorded_by_team_id` / `owning_team_id` / `scorekeeper_user_id` / `has_scorekeeper` / 二段アクセス（§A.4）
> - [02_playing_time_and_aggregation.md](./02_playing_time_and_aggregation.md) — 集計 API の認可・破壊耐性（§E.5a）
> - [docs/security/03_role_authority_model.md](../../security/03_role_authority_model.md) — ロール・権限モデル（AccessControlService / @accessGuard SpEL）
> - [docs/features/F00_content_visibility_resolver.md](../F00_content_visibility_resolver.md) — `ContentVisibilityResolver` / `ReferenceType` / `ContentVisibilityChecker`
> - 実装基盤: `com.mannschaft.app.common.AccessControlService`（メンバーシップ検証・ロール判定）／ `com.mannschaft.app.common.visibility.ContentVisibilityResolver`
> - [sports/01_soccer.md](./sports/01_soccer.md) — サッカーの理由コード具体値（C/S）と event_type↔コード対応（§5.3・本書 §C.4b の検証規約が参照する競技カタログ）

本書は **C（記録モードと権限）** を具体化する。最重要章。
本書（記録モード・編集権限・IDOR・F00 可視性・テナント分離・入力検証の**枠組み**）は**ほぼ全てが競技非依存のコア**である。唯一、`card_reason_code` の**具体コード一覧**だけが競技依存のため [sports/01_soccer.md](./sports/01_soccer.md) §5 を参照する形にし、検証規約は「**その競技カタログの列挙値かつ event_type 整合**」という汎用表現で本書に残す（§C.4b）。

---

## C. 記録モードと権限

### C.1 2 つの記録モード

`matches.has_scorekeeper`（および `scorekeeper_user_id`）で記録モードを表す。

| モード | フラグ | 説明 | 実運用 |
|--------|--------|------|--------|
| **公式戦（記録係あり）** | `has_scorekeeper=TRUE` | ホーム/主催が試合を作成し、**記録係**（会場運営・記録担当のユーザー）がタイムラインを単独入力 | 大会・リーグの会場記録係に一致 |
| **記録係なし（共同記録）** | `has_scorekeeper=FALSE` | 練習・親善・記録係不在の試合。**どちらのチームもタイムラインに記録可**（共同記録） | 練習試合・親善試合 |

- モードは試合作成時に決定し、作成者（ホーム/主催）が後から切替可能（権限は C.3）。
- 公式戦では `scorekeeper_user_id` に記録係を指定する。記録係は作成者本人でも別ユーザーでもよい。

### C.2 編集・訂正権限の分界

| 対象 | 権限 | 判定列 |
|------|------|--------|
| 試合メタ情報（日時・会場・**最終スコア** `home_score`/`away_score`/PK スコア・`status`・`duration_minutes`・モード切替） | **作成者（ホーム/主催/記録係）のみ** | `matches.created_by` ／ `scorekeeper_user_id` ／ 主体チーム ADMIN/DEPUTY |
| タイムラインイベント記録（公式戦） | **記録係のみ** | `scorekeeper_user_id` |
| タイムラインイベント記録（共同記録） | **両チームの ADMIN/DEPUTY**（自チーム分） | `match_events.recorded_by_team_id` |
| 自チーム選手の出場・交代・スタッツ訂正 | **当該チーム ADMIN/DEPUTY のみ（自チーム分）** | `player_appearances.owning_team_id` ／ `match_events.recorded_by_team_id` |
| **相手チーム分・スコア**の訂正 | **不可**（記録係/主催へ依頼・C.5 異議フロー） | — |
| 閲覧（試合統合表示・スタッツ） | F00 可視性に従う（`MatchVisibilityResolver`・C.3） | — |

#### 権限ルールの要点

- 各チームは**自チーム分のみ**訂正可。`owning_team_id` / `recorded_by_team_id` が自チームでない行への UPDATE/DELETE/INSERT は **403**。
- **スコア（`home_score`/`away_score`/PK スコア）は作成者/記録係のみが確定**する。相手チームはスコアを書き換えられない（依頼ベース・C.5）。スコアキャッシュ更新は `matches.version` 非依存（02 §E.2）、最終確定（メタ更新）のみ `matches.version` を用いる。
- イベントの得点（GOAL）を相手チームが入れたい場合（共同記録で相手の得点を記録）も、`recorded_by_team_id` は記録した側のチームを保持し、訂正権限は記録した側＋作成者に限定する。
- 閲覧は 1 試合として統合表示し、両チーム・各選手にスタッツを共有する。**DB の所有（owning_team_id 等）はユーザーに不可視**（UI には「自分が編集できるか」だけを露出）。

### C.3 認可基盤への実装方針【@EnableMethodSecurity の事実訂正・F00 具体実装】

#### C.3.1 @EnableMethodSecurity は既に有効（事実訂正）

> **【重要・事実訂正】** 起草時の「@EnableMethodSecurity は現状無効」という記述は**誤り**である。**SecurityConfig で既に `@EnableMethodSecurity(prePostEnabled=true)` が有効化されている（認可基盤根治 Phase 3 / PR #1266・2026-06-02）**。97 個の `@PreAuthorize` が実効化済み。

- ただし **per-scope ロール（チーム ADMIN/DEPUTY）は JWT に搭載されていない**ため、`@PreAuthorize("hasRole('ADMIN')")` のような JWT 権限ベースの SpEL は使えない（JWT に per-scope の ADMIN は無い）。
- per-scope の認可は **`AccessControlService` / `@accessGuard` 形式の SpEL ガード**（`docs/security/03_role_authority_model.md §3.3`）を使う。
  - **実コードの `AccessGuard` 公開メソッドは第一引数が `Authentication`**: `isScopeAdmin(authentication, scopeId, scopeType)` / `isScopeMember(authentication, scopeId, scopeType)` / `isScopeStrictAdmin(authentication, scopeId, scopeType)` / `hasScopePermission(authentication, scopeId, scopeType, permission)`。`isAdminOrAbove(...)` という `@accessGuard` メソッドは**実在しない**（それは Service 層 `AccessControlService` のメソッド名）。
  - 例（@PreAuthorize 第二防御）: `@PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")`
- **二重防御の使い分け**:
  - **第一防御（Service 層）**: `AccessControlService.isAdminOrAbove(userId, teamId, "TEAM")` 等を `MatchAccessService` から明示呼出し。
  - **第二防御（Controller の `@PreAuthorize`）**: `@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')` 等の SpEL ガード（第一引数 `authentication`）。
  - 認可ロジックは `MatchAccessService`（match ドメイン）に集約し Controller から委譲する。

> **既存 tournament の無防備パターンを継承しない**: 既存 `com.mannschaft.app.tournament.MatchController` / `MatchService` は `@PreAuthorize` 無し・org 絞り込み無しで IDOR の温床である（[05](./05_tournament_integration.md) §H.4 で `FixtureController` へ改称・認可付与）。**本機能はこの無防備パターンを継承せず**、全エンドポイントに二重防御と IDOR チェーンを必須とする。

#### C.3.2 F00 可視性の具体実装 — `MatchVisibilityResolver`（殿裁可）

閲覧可視性は**独自 visibility 述語を書かず F00 正準（`ContentVisibilityResolver` / `ContentVisibilityChecker`）に委譲**する（メモリ教訓「可視性は必ず F00 ContentVisibilityResolver 経由」）。

- **`MatchVisibilityResolver implements ContentVisibilityResolver<…>` を新設**（`com.mannschaft.app.match`・実装は `backend/.../common/visibility/ContentVisibilityResolver.java` の規約に沿う）。
- **`ReferenceType.MATCH`（idKind=`UUID_V7`）を追加**（matches の PK は UUIDv7 / BINARY(16)）。
  - **実装注記**: `ReferenceType.idKind()` の switch に `MATCH = UUID_V7` ケースを追加する必要がある（漏れると idKind 解決が `fail-closed` になり可視性判定が機能しない）。なお **match はコルクボード（引用・ピン留め）の対象外**のため、引用先 ID を保持する `reference_id_uuid` カラムの追加は**当面不要**（コルクボード引用が match を対象に含める要件が顕在化したら追加する）。
- matches は UUIDv7 主キーなので、`ContentVisibilityResolver` の **UUID 経路** `canViewUuid(UUID, Long)` / `filterAccessibleUuid(Collection<UUID>, Long)` を実装する（Long 経路はデフォルトのまま `UnsupportedOperationException`）。
- `ContentVisibilityChecker` のコンストラクタ・ディスパッチ表に `MatchVisibilityResolver` を**自動登録**（Spring Bean として注入され `referenceType()` をキーに登録される）。
- **`MatchAccessService.canView` は必ずこの Resolver / Checker へ委譲**する（独自述語禁止）。
- 一覧・集計は `filterAccessibleUuid` の**バッチ判定で N+1 を回避**する（実装は SQL 数 ≦ 2 で完結）。

```java
// MatchAccessService の判定 API（概念）
boolean canEditMeta(Long userId, MatchEntity match);        // 作成者/記録係/主体チームADMIN（@accessGuard）
boolean canRecordTimeline(Long userId, MatchEntity match);  // 公式戦=記録係 / 共同=両チームADMIN
boolean canEditTeamData(Long userId, MatchEntity match, Long owningTeamId); // 自チームADMIN かつ owning==自チーム
boolean canView(Long userId, UUID matchId);                 // F00 MatchVisibilityResolver.canViewUuid へ委譲
```

#### C.3.3 メンバーシップ・ロール判定

- **メンバーシップ判定**: `accessControlService.isMember(userId, teamId, "TEAM")`（memberships テーブル参照・F00.5 Phase 3）。
- **ADMIN/DEPUTY 判定**: `accessControlService.isAdminOrAbove(userId, teamId, "TEAM")`（per-scope ロール）。
- **記録係判定**: `matches.scorekeeper_user_id == 認証ユーザー`。
- **作成者判定**: `matches.created_by == 認証ユーザー` または 主体チーム ADMIN。

### C.4 IDOR 対策（match_id → 帰属確認チェーン・二段アクセス）

全ての書き込み・読み取り API で、Service 層が次の帰属チェーンを必ず検証する（推測 ID による越境を遮断）。**子テーブルは親 matches をテナント取得 → 子は match_id スコープ**の二段アクセス（01 §A.4）で、**子 ID 直引きは禁止**。

| 操作 | 検証チェーン |
|------|-------------|
| `GET/PATCH /matches/{matchId}` | `matchRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(matchId, 認証テナント)` → `canView`/`canEditMeta` |
| `POST /matches/{matchId}/events` | 親 match をテナント取得 → `canRecordTimeline` → （共同記録時）`event.recorded_by_team_id` が自チーム |
| `PATCH/DELETE /matches/{matchId}/events/{eventId}` | 親 match をテナント取得 → `event.match_id == matchId`（不一致 404） → `recorded_by_team_id` が自チーム or 記録係 |
| `PATCH /matches/{matchId}/appearances/{apId}` | 親 match をテナント取得 → `appearance.match_id == matchId`（不一致 404） → `owning_team_id` が自チーム ADMIN |
| `GET /users/{userId}/match-stats` | `userId == self`（本人のみ・チーム横断） |
| `GET /users/{userId}/teams/{teamId}/match-stats` | `isAdminOrAbove(viewer, teamId)` ＋ 対象 userId が teamId 所属（F19.1 公開設定連動・02 §F.1） |
| `GET /teams/{teamId}/match-stats` | `teamId` のメンバー以上（playerRankings は MEMBER 以上＝SUPPORTER 除外・02 §F.3） |

- 親子 ID 不一致（`event.match_id != パスの matchId` 等）は **404 で統一**（存在を漏らさない）。
- テナント越境（`match.organization_id != 認証テナント`）も **404**。
- 認可失敗（権限不足）は **403**。不在・テナント越境・親子不一致は **404**（403/404 マッピングは C.6 監査で明文化）。
- リポジトリは `matches` のみ `AbstractTenantAwareRepository`（原則 7）の `findByIdAndOrganizationIdAndDeletedAtIsNull` を用いてテナント絞り込みを基底で強制。子は match_id スコープのみ。

### C.4a 編集権限列の改竄耐性（マスアサインメント防止）【要改善の根治】

- `owning_team_id` / `recorded_by_team_id` は **Request DTO に含めない**。サーバーが**認証主体のチーム所属から導出**してセットする（クライアントが任意のチーム ID を詐称できないようにする＝マスアサインメント防止）。
- イベント記録時、`team_side` と相手チームの整合を検証する（**自サイド以外を自名義で記録できない**）。共同記録で自チームが相手サイドのイベントを自名義（`recorded_by_team_id`=自チーム）で捏造することを防ぐ。
- **`linked_event_id`（連鎖・01 §B.2）の帰属検証**: クライアントが指定する `linked_event_id` は、**同一 `matches`（同じ `match_id`）に属する既存 `match_events` の ID であることをサーバーが検証**する（別試合・他テナントのイベント ID を連鎖相手に指定する越境を遮断）。不一致は 404（親子不一致の統一・C.4）。連鎖相手も自チームの編集権限スコープ内であることを確認する。

### C.4b 入力検証（インジェクション・過大入力対策）【要改善の根治】

全書き込み API は **Request DTO ＋ `jakarta.validation`** を必須とする。

- `detail JSON`: **サイズ上限 4KB** ＋ サーバー側スキーマ検証（競技別・01 §D.3 の `SportEventCatalog` と整合）。任意 JSON を無検証で保存しない。
- `minute` / `stoppage_minute` / `jersey_number` / `home_score` / `away_score` / PK スコア: `@Min`/`@Max` で業務範囲を制約（例: minute 0–150、jersey 0–999、score 0–999）。
- `player_name` / `related_player_name` / `opponent_name`: 最大長制約 ＋ **制御文字除去 ＋ trim**。
- **`note`（最大 255）・`custom_label`（最大 64）**（01 §B.2・§D.2）: 上記と同じ入力検証方針の対象に含める。`@Size(max=255)` / `@Size(max=64)`（`jakarta.validation`）＋ **制御文字除去 ＋ trim**。**HTML タグ不可**（Vue 自動エスケープに加え、CSV/PDF エクスポート・SSR・ログ出力時に XSS / CSV インジェクション / CRLF サニタイズの対象とする）。任意のリッチテキストを無検証で保存しない。
- **`card_reason_code`（最大 8）**（01 §B.2・§D.5）: 警告/退場の標準理由コード。サーバーで**二段検証**する（**検証規約は競技非依存・コア**）。
  - **(1) カタログ列挙値であること**: `match.sport` に紐づく**その競技の理由コードカタログ**（01 §D.5・案 A）の列挙値であること。列挙外の文字列は **400**。クライアントが渡す任意文字列を無検証で保存しない（症状を隠さず根治）。
  - **(2) `event_type` との整合**: 警告系→警告コード群／退場系→退場コード群、という対応がカタログで定められる（その競技カタログの event_type↔コード対応に整合すること）。不整合は **400**。警告/退場系以外の `event_type` に `card_reason_code` を付けた場合も **400**（非対象イベントには NULL のみ許容）。
  - **競技整合**: 多競技拡張時はカタログを競技別に切替（`match.sport` に紐づくカタログ・01 §D.5）。当該競技のカタログ外のコードを付けた場合は **400**。
  - **競技固有の具体対応 → [sports/01_soccer.md](./sports/01_soccer.md) §5.3 参照**: サッカーの具体列挙値（`CautionCode` C1〜C8／`SendingOffCode` S1〜S6・CS）と event_type↔コード対応（`YELLOW_CARD`→C 系／`RED_CARD`→S1〜S6／`SECOND_YELLOW`→CS）はサッカー競技カタログに集約した。
  - `card_reason_code` は固定記号で言語非依存・固定長ゆえ XSS/CRLF の懸念は低いが、列挙値突合により実質ホワイトリスト検証となる（任意入力ではない）。
- **ログ出力時は CRLF サニタイズ**（player_name / `note` / `custom_label` 等のユーザー入力をログに出す場合、改行除去でログインジェクションを防ぐ）。

### C.5 共同記録の破壊耐性・異議フロー【要改善の根治】

- **スコア（home/away_score・PK スコア）は作成者/記録係のみ**が確定する（C.2）。相手チームは書き換え不可。
- 楽観ロック粒度は**イベント行単位（`match_events` 個別行）**を優先（02 §E.2・matches.version 非依存）。フル再計算は自サイドの appearance のみ削除対象とし相手分を破壊しない（02 §E.5a）。
- **相手チームの異議・訂正依頼フロー**: 相手チームが「このスコア/イベントは誤り」と考える場合、直接編集はできないが**訂正依頼（異議）を作成者/記録係へ送る**経路を設ける（通知ドメイン連携・MVP では「依頼コメント＋通知」で最小実装。正式な承認ワークフローは後段）。

### C.6 退会ユーザーの扱い（原則 4・GDPR・二段モデル）【要改善の根治】

- `match_events.player_user_id` / `related_player_user_id`、`player_appearances.player_user_id`、`matches.created_by`/`scorekeeper_user_id` は**履歴・統計の証跡として ID を保持**する。
- **CLAUDE.md §13.12 の PII 二段モデル**（即時消去＝弱匿名化 / 猶予対象＝強匿名化）に従う。match 選手データは**統計整合性に重大影響**するため**猶予対象（強匿名化・最大 30 日後の `AccountPurgeService`）**に区分する。退会撤回ウィンドウ中は ID を保持しつつ表示名のみ匿名化に追従する。
- 表示名は既存匿名化（`user.anonymize()`）に追従（集計 DTO の `displayName` は匿名化後の値を返す）。
- `player_name`（手入力の未登録選手名）は**第三者の PII**であり当該本人の退会とは独立した運用入力ゆえ匿名化対象外（F08.7.1/05 §9.2 と同方針）。ただし**未登録選手本人からの削除要求**（GDPR Art.17）受付経路として、**記録チーム ADMIN 経由の訂正・匿名化**（player_name を「選手 A」等へ置換）を運用フローに用意する。

### C.7 監査ログ【拡充】

- スコア確定（メタ更新）・status 遷移（COMPLETED/CANCELLED/POSTPONED）・モード切替・**記録係変更**を監査ログに残す。
- `AuditEventType` に以下を追加する想定（既存 tournament 系の監査追加と同パターン）:
  - `MATCH_SCORE_FINALIZED`（スコア確定）
  - `MATCH_STATUS_CHANGED`（status 遷移）
  - `MATCH_RECORDING_MODE_CHANGED`（記録モード切替）
  - **`MATCH_SCOREKEEPER_CHANGED`（記録係変更）**
- **スコア確定監査の metadata には before/after・matchId・操作者・チーム**を含める（誰がいつどのスコアにしたか追跡可能に）。
- `AuditEventCategory` に **`MATCH` カテゴリ新設を検討**（採番後の正式名は実装時に確定）。
- イベントの大量追加（ライブ記録）は監査ログに individ で残すとノイズになるため、**1 試合の記録セッション単位**でのサマリ監査（誰がいつ何件記録したか）に留める。
- **403（認可失敗）/ 404（不在・テナント越境・親子不一致）のマッピングを明文化**（C.4）。エラーレスポンスで存在を漏らさない。

---

## 未解決事項（全項目解決済み／MVP外の先送り決定を含む）

1. **共同記録モードの編集競合** — 解決済み（殿裁可）: 楽観ロック粒度は**イベント行単位**を優先。スコアキャッシュは `matches.version` 非依存（アトミック増減 or 読取時 GOAL 集計導出）。フル再計算は `matches.version` に触れず appearances のみ更新（02 §E.2）。ライブ入力中の 409 リトライ UX は [04](./04_frontend_and_ux.md) §G.2（G.7）。
2. **記録係の権限源泉** — 解決済み（殿裁可・MVP 方針）: `scorekeeper_user_id` を「作成者が任意ユーザーを記録係指定」とする（前者）。会場運営ロール（組織レベルの記録係ロール）新設は後段の余地として残すが MVP 外。
3. **公開閲覧（SUPPORTER/GUEST）の範囲** — 解決済み（殿裁可）: 閲覧可視性は `MatchVisibilityResolver`（F00 正準・C.3.2）に委譲し、大会公式戦は F08.7 の可視性連動、練習試合はチーム設定（F00 可視性レベル `SCOPE_AFFILIATED` 等）に従う。個人統計の公開は F19.1 を正本に連動（02 §F.1）。
4. **相手チームが未登録（opponent_team_id=NULL）時** — 解決済み（殿裁可）: 相手チームのメンバーが居ないため共同記録が成立しない。自動的に「作成チーム単独記録」へ縮退する（相手分は記録チームが `recorded_by_team_id`=自チームで代理記録・C.4a の整合検証は HOME/AWAY の側のみ確認）。
