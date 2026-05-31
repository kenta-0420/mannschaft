# F22.1 市（Market）— 04. セキュリティ・運用・未解決事項

> 親: [README.md](README.md) ／ 関連: [01_data_model.md](01_data_model.md) / [02_api_design.md](02_api_design.md)

---

## 1. セキュリティ考慮事項（§6相当）

### 1.1 認可マトリクス（deny-by-default 準拠）

| 操作 | 認可主体 | 実装方針 |
|---|---|---|
| 市閲覧（一覧・地域・件数） | **未ログイン可**（permitAll・PII抑制DTO） | `/api/v1/public/market/**`。§1.6 許可リスト追記 |
| 札詳細閲覧 | 未ログイン可（`visibility='PUBLIC'` のみ）。それ以外は **404 で存在秘匿** | F19.1 §10.1 準拠 |
| 札立て（作成） | **自 scope の ADMIN または `MANAGE_RECRUITMENTS` 保有 DEPUTY_ADMIN のみ** | ダッシュボードからのみ。**TEAMは `AccessControlService.checkPermission(...)`／ORGは `checkAdminOrHasPermission(...)`**（後者はORG専用で、TEAMに渡すと `IllegalArgumentException`。混同禁止） |
| 札に応じる（応募） | ログインユーザ／チームADMIN。`visibility` でサポーター/メンバー判定 | F03.11 §2 マトリクス踏襲。`FRIEND_TEAMS_ONLY` は宛先解決集合のみ（非対象は404） |
| 札を下げる（手動/最終認証） | **札主 scope の所有者のみ** | `scope_id` 所有権検証。最終認証は当該 confirmable の受信者（札主）本人のみ confirm 可 |
| 非公開札の宛先指定 | **自チームの成立フレンドのみ** | `team_friends` 成立検証（未成立 `MARKET_003`）。フォルダは自チーム所有（他人所有 `MARKET_004`） |

### 1.2 IDOR / 越権対策
- **scope_id 検証**: 札立ては URL の `teamId`/`orgId` と認証ユーザーの所属・権限を `AccessControlService` で必ず照合（F03.11 の Team/Organization 別 Controller 分離を踏襲）。
- **クロススコープ参照防止**: 札ID と scope の不一致は 404。
- **フレンド関係検証**: 宛先 `team_id` が札主チームと `team_friends` で成立済みかを **Service 層で必須検証**。未成立は `MARKET_003`。第三者にフレンド関係を露出しない（一覧APIにフレンド情報を含めない）。
- **最終認証のなりすまし防止**: `confirmable_notifications` の受信者本人（札主 scope の権限者）以外の confirm は 403（F04.9 既存の受信者検証に委譲）。

### 1.3 個人情報（PII）開示の最小化 ―未ログイン公開
- **公開DTOを認証DTOと完全分離**（F19.1 §6.3/§10.4）。市の公開レスポンス（`/public/market/**`）に以下を**含めない**:
  - 作成者・応募者の**個人名 / メール / 電話 / 生年月日 / 住所**。
  - 主催はチーム/組織の**公称名＋アイコン**のみ。参加状況は「◯名中△名」カウントのみ（F03.11 §14.9 踏襲）。
- **CI 禁則ワードテスト**（F19.1 §10.4 流用）を市の公開DTOにも適用: シリアライズ結果に `email`/`phone`/`lastName`/`firstName`/`birthday`/`address` 等を含まないことをテスト必須化。
- `FRIEND_TEAMS_ONLY` / `SCOPE_ONLY` の札は公開APIに**一切出さない**（404 で存在秘匿）。

### 1.4 レートリミット
| 対象 | 制限 | 根拠 |
|---|---|---|
| 公開市検索（`/public/market/**`） | 未ログイン 60 req/min/IP、検索 30 req/min/IP | F19.1 §10.2 `PublicApiRateLimitFilter` に market パスを追加 |
| 札立て | 30 req/hour/user | F03.11 §14.5 既存 |
| 札に応じる（応募） | 10 req/min/user | F03.11 §5.2-9 既存 |
| 非公開札の通知一斉配信 | F01.5 §9 の多段レート制限（digest集約）を流用 | フォロー型スパム抑止 |

### 1.5 入力検証
- **地域コード**: `prefecture_code`/`city_code` は `cities`/`prefectures` マスタ存在を検証（FKなし→Service検証）。自由入力地名は構造化フィルタに使わない（表記揺れ防止）。`SUBSTRING(city_code,1,2)=prefecture_code` 整合（`MARKET_001`）。
- **定員・期限**: 既存 CHECK（`min_capacity<=capacity`、`application_deadline<start_at`、`auto_cancel_at<=application_deadline`）を踏襲。
- **フレンド宛先**: `target_kind` と参照列の整合（`recruitment_friend_targets` の CHECK）＋成立/所有検証。
- **TZ**: 期限・リマインドは UTC 保存・表示時アカウントTZ変換（全アカウントTZ対応済）。

