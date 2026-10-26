# F08.7.1 / 01: 連絡機能（掲示板・チャット連絡スペース）

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-05-31
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要・トレーサビリティ
> - [F08.7_tournament_league.md](../F08.7_tournament_league.md) — 大会・ディビジョン・参加者（連絡単位の源泉）
> - [F05.1_bulletin_board.md](../F05.1_bulletin_board.md) — 掲示板（`scope_type` 拡張先・カテゴリ/スレッド実体）
> - [F04.2_chat.md](../F04.2_chat.md) — チャット（`channel_type` / `source_type` 拡張先）
> - [docs/security/03_role_authority_model.md](../../security/03_role_authority_model.md) — 認可方針・PUBLIC 露出方針

本書は確定要件 ①（連絡単位＝大会全体＋各ディビジョンの二段・掲示板＆チャット両方）・②（閲覧＝参加チーム全メンバー＋公開スペースは PUBLIC 可）・③（投稿＝主催組織 ADMIN＋各チーム代表/副代表）・④（スペースは作成時に自動付帯）を実装可能レベルに具体化する。

---

## 1. 概要

大会の参加チーム間で連絡を取り合うための**連絡スペース**を、(a) 大会全体（`TOURNAMENT`）と (b) 各ディビジョン（`TOURNAMENT_DIVISION`）の **二段**で、**掲示板**と**チャット**の両方として自動付帯する。

- **掲示板**（F05.1）= 非同期・お知らせ／連絡向け（日程変更・審判派遣・スポンサー募集など）。
- **チャット**（F04.2）= 同期・速報／雑談向け（既定で非公開）。
- スペースは大会／ディビジョン作成時に**自動生成**され、運営の手間ゼロで連絡を開始できる（ADHD 配慮：入力摩擦ゼロ）。
- 各スペースに**公開トグル**（`is_public`）を設け、主催者が「未ログイン者にも見せる広報スペース」にできる。

連絡単位は二段なので、1 大会あたり最大で `(1 + ディビジョン数) × 2(掲示板/チャット)` のスペースが生成される（例: 4 部リーグ ＝ (1+4)×2 = 10 スペース）。

---

## 2. データモデル

### 2.1 新規テーブル `tournament_contact_space`（tournament ドメイン）

「このスコープ（大会 or ディビジョン）の、この種別（掲示板 or チャット）のスペースが、どの bulletin/chat リソースに払い出されているか」と、その公開フラグを 1 テーブルで管理する。冪等化の逆引きキーも兼ねる。

- **主キーは UUIDv7**（原則 6・`UuidV7Entity` 継承）。
- **クロスドメイン FK は張らない**（原則 1）。`scope_id` / `ref_id` は ID 値のみ保持し、整合性はアプリ層で保証する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BINARY(16) | NO | （UUIDv7 自動生成） | PK |
| `scope_type` | VARCHAR(30) | NO | — | `TOURNAMENT` / `TOURNAMENT_DIVISION` |
| `scope_id` | BIGINT UNSIGNED | NO | — | `tournaments.id`（TOURNAMENT 時）または `tournament_divisions.id`（TOURNAMENT_DIVISION 時）。FK なし |
| `space_kind` | VARCHAR(20) | NO | — | `BULLETIN` / `CHAT` |
| `ref_id` | BIGINT UNSIGNED | NO | — | 払い出した実体の ID。BULLETIN なら `bulletin_categories.id`（または board id）、CHAT なら `chat_channels.id`。FK なし（クロスドメイン） |
| `is_public` | BOOLEAN | NO | FALSE | 公開トグル（TRUE で PUBLIC が閲覧可。要件②）。CHAT は既定 FALSE |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除（大会/ディビジョン削除時に archive。§6） |

**インデックス**

```sql
-- 冪等化（同一スコープ×種別で 1 つ）＋「このスコープのリソース id」逆引き
UNIQUE KEY uq_tcs_scope_kind (scope_type, scope_id, space_kind)
-- ref_id 逆引き（chat_channel id → どの大会スペースか）
INDEX idx_tcs_ref (space_kind, ref_id)
```

**備考**
- `UNIQUE(scope_type, scope_id, space_kind)` が「冪等化」「逆引き」「公開フラグ保持」の 3 役を 1 本で担う。払い出し競合時はこの UNIQUE で弾き、`DataIntegrityViolationException` を catch して再取得する（§3.4）。
- このテーブルは organization_id を持たない（大会は組織配下だが、スペース自体はテナント単位で爆発的に増えるものではなく、大会 ID から組織を辿れる）。`AbstractTenantAwareRepository` は適用しない。

### 2.2 bulletin `ScopeType` の拡張（F05.1）

bulletin の `scope_type` は **実 DDL では `VARCHAR(20)`**（`V5.001__create_bulletin_categories_table.sql:4` / `V5.002__create_bulletin_threads_table.sql:5`）、JPA は `com.mannschaft.app.bulletin.ScopeType`（`@Enumerated(EnumType.STRING)` ＋ `@Column(nullable=false, length=20)`、現値 `ORGANIZATION` / `TEAM` / `PERSONAL` / `VILLAGE`）。ここに enum 定数 **`TOURNAMENT` / `TOURNAMENT_DIVISION`** を追加する。

- `scope_id` には大会 ID / ディビジョン ID を直接格納する。
- **桁確認**: `TOURNAMENT`（10字）・`TOURNAMENT_DIVISION`（**19字**）はいずれも `VARCHAR(20)` に収まる。**MODIFY 不要**（F05.1 §3 の `scope_type` 記述と統一。実 DDL は VARCHAR・JPA は `EnumType.STRING`。F05.1 の ENUM 表記は誤りなので併せて是正対象）。
- DDL: VARCHAR ゆえ enum 定数追加（Java `ScopeType` への `TOURNAMENT` / `TOURNAMENT_DIVISION` 追加）のみで足り、列定義の `ALTER` は不要。**実装時に実スキーマを grep して 20 桁制約を超えないことを再確認**する。
- スコープ別スレッド一覧の主クエリ用 `(scope_type, scope_id)` 複合 index は **実 DDL に未存在**（`V5.002` には PRIMARY KEY / category FK / author FK / FULLTEXT のみ。後続移行にも追加なし）。**実装時に grep で確認し、無ければ Flyway 移行で追加**する（§3.4・README Y-2 と整合）。

### 2.3 chat `ChannelType` / `source_type` の拡張（F04.2）

chat の `chat_channels.channel_type` に **`TOURNAMENT_CHAT`（15字）/ `TOURNAMENT_DIVISION_CHAT`（24字）** を追加する。紐付けは既存の **`source_type` / `source_id` 方式**（EVENT_CHAT と同じ・カラム追加ゼロ）を踏襲する。

> **🔴 桁あふれ警告（検分1周目で発覚・実コード確認済み）**: `chat_channels.channel_type` は実 DDL で **`VARCHAR(20)`**（`V4.013__create_chat_channels_table.sql:3`）、Entity も `ChatChannelEntity.java:36-38` で `@Enumerated(EnumType.STRING) @Column(nullable=false, length=20)`。`TOURNAMENT_DIVISION_CHAT` は **24 字**で **20 桁に収まらない**（保存時に切り詰め／エラーになる）。**「VARCHAR ゆえ DDL 変更不要・カラム追加ゼロ」は誤り。**
>
> したがって実装時に **以下の 2 点が必須**:
> 1. Flyway 移行で桁拡張: `ALTER TABLE chat_channels MODIFY channel_type VARCHAR(30) NOT NULL;`
> 2. Entity 桁拡張: `ChatChannelEntity.channelType` の `@Column(length = 20)` を **`length = 30`** に変更。
>
> （`source_type` 側は本機能で `TOURNAMENT` / `TOURNAMENT_DIVISION`＝最長 19 字を入れる。`chat_channels.source_type` の桁も実装時に grep で確認し、20 桁未満なら問題ないが、20 桁ちょうど制約の場合は 19 字で収まるため MODIFY 不要。）