### 1.6 deny-by-default 許可リストへの追記（**必須**）
`docs/security/01_authorization_baseline.md §3.3`（公開閲覧・レート制限あり）の `/api/v1/public/**` GET 群に、以下を**明示追加**する（**本設計のPRで追記済み**）。これを怠ると deny-by-default 反転（`.anyRequest().authenticated()`）時に市の公開検索/詳細が **401 で死ぬ**。

- `GET /api/v1/public/market/listings`
- `GET /api/v1/public/market/listings/*`
- `GET /api/v1/public/market/regions`
- `GET /api/v1/public/market/summary`
- `GET /api/v1/public/market/categories`（ジャンルフィルタ用・全テナント共通固定マスタ・PIIなし。2026-05-31 追加）

> POST（応募・札立て・取下げ）は許可リストに入れず `.authenticated()` がカバー（F19.1/§3.3 の方針踏襲）。

> **🔴 根治記録（2026-05-31）**: 実機 E2E で「未ログインで `/market` がログイン画面へ強制リダイレクト」が発覚。真因は市一覧ページが**認証必須**の `GET /api/v1/recruitment-categories` をジャンルフィルタ用に直叩きし、未ログインで 401 → FE の `useApi` が市ページごと `/login` へ遷移していたこと。公開ページは公開 API のみに依存させる原則に基づき `GET /api/v1/public/market/categories` を新設・permitAll 登録し、FE を切り替えて根治した（02_api_design §3.6）。

---

## 2. 運用・デプロイ上の注意

- **confirmable source_type 拡張のデプロイ順序**（01_data_model §5）: F04.9 が未知 source_type を安全に無視する防御コードを**先に**デプロイ → その後 `MARKET_FINALIZE` 発火側を投入。順序厳守（F03.11 §8.5 と同轍）。
- **Flyway 版番号**: マージ直前に `origin/main` 最大版番号を再確認してリネーム（並行PR衝突回避）。from-scratch 番人テストで検知。
- **自動下げ×最終認証の競合（ロック戦略の具体）**:
  - 最終認証の `confirm` トランザクション内で **`recruitment_listings` の当該札行を `SELECT ... FOR UPDATE`（`PESSIMISTIC_WRITE`）で取得**してから `FULL`→`COMPLETED` 遷移を行う。
  - 自動下げバッチ（autoCancel/充足判定）も同札行を `FOR UPDATE` で取得してから状態判定する。これにより confirm とバッチが同札行で直列化し、二重遷移を防ぐ（`innodb_lock_wait_timeout` でデッドロック回避）。
  - 確認通知は札主 scope の権限者**1名以上**に送られ得るが、`confirm` は札行ロックで直列化されるため、先勝ちで `COMPLETED` 後の2人目の confirm は冪等に成功扱い（既に COMPLETED なら no-op）。
- **モデレーション**: 不適切な札の取下げは F03.11 既存のモデレーション/管理者キャンセルに委譲（市で再実装しない）。

---

## 3. 未解決事項（すべて解決方針確定）

> 本設計の精査（2周）により、保留事項は残さず**全て解決方針を確定**した。