| 項目 | 値 |
|------|-----|
| `channel_type` | `TOURNAMENT_CHAT`（大会全体）/ `TOURNAMENT_DIVISION_CHAT`（ディビジョン） |
| `source_type` | `TOURNAMENT`（source_id = `tournaments.id`）/ `TOURNAMENT_DIVISION`（source_id = `tournament_divisions.id`） |
| `team_id` / `organization_id` | ともに NULL（大会スコープは特定チーム/組織に属さない横断スペース。CROSS_TEAM と同様の扱い） |
| `is_private` | 既定 TRUE（公開トグル ON 時のみ後述の read-only 公開を許可。§5） |
| `name` | `{大会名} 連絡` / `{大会名} {ディビジョン名} 連絡` |

- 既存 `UNIQUE KEY uq_chat_channels_source (source_type, source_id)` により、同一大会/ディビジョンからの重複チャンネル作成を防止できる（payload 追加ゼロ）。
- `source_data` 拡張（F04.2 既存方式）：`source_type='TOURNAMENT'` / `'TOURNAMENT_DIVISION'` の場合、`GET /chat/channels/{id}` レスポンスに大会サマリー（`tournament_id` / `tournament_name` / `division_name?` / `status`）を含める。チャット画面上部に大会カードを固定表示する。

---

## 3. 自動生成フック（スペースの自動付帯・要件④）

スペースは大会／ディビジョン作成時に **直接サービス呼び出し**で払い出す。tournament ドメインから chat / bulletin ドメインの Service を呼ぶため**ドメイン越境**となる。原則 5 に従い、各フックに越境 TODO コメントを明記する。

> ```java
> // TODO: tournament ドメインから chat/bulletin ドメインの Service を直接呼んでいる。
> //       将来は TournamentCreatedEvent / DivisionCreatedEvent によるイベント駆動化候補。
> ```

### 3.1 大会作成時（`TournamentService.createTournament`）

`tournament` を save した直後（同一トランザクション内）に以下を実行する:

1. 大会全体の **掲示板スペース**を払い出す（`scope_type=TOURNAMENT, space_kind=BULLETIN`）。`BulletinCategoryService.provisionForScope(TOURNAMENT, tournamentId)` を呼び、デフォルトカテゴリを生成（§3.2）。`tournament_contact_space` に `ref_id = 代表カテゴリ id`（または board id）を記録。
2. 大会全体の **チャットスペース**を払い出す。`TournamentChatChannelService.createForTournament(tournamentId)`（新設・F04.2 `EventChatChannelService` を範とする）で `channel_type=TOURNAMENT_CHAT, source_type=TOURNAMENT, source_id=tournamentId, is_private=TRUE` のチャンネルを作成。`tournament_contact_space` に `ref_id = chat_channel id` を記録。

### 3.2 ディビジョン作成時（`DivisionService.createDivision`）

`tournament_division` を save した直後に、そのディビジョンの掲示板＋チャットを払い出す（`scope_type=TOURNAMENT_DIVISION, scope_id=divisionId`）。手順は §3.1 と同型。

**bulletin デフォルトカテゴリの自動生成**（村実装 `VillageLobbyService` に倣う）:
- provisioning 時にデフォルトカテゴリを 2 件生成する: 「お知らせ」（`post_min_role=ADMIN` 相当の運営連絡）・「連絡」（参加チーム代表が投稿）。
- カテゴリ名の重複防止は F05.1 の方式（Service 層で `deleted_at IS NULL` 照合）に従う。

### 3.3 シーズン継続時（`TournamentService.continueTournament`）

シーズン継続で**ディビジョンが複製される**場合、複製先ディビジョンにも §3.2 と同じ払い出しを行う（**払い出し漏れ防止**）。継続元のスペースは旧シーズンの履歴として残す（新シーズンは新ディビジョン ID＝新スペース）。

> **テストで検証必須**: `continueTournament` 後に複製ディビジョン数 × 2（掲示板/チャット）のスペースが `tournament_contact_space` に存在することをアサートする（この漏れは過去類似機能で頻発した）。

### 3.4 冪等化・競合制御

- 払い出し前に `tournamentContactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(...)` で既存を確認し、あれば再利用する（リトライ・再実行で二重生成しない）。
- chat 側も既存 `findBySourceTypeAndSourceId` で既存チャンネルを確認する。
- 競合（同時実行）時は `UNIQUE(scope_type, scope_id, space_kind)` 違反 → `DataIntegrityViolationException` を catch → 再取得（`VillageLobbyService` 方式）。
- **source 複合 index の有無**: `chat_channels(source_type, source_id)` は F04.2 で `uq_chat_channels_source` として既存。`bulletin_threads(scope_type, scope_id)` 複合 index は **実 DDL に未存在**（`V5.002` 確認済・後続移行にも無し）。**実装時に grep で再確認し、無ければ Flyway 移行で追加**する（症状を隠さず根治）。

---

## 4. 認可（read / write 分離）

新規 `TournamentContactAccessService`（tournament ドメイン）を設け、村の二段認可（`VillageBulletinAccessService` の `checkView` / `checkModerator`）を範として read / write を分離する。掲示板・チャット双方のコントローラ／サービス入口で**必ず**通す（多層防御）。

クロスドメインの所属判定は `TeamMembershipRepository` / `AccessControlService` を **ID 参照のみ**で呼ぶ（原則 1）。存在しない／削除済みスペースは一律 **404**（IDOR 対策・存在を漏らさない）。

### 4.1 `canView(scopeType, scopeId, userId)`（要件②）

```
canView(scopeType, scopeId, userId):
    space = findActiveSpace(scopeType, scopeId, space_kind)
    space が存在しない / deleted_at IS NOT NULL → 404（IDOR 対策）

    space.is_public == true                                      → 許可（PUBLIC・未ログイン含む。read-only）
    userId が対象チーム(status IN (REGISTERED, ACTIVE)) のメンバー  → 許可
    主催組織 ADMIN（isAdmin(userId, orgId, "ORGANIZATION")）       → 許可
    SYSTEM_ADMIN                                                  → 許可
    else                                                         → 403
```

- **チーム解決の源泉＝`tournament_participants`**。`status IN (REGISTERED, ACTIVE)` を含め、`WITHDRAWN` / `DISQUALIFIED` を**除外**する（**大会開始前から連絡可能**にする決定。要件のユーザビリティ確保）。
- `TOURNAMENT`（大会全体）スコープは**全ディビジョンの participants を集約**して判定する。
- 主催組織は大会 ID から `tournaments.organization_id` を辿って解決する。

### 4.2 `canPost(scopeType, scopeId, userId)`（要件③）

```
canPost(scopeType, scopeId, userId):
    対象チームの ADMIN/DEPUTY_ADMIN
        （accessControlService.isAdminOrAbove(userId, teamId, "TEAM")）  → 許可
    主催組織 ADMIN（isAdmin(userId, orgId, "ORGANIZATION")）             → 許可
    SYSTEM_ADMIN                                                        → 許可
    else                                                               → 403
```

- 投稿できるのは **各チームの代表（ADMIN）・副代表（DEPUTY_ADMIN）** と **主催組織 ADMIN** のみ。一般 MEMBER・SUPPORTER・PUBLIC は閲覧のみ（権限昇格防止：MEMBER が代表になりすませない）。
- `is_public=true` のスペースでも、**PUBLIC は常に read-only**。公開で投稿権が広がることはない。

### 4.3 N+1 回避（大会全体スコープ）

`TOURNAMENT` スコープの `canView` / `canPost` は「大会の全参加チームのいずれかで当該ユーザーがメンバー/代表か」を判定するため、ディビジョン × チーム × メンバーで N+1 になりやすい。これを避けるため、`TournamentParticipantRepository` に**単発 exists クエリ**を新設する:

```java
// 大会のいずれかの参加チーム（REGISTERED/ACTIVE）に当該ユーザーが所属するか
boolean existsActiveMemberOfAnyParticipantTeam(Long tournamentId, Long userId);
// 大会のいずれかの参加チームで当該ユーザーが ADMIN/DEPUTY_ADMIN か
boolean existsTeamAdminOfAnyParticipantTeam(Long tournamentId, Long userId);
// ディビジョン単位の同等クエリ
boolean existsActiveMemberOfDivisionParticipantTeam(Long divisionId, Long userId);
boolean existsTeamAdminOfDivisionParticipantTeam(Long divisionId, Long userId);
```

JOIN は `tournament_participants × team_memberships`（status 絞り込み付き）の 1 クエリで完結させる。