- [x] **1. 札の地域は札主所在地か任意指定か** → **任意指定**（既定＝チーム所在地の正規化補完）。開催地はチーム所在地と異なりうるため。未指定可（"地域を問わない" 区画に集約、`include_region_none`）。
- [x] **2. 複数地域にまたがる募集** → MVPは**単一地域**（`prefecture_code`/`city_code` 各1）。将来は `recruitment_listing_regions`（N:N中間表）で拡張、市ビューは UNION 集約で破綻しない設計を維持（Phase 2）。
- [x] **3. フレンド未成立チームへの誤指定** → 宛先指定時に `team_friends` 成立を Service で必須検証（`MARKET_003`）。UIは成立フレンドのみ候補提示。
- [x] **4. 自動札下げ×最終認証の競合** → 札行 `PESSIMISTIC_WRITE` ロックで直列化。確認応答中は自動下げバッチをスキップ（F03.11 §5.4 / §11.1 と整合）。
- [x] **5. リマインドのTZ** → UTC保存・表示時アカウントTZ変換（全アカウントTZ対応済）。`remind_at` は送信側UTCで評価。
- [x] **6. 未ログイン×個人情報** → 公開DTO完全分離＋CI禁則ワードテスト。非公開/scope限定札は404存在秘匿。
- [x] **7. teams所在地文字列とマスタ非リンク** → 正規化マッパで吸収（不一致は候補提示）。teams側カラムの `cities.code` 正規化は影響大のため**別軍議**（Phase 2）。本機能は札の地域コードを正典とし teams 文字列に依存しない。
- [x] **8. 応募取消×キャンセル待ち繰上げ** → F03.11 既存の自動昇格（FULL→OPEN復帰・キャンセル待ち繰上げ §5.3）を流用。市で再実装しない。
- [x] **9. deny-by-defaultで公開検索が401死** → §1.6 で許可リストに market 公開GETを追記。
- [x] **10. 市の重複/命名衝突** → 市は実体を持たない論理ビュー（マスタ駆動）。地域は `cities.code` 一意PKで識別。命名衝突は構造上発生しない。「村(village)」とはUI/i18nを分離。
- [x] **11. confirmable拡張の連鎖故障** → 防御コード先行デプロイの順序厳守（§2）。
- [x] **12. フレンド解消後の非公開札の見え方** → 宛先は保存時固定せず都度F01.5解決。**新規**閲覧/応募は解消後に不可（404）。ただし**既存応募は応募レコード（`recruitment_participants`）を正典に表示・キャンセル可能**（「フレンド解消により無効」バッジ）。02_api_design §7。
- [x] **13. チーム/組織の退会・削除時の札** → F03.11 既存の team/org 削除フックで `recruitment_listings.deleted_at` をセット（論理削除踏襲）。市は `deleted_at IS NULL` で自動非表示。応募者の「応募済み」一覧は応募レコードを正典に残す（退会チーム名はマスク表示）。新規実装は不要（F03.11委譲）。
- [x] **14. 組織と傘下チームが同地域×ジャンルで札を立てた重複** → 市は **scope で重複排除しない**（並べるだけ）。札詳細で札主の所属（組織/チーム）を明示表示し利用者が区別できるようにする。重複排除は運用ポリシー（例: 親組織は別カテゴリ運用）に委譲。02_api_design §3.1。
- [x] **15. フレンドフォルダ削除時の宛先孤立** → F01.5 のフォルダ削除フックで `recruitment_friend_targets` の当該行を削除。万一孤立しても配信/アクセス解決は存在しないフォルダを空集合として安全に無視（症状を隠さずログ記録）。01_data_model §4。
- [x] **16. 地域名の多言語表示** → Phase 1 はマスタの日本語名表示（コード絞り込みは全言語機能）。多言語名は Phase 2 でマスタ訳列 or i18nキー対応表を追加。03_ui_i18n §6.2。
- [x] **17. フレンド宛先の実装依存（F01.5）** → `team_friends`（V9.072）実装済のため `ALL_FRIENDS`/`TEAM` は Phase 1 可。`FOLDER` は `team_friend_folders` 実装完了が前提（未実装なら gating）。README §4 / 01_data_model §4。

---

## 4. ステータス確定条件（🟢設計確定の根拠）

本書を `🟢 設計確定` とする条件は以下を満たすこと。**すべて充足済み**。

- [x] §0 の前提（市町村マスタ非新規・地域列追加・visibility/source_type拡張・フレンド宛先テーブル）が御裁可済み。
- [x] §3 未解決事項が全て解決方針確定（[x]）。
- [x] 認可マトリクスが docs/security の deny-by-default に整合し、§1.6 許可リスト追記が明記されている。
- [x] DDL が CLAUDE.md DB原則（クロスドメインFK禁止・新規UUIDv7・マスタ再利用・論理削除踏襲）に適合（01_data_model §8）。
- [x] i18n 6言語の market 訳が揃っている（03_ui_i18n §6）。
- [x] 既存設計書（F03.11/F01.5/F13.1/security baseline）への相互参照追記が列挙されている（README §5）。

---

## 5. テスト方針（実装フェーズ向けメモ）

- **認可**: 札立て（権限なし→403）、非公開札の非対象閲覧→404、フレンド未成立宛先→403（`MARKET_003`）、最終認証の他人confirm→403。
- **PII**: 公開DTOの禁則ワードテスト（CI必須）。未ログインで `/public/market/**` が 2xx 到達、非公開札は404。
- **状態遷移**: 充足→FULL→最終認証→COMPLETED、期限切れ→AUTO_CANCELLED、手動→CANCELLED＋既応募者通知。自動下げ×最終認証の競合（ロック直列化）。
- **地域**: `city_code`/`prefecture_code` 整合（`MARKET_001`）、県ロールアップ集計、"地域なし" 区画。
- **Flyway**: from-scratch 全適用で番号衝突・順序破綻なし。