### 4.4 `@Transactional` の境界

`TournamentContactAccessService` は読み取り専用（`@Transactional(readOnly=true)`）で、tournament ドメイン内に閉じる。所属判定で `TeamMembershipRepository` を呼ぶのはクロスドメインだが**ID 参照の読み取りのみ**であり、書き込みトランザクションを跨がない。

---

## 5. 公開トグル（`is_public`）

主催者（主催組織 ADMIN / SYSTEM_ADMIN）が各スペースの公開可否を切り替える。

### 5.1 API

```
PATCH /api/v1/tournaments/{tournamentId}/contact-spaces/{spaceId}/visibility
PATCH /api/v1/tournaments/{tournamentId}/divisions/{divisionId}/contact-spaces/{spaceId}/visibility
  body: { "is_public": true }
  認可: 主催組織 ADMIN / SYSTEM_ADMIN のみ（canPost より厳しい。チーム代表は公開設定できない）
  応答: 200 更新後のスペース / 403 / 404
```

### 5.2 公開時の挙動・セキュリティ

- **chat の公開は既定 OFF**（`is_private=TRUE`）。公開トグルを ON にしても **PUBLIC は read-only**（投稿は常に §4.2 の代表＋主催者のみ）。スペクテーター（観戦者・未ログイン）への露出範囲は閲覧のみに限定する。
- 掲示板の公開も同様に PUBLIC は閲覧のみ。
- 公開トグルの監査：`audit_logs` に `TOURNAMENT_CONTACT_SPACE_VISIBILITY_UPDATED` を記録（誰が・いつ・どのスペースを公開/非公開にしたか）。
- セキュリティ精査項目（docs/security 連携）：公開スペースに**非公開大会の機微情報（未確定の対戦相手・内部連絡）が露出しないか**を運営に明示。公開はあくまで「広報目的の連絡スペース」用途とし、内部連絡カテゴリ／チャンネルは非公開のまま保つ運用を推奨する。

---

## 6. 削除・退会の取り扱い

### 6.1 大会・ディビジョン削除（原則 2：クロスドメイン CASCADE なし）

- 大会／ディビジョン削除（論理削除）時、連絡スペースは**即時物理削除せず soft delete / archive で残す**（履歴保持）。`tournament_contact_space.deleted_at` をセットし、紐づく bulletin スレッド / chat チャンネルは `is_archived=TRUE` 相当でアーカイブする。
- `DivisionService.deleteDivision` が**物理 delete** を行う実装の場合は、削除前に**スペース archive フック**を追加する（孤児化を防ぐ。chat_channel / bulletin が宙に浮かないよう、archive 後に空 ref として残すか、tournament ドメイン側の `deleted_at` だけ立てる）。
- クロスドメイン CASCADE（`ON DELETE CASCADE` で chat_channels を巻き込む等）は**作らない**（原則 2）。連絡履歴は統計・証跡として価値があるため保持する。

### 6.2 ユーザー退会

- 投稿（スレッド・メッセージ）は `user_id` を保持したまま残す。表示名は既存の匿名化フロー（`user.anonymize()`）に**自動追従**する（CLAUDE.md 原則 4）。
- **本機能専用の退会リスナーは不要**。bulletin / chat 既存の匿名化追従に乗る。
- **新規テーブルの `created_by` 等の user_id 列**（`tournament_contact_space` の払い出し者を将来記録する場合を含む）は、**履歴・証跡として保持**する＝CLAUDE.md の退会二段モデルにおける**強匿名化対象外**（user_id は NULL 化せず残す）。表示名のみ既存の匿名化に追従させ、退会後は匿名表示名で描画する。GDPR 上の個人特定リスクは表示名匿名化で除去され、証跡としての user_id 参照は保持してよい（即時消去対象の「再設定で復旧可能なデータ」には当たらない）。

---

## 7. F08.7 §5.9 との関係

F08.7 §5.9「ディビジョン別ターゲティング」は「掲示板/通知/DM の配信対象をディビジョンで絞る」フィルタ機能の計画であった。本書はそれを**連絡スペースという常設の場**として具体化したもの。両者は補完関係:

- **本書（連絡スペース）**: 大会/ディビジョンに紐づく**常設の掲示板・チャット**。参加チームが日常的に連絡する場。
- **§5.9（ターゲティング）**: 既存の通知/DM/タイムラインの**配信先フィルタ**として `target_type='TOURNAMENT_DIVISION'` を使う一過性の配信。

F08.7 §5.9 は本書 01 へのリンクに置き換える（参加チーム解決の源泉＝`tournament_participants`・status=ACTIVE 除外ルールは共通）。

---

## 8. 精査ログ

### 8.1 1 回目（不備・セキュリティ・ユーザビリティ・見落とし・保守性）

- **不備**: 二段（大会/ディビジョン）× 2 種別（掲示板/チャット）の払い出しを §3.1〜§3.3 で網羅。continueTournament の複製漏れをテスト必須として明記。
- **セキュリティ**: read/write 分離（§4.1/§4.2）、IDOR 404 統一、PUBLIC read-only、公開トグルは主催組織 ADMIN 限定で代表には開放しない（権限昇格防止）。
- **ユーザビリティ**: 自動付帯で入力摩擦ゼロ。REGISTERED で開始前から連絡可。
- **見落とし**: N+1 回避の exists クエリ（§4.3）、source 複合 index の実装時 grep、bulletin カテゴリ自動生成。
- **保守性**: 越境 TODO 明記（原則 5）、UUIDv7（原則 6）、クロスドメイン FK なし（原則 1）、CASCADE なし（原則 2）。

### 8.2 未解決事項

**現時点でなし。**

---

## 9. 実装メモ（隊1・2026-05-31）

設計書に対する実装上の確定事項・軽微な差分を記録する。

### 9.1 実装ファイル

- DDL: `V9.20260531120000__create_tournament_contact_space.sql`（新規テーブル）/ `V9.20260531120100__add_bulletin_threads_scope_index.sql`（§2.2 の `(scope_type, scope_id)` 複合 index 根治追加）
- enum: `bulletin.ScopeType`（+TOURNAMENT/TOURNAMENT_DIVISION）/ `chat.ChannelType`（+TOURNAMENT_CHAT/TOURNAMENT_DIVISION_CHAT）/ `tournament.ContactSpaceScopeType` / `tournament.ContactSpaceKind`
- Entity/Repo: `TournamentContactSpaceEntity`（UuidV7Entity 継承）/ `TournamentContactSpaceRepository`
- 認可: `TournamentContactAccessService`（checkView/checkPost/checkVisibilityManage）+ `TournamentParticipantRepository` の N+1 回避 exists クエリ 4 本
- 払い出し: `chat.TournamentChatChannelService` / `tournament.TournamentContactSpaceProvisioningService`（フック: createTournament / continueTournament / createDivision、archive: delete*）
- 公開トグル: `TournamentContactSpaceService` + `TournamentContactSpaceController`（PATCH visibility / GET 一覧）
- 本体配線: `BulletinThreadService`（*Global メソッド群）/ `ChatMessageService`（sendMessage / checkChannelViewAccess）+ `ChatMessageController.listMessages`

### 9.2 設計差分

- **チーム所属判定の実装**: 設計書は `TeamMembershipRepository` を範としているが、本コードベースに同名クラスは無く、TEAM メンバー判定は `memberships`（`scope_type='TEAM'`）、TEAM 代表（ADMIN/DEPUTY_ADMIN）判定は `user_roles × roles` を使う。N+1 回避 exists クエリは `tournament_participants × tournament_divisions × (memberships | user_roles)` の native JOIN 1 本で完結（§4.3 の意図どおり）。
- **添付クォータ**: 大会連絡スペースの bulletin/chat 添付は専用 StorageScopeType を持たないため、`VILLAGE` と同方針で操作者の PERSONAL クォータに計上する（`BulletinAttachmentService` / `ChatAttachmentService`）。
- **公開 PUBLIC 露出経路**: `TournamentContactAccessService.checkView` は `is_public=true` で未ログイン（userId=null）を許可する（§4.1 準拠）。ただし現行の chat/bulletin コントローラは authenticated 経路のため、未ログイン HTTP 公開専用エンドポイントは本波では新設していない（将来の public controller で露出する余地を残す）。
