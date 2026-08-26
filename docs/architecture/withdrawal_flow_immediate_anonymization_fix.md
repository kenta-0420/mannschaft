# 退会フロー — 即時匿名化欠落の根治治療 陣立て書（addendum）

> 起票日: 2026-05-18
> 担当: 家老（Plan agent）
> ステータス: 🟡 設計段階（実コード変更なし／マスター御裁可待ち）
> 親設計書（main マージ済）: [`account_purge_cross_domain_refactor.md`](./account_purge_cross_domain_refactor.md) §2.3
> 兄弟設計書（PR #772 OPEN 中）: [`account_purge_last_admin_succession.md`](./account_purge_last_admin_succession.md) §10.10
> 範囲: 本軍議は **「`withdrawUser()` 休眠コード問題」（兄弟設計書 §10.10）のみ**。
> 他の §10 項目（監視・event-pool・シャーディング等）には立ち入らない
> 関連 GDPR 設計書: [`docs/features/F12.3_gdpr_personal_data.md`](../features/F12.3_gdpr_personal_data.md)

---

## §1. 背景と問題定義

### 1.1 殿の検分で確定した事実（2026-05-18）

兄弟設計書 PR #772 のレビュー過程で次の **重大な既存隠れバグ** が発覚した。

| 観察 | 確認方法 | 結果 |
|---|---|---|
| `UserService#withdrawUser()`（[`UserService.java:478-500`](../../backend/src/main/java/com/mannschaft/app/auth/service/UserService.java)）の呼出元 | `grep -r "withdrawUser" backend/src/main` | **プロダクションコード呼出ゼロ**（テスト 1 ファイル + Flyway コメント 1 ファイル + 定義 1 ファイルのみ）|
| `UserAnonymizedEvent`（[`UserAnonymizedEvent.java`](../../backend/src/main/java/com/mannschaft/app/auth/event/UserAnonymizedEvent.java)）の発火点 | `grep -r "new UserAnonymizedEvent" backend/src/main` | **`UserService.java:497` の 1 箇所のみ**（= `withdrawUser` 内 = 発火実績ゼロ）|
| `UserAnonymizedEvent` の購読リスナー数 | `grep -r "UserAnonymizedEvent" backend/src/main` | **8 件配線済**（後述 §3）+ scopefolder は「フックメソッド準備済・リスナー未配線」 |
| 退会受付 API `requestWithdrawal`（[`UserService.java:408-438`](../../backend/src/main/java/com/mannschaft/app/auth/service/UserService.java)）から `withdrawUser` を呼ぶ箇所 | 同上 | **無し**（`requestWithdrawal` は `WithdrawalRequestedEvent` のみ発火）|
| `WithdrawalRequestedEvent` の購読リスナー数 | `grep -r "WithdrawalRequestedEvent" backend/src/main` | **2 件**（[`WithdrawalStripeHandler`](../../backend/src/main/java/com/mannschaft/app/gdpr/service/WithdrawalStripeHandler.java) + [`AuditLogEventListener`](../../backend/src/main/java/com/mannschaft/app/auth/event/AuditLogEventListener.java)）。**いずれも `withdrawUser` を呼ばない** |

### 1.2 結論（要約）

```
[現状の退会フロー — 実装されているもの]

退会画面（settings/account.vue）
   ↓ DELETE /api/v1/users/me
UserController#requestWithdrawal
   ↓
UserService#requestWithdrawal     ← Day 0 — 受付
   ├─ checkNotLastSystemAdmin
   ├─ user.requestDeletion()       ← deleted_at セット のみ
   ├─ revokeAllRefreshTokens
   └─ publish WithdrawalRequestedEvent
        ├─ WithdrawalStripeHandler    (Stripe 解約 WARN — 未実装)
        └─ AuditLogEventListener      (WITHDRAWAL_REQUESTED 監査ログ)

         ─── 30 日間「個人情報が丸見えのまま」放置 ───

AccountPurgeService#purgeExpiredAccounts (cron 4:00 JST)
   ↓
purgeUser                          ← Day 30 — 物理削除
   ├─ 越境 DELETE 21 種（親設計書 §2.1）
   ├─ AuditEventType.WITHDRAWAL_COMPLETED 監査ログ
   └─ userRepository.delete(user)

[本来意図されていた退会フロー — 死蔵されたもの]

UserService#withdrawUser           ← Day 0 — 即時匿名化（呼出元ゼロで死蔵）
   ├─ revokeAllRefreshTokens
   ├─ user.anonymize()              ← 氏名/メール/電話/PII を即時消去
   ├─ user.softDelete()
   └─ publish UserAnonymizedEvent
        ├─ AuthAnonymizationEventListener            (休眠中)
        ├─ FavoriteAnonymizationEventListener         (休眠中)
        ├─ NotificationAnonymizationEventListener     (休眠中)
        ├─ SocialAnonymizationEventListener           (休眠中)
        ├─ IntegrationAnonymizationEventListener      (休眠中)
        ├─ VillageUserCleanerEventListener            (休眠中)
        ├─ WeatherLocationCleanupListener             (休眠中)
        └─ (scopefolder: フックメソッドのみ・リスナー未配線)
```

### 1.3 GDPR 法的観点での影響

| 項目 | 現状 | 想定設計 |
|---|---|---|
| 退会受付 〜 物理削除のラグ | 30 日 | 30 日（変わらず）|
| **その 30 日間の PII 残存状態** | **氏名・暗号化氏名・メール（生）・パスワードハッシュ・@ハンドル・全 OAuth 連携・全 2FA・全プッシュ購読・全フォロー関係・全 Google Calendar 連携・お気に入り全件・天気地点キャッシュ・村ニックネーム・全ピン留めが残存** | これらは即時匿名化・削除されるはず |
| 退会者からの「忘れられる権利」即時行使要求への応答能力 | 不可（30 日待つほかない、または手動運用）| 即時（`UserAnonymizedEvent` で 7 ドメインが即時 clean、scopefolder 配線後は 8 ドメイン）|
| データブリーチ時に流出する PII の量 | 退会者 N 名分 × 30 日分が常時 at-rest | 退会済アカウント分はゼロ |
| GDPR Art.17 Recital 65 解釈 | 「30 日の合理的猶予」として正当化は可能だが、**「即時に PII 残存を最小化する技術的措置がコードに存在するのに動いていない」状態は監督官庁監査時に説明困難** | コードと運用が一致 |

殿の検分メッセージ要旨を再録:

> 「F12.3 設計書では『退会は論理削除のみ・30 日後物理削除』の二段モデルが正だが、後追いで実装された `withdrawUser()` は配線されないまま死蔵されている。CLAUDE.md「障害対応の原則 — 症状を隠さない」級の重大な既存バグ。」

### 1.4 親設計書 §2.3 への波及

親設計書 §2.3「二重実行リスクの既存箇所」は **「即時匿名化リスナーで `oauth_accounts` / `two_factor_auth` が削除済 → 30 日後 `AccountPurgeService` でも削除 → 冪等で実害なし」** を前提としているが、本検分の結果 **「即時匿名化リスナー自体が一度も動いていない」** ことが確定したため、その表の「即時時に削除済 ✅」の列は **全行 ❌** に書き換わる。

つまり親設計書 Phase B-1〜B-6 が「30 日後フェーズで *PurgeEventListener を新設する」ことを前提に進めると、即時時にも 30 日時にも片付かない PII / 連携データが温存される（特に `oauth_accounts` / `two_factor_auth` は **どこにも削除されない**）。本軍議は親 Phase B-1 着工の **前提整理** にあたる。

---

## §2. 現状コードフロー完全マップ

### 2.1 退会受付（Day 0）

| Step | 場所 | 処理 | 副作用 |
|---|---|---|---|
| ① 退会画面送信 | [`frontend/app/pages/settings/account.vue:12`](../../frontend/app/pages/settings/account.vue) | `DELETE /api/v1/users/me` 呼出 | — |
| ② Controller 受付 | [`UserController.java:122-129`](../../backend/src/main/java/com/mannschaft/app/auth/controller/UserController.java) | `userService.requestWithdrawal(userId, req)` 呼出 | — |
| ③ 唯一の SYSTEM_ADMIN 退会ブロック | [`UserService.java:410`](../../backend/src/main/java/com/mannschaft/app/auth/service/UserService.java) `checkNotLastSystemAdmin` | `user_roles JOIN roles` で `SYSTEM_ADMIN` 件数判定 | `BusinessException(GDPR_006)` |
| ④ レートリミット | `UserService.java:413` `authTokenService.checkRateLimit` | Valkey の `mannschaft:auth:withdrawal_attempt:{userId}` を 1 分 3 回まで | — |
| ⑤ パスワード検証 | `UserService.java:422-426` | OAuth 専用ユーザーはスキップ | `BusinessException(AUTH_010)` |
| ⑥ **論理削除** | `UserService.java:429-430` `user.requestDeletion()` + `userRepository.save(user)` | `users.deleted_at = NOW()` のみ更新。**PII は無加工で残存** | — |
| ⑦ 全 Refresh Token 失効 | `UserService.java:433` `revokeAllRefreshTokens` | `refresh_tokens` の `revokedAt = NOW()` を一括更新 | — |
| ⑧ セッション無効化 | `UserService.java:434` `authTokenService.setUserInvalidationTimestamp` | Valkey の `mannschaft:auth:user_invalidated:{userId}` 書込 | 現行セッション全失効 |
| ⑨ イベント発火 | `UserService.java:437` `eventPublisher.publish(new WithdrawalRequestedEvent(userId, user.getEmail()))` | `@TransactionalEventListener(AFTER_COMMIT)` の 2 件が起動 | 下記 2.2 |

### 2.2 `WithdrawalRequestedEvent` 購読リスナー（Day 0 で動くもの）

| # | リスナー | 場所 | 処理内容 | `withdrawUser` を呼ぶか |
|---|---|---|---|---|
| 1 | `WithdrawalStripeHandler.handleWithdrawal` | [`WithdrawalStripeHandler.java:31-53`](../../backend/src/main/java/com/mannschaft/app/gdpr/service/WithdrawalStripeHandler.java) | StripeCustomer 検索のみ。**`log.warn("Stripeサブスクキャンセル未実装")`** で実 API 呼出はせず、DB 状態更新も無し | ❌ |
| 2 | `AuditLogEventListener.handleWithdrawalRequested` | [`AuditLogEventListener.java:208-220`](../../backend/src/main/java/com/mannschaft/app/auth/event/AuditLogEventListener.java) | `AuditEventType.WITHDRAWAL_REQUESTED` を `audit_logs` に書込 | ❌ |

**結論:** Day 0 で動くのは「Stripe 未実装 WARN」と「監査ログ記録」のみ。**PII 消去・連携 OAuth 解除・プッシュ購読解除など実際の `clean up` は何ひとつ起きていない**。

### 2.3 退会キャンセル（Day 0 〜 Day 30 の任意時点）

| Step | 場所 | 処理 |
|---|---|---|
| ① キャンセル送信 | フロント `POST /api/v1/users/me/withdrawal/cancel` | — |
| ② Controller | [`UserController.java:134-140`](../../backend/src/main/java/com/mannschaft/app/auth/controller/UserController.java) | `userService.cancelWithdrawal(userId)` |
| ③ Service | [`UserService.java:446-459`](../../backend/src/main/java/com/mannschaft/app/auth/service/UserService.java) | `user.cancelDeletion()` で `deleted_at = NULL` に戻すのみ |

**重要:** 現状は `deleted_at` を NULL に戻すだけで全完了。これは **「PII が無加工で残存している」前提に依存した設計**。即時匿名化が走ると **このキャンセルは事実上死ぬ**（後述 §5）。

### 2.4 30 日後物理削除（Day 30）

| Step | 場所 | 処理 |
|---|---|---|
| ① cron 起動 | [`AccountPurgeService.java:81`](../../backend/src/main/java/com/mannschaft/app/gdpr/service/AccountPurgeService.java) `@Scheduled(cron = "0 0 4 * * *", zone = "Asia/Tokyo")` | 毎日 AM4:00 JST |
| ② 対象抽出 | `userRepository.findPurgeTargets(cutoff=now-30d, PageRequest.of(0, 100))` | `deleted_at < cutoff AND purged_at IS NULL` |
| ③ ループ内 `purgeUser(user)` | `AccountPurgeService.java:110-222`（`@Transactional`）| 越境 DML 21 種（親設計書 §2.1 参照）+ `AuditEventType.WITHDRAWAL_COMPLETED` 監査ログ + `userRepository.delete(user)` |

`AccountPurgeService` は `UserAnonymizedEvent` も `WithdrawalRequestedEvent` も発火しない。**したがって 7 ドメインの即時匿名化リスナー（+ scopefolder 未配線）は 30 日後フェーズでも一切呼ばれない**。

### 2.5 `withdrawUser` 関数の中身（休眠中）

[`UserService.java:476-500`](../../backend/src/main/java/com/mannschaft/app/auth/service/UserService.java) `@Transactional`：

```java
public void withdrawUser(Long userId) {
    checkNotLastSystemAdmin(userId);              // ① 唯一 SYSTEM_ADMIN ブロック
    UserEntity user = findUserOrThrow(userId);
    String originalEmail = user.getEmail();        // ② 匿名化前 email 退避（イベント用）
    revokeAllRefreshTokens(userId);
    authTokenService.setUserInvalidationTimestamp(userId);
    user.anonymize();                              // ③ PII 全消去（下記 §2.6）
    user.softDelete();                             // ④ deleted_at セット（冪等）
    userRepository.save(user);
    eventPublisher.publish(
        new UserAnonymizedEvent(userId, originalEmail));   // ⑤ 7 配線済ドメインに伝播
}
```

### 2.6 `UserEntity#anonymize()` の効果（[`UserEntity.java:268`](../../backend/src/main/java/com/mannschaft/app/auth/entity/UserEntity.java)）

| 上書き対象 | 上書き後の値 | 備考 |
|---|---|---|
| `email` | `"withdrawn-{UUID}@deleted.mannschaft.internal"` | UNIQUE + NOT NULL 制約のためダミー値必須 |
| `passwordHash` | `null` | ログイン不可化 |
| `lastName` / `firstName` | `"退会済み"` / `"ユーザー"` | 暗号化 PII を固定値で上書き |
| `lastNameKana` / `firstNameKana` | `null` | — |
| `displayName` | `"退会済みユーザー"` | — |
| `nickname2` | `null` | — |
| `contactHandle` | `null` | UNIQUE 制約あり（NULL 許容）|
| `handleSearchable` | `false` | — |

`softDelete()` は `deletedAt == null` の場合のみ `deletedAt = NOW()` を冪等にセット（行 308-312）。

---

## §3. 9 ドメイン匿名化リスナー網羅表（**7 配線済 + 1 未配線 + 1 別構造**）— 現状休眠の影響

| # | ドメイン | リスナー / クラス | 監視テーブル | 操作 | 現状休眠の影響 |
|---|---|---|---|---|---|
| 1 | auth | `AuthAnonymizationEventListener` | `oauth_accounts`, `two_factor_auth` | DELETE（`deleteByUserId`）| 退会後 30 日間 OAuth 連携と 2FA 設定が残存。**さらに 30 日後 `AccountPurgeService` も削除しない**（親 §2.3 注: 二重削除は冪等な前提だが、片方しか動かない場合は完全に温存）|
| 2 | favorite | `FavoriteAnonymizationEventListener` | `user_favorites` | DELETE（`deleteAllByUserId`）| お気に入りデータ残存。**`AccountPurgeService` 側にも削除無し** → 永久残存 |
| 3 | notification | `NotificationAnonymizationEventListener` | `push_subscriptions`, `notification_preferences`, `notification_type_preferences` | DELETE × 3 | プッシュ通知購読が残存。**退会後にユーザーへ通知が飛び続ける可能性**。GDPR 同意撤回権侵害リスク |
| 4 | social | `SocialAnonymizationEventListener` | `follows`, `user_social_profiles` | follows DELETE + profile `deactivate()` | フォロー関係残存。退会者を他ユーザーが follow 一覧で見続ける |
| 5 | schedule | `IntegrationAnonymizationEventListener` | `user_google_calendar_connections` | DELETE（`deleteByUserId`）| **Google Calendar OAuth トークンが残存 → 退会後もカレンダー連携が裏で生き続ける**。最重大級の GDPR 違反候補 |
| 6 | village | `VillageUserCleanerEventListener` | `user_village_nicknames`, `user_village_pins`, `village_memberships` | DELETE × 2 + `leftAt`/`bannedReason="ANONYMIZED"` | 村ニックネーム・ピンが残存。`village_memberships` も active のまま |
| 7 | weather | `WeatherLocationCleanupListener` | `user_weather_locations` | DELETE（`deleteByUserId`）| 地理情報残存（個人特定可能性ありと設計書明記） |
| 8 | scopefolder | **未配線**（フックメソッド `MyScopeFolderService#deleteAllByUserId` のみ存在、リスナー無し）| `my_scope_folders`, `my_scope_folder_items` | （実行されない）| F15.3 設計書 §9.4 で「後続 PR でリスナー追加」と書かれたまま塩漬け（[`MyScopeFolderService.java:457`](../../backend/src/main/java/com/mannschaft/app/scopefolder/service/MyScopeFolderService.java)）|
| 9 | schedule | `CalendarLayerLifecycleListener`（**本監査より後に新設・配線済**）| `user_calendar_layer_settings` | DELETE（`deleteByUserId`。即時＝弱匿名化の段）| （該当なし。F03.19 W1-e で最初から配線して追加したため休眠期を持たない）|

> **追記（F03.19 W1-e）:** 上表 #9 `user_calendar_layer_settings` は本監査の後に新設された表であり、当初の「7 配線済 + 1 未配線 + 1 別構造」の数え上げには含まれない。本表は**退会時に削除される表の正本一覧**として今後も追記していく（F03.19 設計書 §10.4 の指示による）。
>
> **注:** 指示書では「9 ドメイン」だったが、本検分の結果 **配線済は 7 + scopefolder 未配線 + chart は未実装 = 実体 7 リスナー**。`chart` ドメインには `UserAnonymizedEvent` を購読する Listener は存在しない（`grep` 確定）。`chart_records.anonymizeCustomerUserId` は `AccountPurgeService` から直接呼ばれているのみ（親 §2.1 表 #11）。

### 3.1 chart / scopefolder の扱い

| ドメイン | 即時匿名化リスナー | 30 日後 `AccountPurgeService` | 結論 |
|---|---|---|---|
| chart | ❌ 未実装 | ✅ `chart_records.anonymizeCustomerUserId` | **30 日まで PII 残存だが最終的に匿名化はされる**（親設計書 Phase B-4 で AccountPurgedEvent 経由に整理予定）|
| scopefolder | ❌ 未配線 | ❌ どこからも呼ばれない | **永久残存** — 即時匿名化を有効化する際に同時に整備すべき |

---

## §4. `withdrawUser` の歴史と設計意図推定

### 4.1 git 履歴

| commit | 日付 | 内容 |
|---|---|---|
| [`badfe701e`](#) | 2026-05-09 | **`withdrawUser()` 初版追加**。コミットメッセージ抜粋: 「即時匿名化退会処理。セッション無効化 → anonymize() → UserAnonymizedEvent 発行の順で実行」「1000 万ユーザー耐久 DB 再構築 Phase 0-α」 |
| [`5d064d4d2`](#) | 2026-05-09 | `anonymize() + softDelete()` を二段階に分離 + 単体テスト 4 ケース追加 |
| [`707a06cc9`](#) | 2026-05-10 | `@Transactional` 越境 TODO コメント追記 |

**配線コミット（呼出元接続）は存在しない**。`UserController#requestWithdrawal` → `UserService#requestWithdrawal` の経路は `withdrawUser` 追加以前から存在し、変更されていない。

### 4.2 F12.3 設計書との整合

[`docs/features/F12.3_gdpr_personal_data.md`](../features/F12.3_gdpr_personal_data.md) §1「既存実装との関係」表（行 22-30）：

> 退会（論理削除）｜`UserService.requestWithdrawal()`｜**そのまま活用**
> 退会取り消し｜`UserService.cancelWithdrawal()`｜**そのまま活用（30 日猶予）**

§5「退会フロー拡張」（行 448-485）も `requestWithdrawal` → `WithdrawalRequestedEvent` → 30 日後 `AccountPurgeService` 物理削除の **二段モデル** を明確に正としている。
**F12.3 設計書には `UserAnonymizedEvent` も `withdrawUser()` も登場しない**。

### 4.3 設計意図の推定（家老見解）

`withdrawUser()` 追加コミット（`badfe701e`）の文言「1000 万ユーザー耐久 DB 再構築 Phase 0-α」と Phase 1〜2 進行に伴うイベント配線が、F12.3 の「30 日猶予モデル」と整合せずに **配線が保留されたまま放置された** と推定される。

具体的な衝突点:

| 観点 | F12.3 二段モデル | `withdrawUser()` 即時匿名化モデル |
|---|---|---|
| キャンセル復帰 | `deleted_at` を `NULL` に戻すだけで全復帰可能 | `anonymize()` 不可逆（氏名・メールは UUID 上書き）→ **キャンセル後にユーザーは「退会済みユーザー」のまま** |
| PII の at-rest 期間 | 30 日 | 0 日（即時消去）|
| OAuth/2FA データの保護 | 30 日後一括削除 | 即時削除 |
| Stripe 連携 | `WithdrawalRequestedEvent` で別ハンドラに委譲（現実は未実装 WARN）| `withdrawUser` 自体は触らない |

「`withdrawUser` を `requestWithdrawal` から呼ぶ」と素朴に配線するだけだと **キャンセル機能が事実上死ぬ** ため、配線者は判断できずに塩漬けにした、というのが家老の最終推定。
これは **「孤立コード」ではなく「設計衝突で保留中の半実装」** という性質。CLAUDE.md「障害対応の原則」§3「未実装は未実装として対処する」の精神に照らし、本軍議で正面突破する。

---

## §5. 退会キャンセル機能との整合性論点（重要）

### 5.1 現状の挙動

```
Day 0  退会受付          → deleted_at = NOW(), PII 無加工残存
Day 10 cancelWithdrawal  → deleted_at = NULL に戻る → 完全復帰（email / 氏名 / 連携 全て無傷）
Day 30 物理削除バッチ    → user 行ごと delete
```

### 5.2 即時匿名化を素朴導入した場合の挙動

```
Day 0  退会受付 + withdrawUser  → deleted_at = NOW(), email=withdrawn-uuid@..., 氏名="退会済み ユーザー"
Day 10 cancelWithdrawal         → deleted_at = NULL に戻る
                                  → だがログインは不可（password_hash も null）
                                  → 氏名・メール・@ハンドルも全部失われている
                                  → user は復帰したが本人ではない
Day 30 物理削除バッチ           → user 行ごと delete
```

これは **事実上、現状の 30 日猶予キャンセル機能を殺す**。

### 5.3 F12.3 が二段モデルを採用した理由（推定）

| 理由 | 根拠 |
|---|---|
| GDPR Art.17 Recital 65 は「合理的な猶予期間」を許容している | F12.3 §1 / §5 |
| Stripe 等の外部連携の整合（30 日内にチャージバック等の発生可能性）| F12.3 §5.2 |
| **ユーザー側「やっぱりやめた」UX への配慮** | F12.3 §1.1「30 日猶予で取消可能」を機能要件として明記 |
| 監査ログとの紐付けが残るため運用上のトレーサビリティ確保 | F12.3 §6 |

### 5.4 即時匿名化と 30 日猶予の両立 — 設計矛盾の解消案

3 通り存在する:

| 案 | 即時匿名化 | 30 日猶予 | UX |
|---|---|---|---|
| α | する | **キャンセル機能廃止** | 「退会＝即時 PII 消去・取消不可」と明示。30 日猶予は user 行物理削除までのバッファのみ |
| β | する | **キャンセル時は氏名「（取消済）退会者」固定で復帰** | 部分復帰。法務的な「やっぱりやめた」は受けるが個人特定情報は失う |
| γ | **しない**（現状維持） | する | 現状の二段モデル維持。PII 30 日残存はそのまま GDPR Recital 65 で正当化 |

各案の比較は §6 で詳述。

---

## §6. 根治治療の選択肢（案 α 〜 案 ε 比較）

### 6.1 案 α 「`requestWithdrawal` 末尾で `withdrawUser` を直接呼ぶ」（同一トランザクション統合）

```java
// requestWithdrawal の末尾
publish WithdrawalRequestedEvent(...)
withdrawUser(userId);   // 追加
```

| 観点 | 評価 |
|---|---|
| 実装コスト | 最小（1 行追加 + 関連テスト書き換え）|
| キャンセル機能との整合 | ❌ §5.2 — 完全に殺す |
| トランザクション | `withdrawUser` 自身が `@Transactional` のため `REQUIRES_NEW` 等の調整必要 |
| 8 リスナー起動順序 | `AFTER_COMMIT` のためコミット後に起動 — OK |
| 障害時挙動 | `withdrawUser` 内例外で `requestWithdrawal` 全体がロールバック（受付自体が失敗）→ ユーザー側は再試行のみ |
| GDPR 即時応答能力 | ✅ 即時 |

**家老評価:** キャンセル機能を捨てる覚悟があれば最も単純。法務確認必須。

### 6.2 案 β 「`WithdrawalRequestedEvent` 購読の新規 `ImmediateAnonymizationEventListener` 作成」

```java
@Async("event-pool")
@Transactional(propagation = REQUIRES_NEW)
@TransactionalEventListener(phase = AFTER_COMMIT)
void on(WithdrawalRequestedEvent ev) {
    userService.withdrawUser(ev.getUserId());
}
```

| 観点 | 評価 |
|---|---|
| 実装コスト | 小（1 クラス追加）|
| キャンセル機能との整合 | ❌ §5.2 — 案 α と同じく殺す |
| トランザクション分離 | `REQUIRES_NEW` で独立 → 受付自体は通る／匿名化失敗時はリトライ可能 |
| 8 リスナー起動順序 | `withdrawUser` 内発火の `UserAnonymizedEvent` も `AFTER_COMMIT` → 二段の `AFTER_COMMIT` チェーン |
| GDPR 即時応答能力 | ✅ 即時 |

**家老評価:** 案 α より疎結合で安全。ただしキャンセル機能殺害という性質は変わらない。

### 6.3 案 γ 「`UserAnonymizedEvent` 廃止 + 8 リスナーを `WithdrawalRequestedEvent` 購読に変更」

| 観点 | 評価 |
|---|---|
| 実装コスト | 中（8 リスナーすべて改修 + 1 イベント廃止）|
| キャンセル機能との整合 | ❌ §5.2 同様に殺す |
| イベント体系の整理 | ✅ イベント数削減・責務集約 |
| 既存テスト影響 | 8 リスナーすべてのテスト書き換え |

**家老評価:** イベント体系は綺麗になるが、キャンセル機能の問題は依然解消されない。コスト高。

### 6.4 案 δ 「即時匿名化フェーズ自体を廃止し `withdrawUser` を撤去」（F12.3 二段モデルへ完全帰結）

| 観点 | 評価 |
|---|---|
| 実装コスト | 小（`withdrawUser` 削除 + 8 リスナー削除 + `UserAnonymizedEvent` 削除 + テスト削除）|
| キャンセル機能との整合 | ✅ 現状維持で問題なし |
| GDPR 即時応答能力 | ❌ 30 日のラグはそのまま（Recital 65 で正当化）|
| 8 リスナーの労力を捨てる | ❌ 過去の実装労力が損失 — 親設計書 Phase B（`AccountPurgedEvent` 体系）に同じロジックを移植する必要 |
| 親設計書 §2.3 への波及 | ✅ §2.3 表は「即時時 ❌・30 日後 ✅」で整合（親 Phase B-1〜B-6 で 8 ドメイン分の `*PurgeEventListener` を整備すれば完結）|

**家老評価:** 法務的に最もリスクが低い。**ただし「対処療法ではない」と言い切るには、8 リスナーの 30 日後フェーズへの完全移植が必須**。親設計書 Phase B-1〜B-6 がまさにそれをやるので、親設計書のスコープに本 8 ドメインを追加合流させる形になる。

### 6.5 案 ε（家老追加）「二段匿名化モデル — 受付即時に "弱匿名化" / 30 日後に "強匿名化"」

```
Day 0 退会受付
  ├─ user.softDelete()
  ├─ 弱匿名化（一部のみ）
  │   ├─ 即時セッション無効化（既存どおり）
  │   ├─ プッシュ通知購読 DELETE（NotificationListener 一部）
  │   ├─ Google Calendar 連携 DELETE（IntegrationListener）
  │   ├─ user_weather_locations DELETE（WeatherListener）
  │   └─ user_favorites DELETE（FavoriteListener）
  │       ↑ いずれも「キャンセルしても復元不要」なデータ群
  └─ publish WithdrawalRequestedEvent
                ↓
Day 0 〜 30  キャンセル可能（user 本体 PII は無加工で残る → 復帰可能）
                ↓
Day 30 物理削除直前
  ├─ user.anonymize()
  ├─ 残り強匿名化（auth/social/village 等）
  ├─ AccountPurgeService が user 行 delete
  └─ publish AccountPurgedEvent（親 Phase B 体系）
```

| 観点 | 評価 |
|---|---|
| 実装コスト | 中〜大（受付経路に "弱匿名化" イベントを新設、8 リスナーを「即時 OK / 30 日後」に分類）|
| キャンセル機能との整合 | ✅ user 本体 PII は無加工 → 完全復帰可能 |
| GDPR 即時応答能力 | △ プッシュ・OAuth トークン・地理情報など **退会後の外部連携リスク** が即時消える（最も気にすべき項目） |
| 法務リスクのバランス | ✅ 最良 — 「退会後すぐに止めるべきもの」と「30 日猶予して問題ないもの」を仕分け |
| 親設計書 Phase B との合流 | ✅ 強匿名化は親 `AccountPurgedEvent` に統合可能 |
| 8 リスナーの仕分けが必要 | 表 §3 に追加列「即時/30 日後」を設計確定後に書き込む必要 |

**家老評価:** **本軍議の推奨案**。GDPR 即時応答能力とキャンセル UX の両立、親設計書 Phase B との整合、既存リスナー資産の活用、すべてのバランスが最良。

### 6.6 案 ζ「現状維持 + ドキュメント更新」（消極案・参考）

`withdrawUser` を **明示的に `@Deprecated` + 削除予告コメント** にして、F12.3 の二段モデルが現状の正であることを宣言。コードは残すが「使ってはいけない」と運用で線引きする。

| 観点 | 評価 |
|---|---|
| 実装コスト | 極小 |
| GDPR 即時応答能力 | ❌ 改善なし |
| CLAUDE.md「対処療法禁止」 | △ ドキュメント明示は対処療法ではないが、根本問題（30 日 PII 残存）は放置 |

**家老評価:** 案 δ または案 ε と組合せれば「現状の問題を可視化しつつ削除前段階として一時運用」する手段になる。単独採用は非推奨。

---

## §7. 家老の推奨案と理由

### 7.1 結論

**案 ε（二段匿名化モデル）を推奨**。ただし **マスター御裁可必須**（GDPR / UX 双方に影響大）。

次善は案 δ + 親設計書 Phase B 連携。

### 7.2 推奨理由

1. **GDPR Art.17 への応答力向上**: プッシュ通知・OAuth トークン・Google Calendar 連携・地理情報は退会と同時に止まるのが利用者の期待値。これは案 δ では実現できない（30 日待つ）。
2. **キャンセル UX 維持**: 案 α/β/γ は現状の「30 日以内取消可能」を殺す。これは F12.3 設計書の根幹で、勝手に変更すべきでない（マスター御裁可が出れば話は別）。
3. **既存 7 リスナー資産の活用**: 案 δ だと 8 ドメインのリスナーロジックを親 Phase B にすべて再実装する必要がある。案 ε なら大半をそのまま流用できる。
4. **親設計書 §2.3 との整合性回復**: 案 ε 採用なら親 §2.3 の表は「即時時に 4 ドメインが clean ✅」「30 日後に残り 4 ドメイン + auth 本体 ✅」と素直に書き換わる。
5. **CLAUDE.md「対処療法禁止」原則**: 案 ζ は対処療法。案 α/β/γ はキャンセル UX を殺すという別の問題を作る。案 ε は両立解。

### 7.3 推奨 Phase 分け（御裁可後の実装計画）

| Phase | 範囲 | PR 数 |
|---|---|---|
| **W-A** | 設計書本 PR（本書）+ マスター御裁可 | 1（本 PR）|
| **W-B** | 8 リスナーの「即時/30 日後」仕分け確定 + 設計追記 | 1（追記 PR）|
| **W-C** | `ImmediateAnonymizationEventListener` 新設 + 即時匿名化対象リスナーを `WithdrawalRequestedEvent` 購読化 | 2〜3 |
| **W-D** | 親設計書 Phase B-1〜B-6 着工と歩調を合わせ、残り 4 ドメインを `AccountPurgedEvent` 体系へ移植 | 親 PR と合流 |
| **W-E** | `withdrawUser()` 関数 + `UserAnonymizedEvent` の正式撤去（または整理） | 1 |
| **W-F** | E2E + ステージング 7 日運用 + 法務最終確認 + 親設計書 §2.3 への反映 PR | 2〜3 |

合計 **7〜10 PR**。親設計書 Phase B と並行可能（W-D は合流ポイント）。

---

## §8. 親設計書 §2.3 + 兄弟設計書 §10.10 への波及対応計画

### 8.1 親設計書 `account_purge_cross_domain_refactor.md` §2.3 への反映

| 現状記述 | 反映内容 |
|---|---|
| 「`oauth_accounts`：✅ AuthAnonymizationEventListener が即時時に削除済」| **❌ AuthAnonymizationEventListener は休眠中で発火実績ゼロ** に書換 |
| 「`two_factor_auth`：✅ 同上」| 同上 |
| 「`push_subscriptions` 等：✅ NotificationAnonymizationEventListener」| 同上 |
| 「`user_favorites`：✅ FavoriteAnonymizationEventListener」| 同上 |
| 「`follows`, `user_social_profiles`：✅ SocialAnonymizationEventListener」| 同上 |
| 「`user_google_calendar_connections`：✅ IntegrationAnonymizationEventListener」| 同上 |
| 「`user_village_*`：✅ VillageUserCleanerEventListener」| 同上 |

反映 PR（御裁可後）: 案 ε 採用なら「即時/30 日後で仕分けた表」に書き換える別 PR。案 δ なら「8 リスナー削除予定」を明記して `AccountPurgedEvent` 体系で完結する旨を追記する別 PR。

### 8.2 兄弟設計書 PR #772 `account_purge_last_admin_succession.md` §10.10 の消化

兄弟設計書 §10.10 は「`withdrawUser` 休眠の指摘 + 別軍議起票推奨」を主旨としている。本軍議が起票し設計書を確定させたことで、§10.10 を「→ `withdrawal_flow_immediate_anonymization_fix.md` で別軍議化済」と更新する PR を別途立てる（御裁可後）。
PR #772 マージ後の addendum として 1 行差分の PR。

### 8.3 親設計書 Phase B-1 への影響

| 案 | Phase B-1 への影響 |
|---|---|
| α / β / γ | 即時時に動くリスナーが増えるため、親 §2.3 二重実行リスクが顕在化（冪等性確認が Phase B-1 着工前提に格上げ）|
| δ | 親 Phase B-1〜B-6 に「8 ドメイン分の旧即時時リスナーのロジック移植」が追加スコープ → 計画延伸 |
| ε（推奨）| 親 Phase B-1〜B-6 は「強匿名化 4 ドメイン」のみで完結（弱匿名化 4 ドメインは本軍議 W-C で対応）→ 親計画はむしろ縮小 |

**最大ポイント:** 親設計書 Phase B-1（role ドメイン）は §3.5 で「`MembershipChangedEvent(REMOVED)` を `removeMember` 経由で発火」する設計だが、これは **本軍議の選択に依存しない**（role ドメインは 8 リスナーのどれも所管していない）。よって **親 Phase B-1 と本軍議 W-C は完全に独立並行可能**。

---

## §9. 段階リリース計画（Phase 分け / PR 粒度 / ロールバック）

§7.3 の Phase 表に加え、ロールバック計画:

| Phase | ロールバック手段 | 所要時間 |
|---|---|---|
| W-A 設計書 PR | `gh pr close` のみ | 1 分 |
| W-B 追記 PR | `git revert` | 5 分 |
| W-C リスナー新設 | `@Profile("disabled")` 化 or `git revert` — 既存 `requestWithdrawal` は無傷なので機能影響なし | 5 分 |
| W-D 親 Phase B 合流 | 親設計書側ロールバック手順に従う | 親設計書 §7 参照 |
| W-E `withdrawUser` 撤去 | `git revert` — テストコードも一緒に戻る | 10 分 |
| W-F 法務確認 | コードロールバック不要、設計書再修正のみ | — |

**緊急時の最終手段:** `UserService#withdrawUser` 自体の `@Transactional` メソッドに `if (true) throw new UnsupportedOperationException()` をガード追加することで即座に休眠状態に戻せる。

---

## §10. GDPR 法的観点での留意点

### 10.1 30 日タイムリミット

GDPR Art.17 Recital 65 は「データ主体の要求から **1 ヶ月以内（緊急性により 3 ヶ月まで延長可）** に消去」を求めている。
現状の 30 日後物理削除は Recital 65 のギリギリ。**「30 日間 PII が無加工残存している」状態は、監督官庁が技術的措置の有無を問うた場合に説明困難**（8 リスナーが存在しながら動いていないため）。

### 10.2 同意撤回権（Art.7）

退会＝同意撤回と解釈される場合、**プッシュ通知購読・Google Calendar OAuth トークン**などの「同意ベース処理」は即時停止が望ましい。
案 ε はこれらを即時消去するため、Art.7 への応答性が大幅に向上する。

### 10.3 案 ε の Recital 65 整合性

| 即時消去対象（弱匿名化）| Recital 65 整合 |
|---|---|
| プッシュ購読・Google Calendar 連携・地理情報・お気に入り | ✅ 同意ベース処理・外部連携リスクのため即時消去が正当 |
| user 本体氏名・メール・OAuth 連携・2FA・フォロー関係・村ニックネーム | ✅ 30 日キャンセル UX のため猶予期間正当化（Recital 65 認容範囲）|

### 10.4 監査ログ

`AuditEventType.WITHDRAWAL_REQUESTED`（Day 0）+ `AuditEventType.WITHDRAWAL_COMPLETED`（Day 30）はそのまま継続。
案 ε 採用なら追加で `AuditEventType.WITHDRAWAL_PARTIAL_ANONYMIZED`（Day 0 弱匿名化完了）を新設する必要。詳細は W-B で確定。

### 10.5 法務レビューのタイミング

- 案 ε / δ いずれを採用する場合も、**W-A 御裁可時点で 1 度・W-F リリース前に 1 度** のレビュー推奨。
- 案 α / β / γ（キャンセル機能殺害）を採用する場合は、**ToS 改訂が必要** になる可能性が高く法務レビュー必須。

---

## §11. テスト計画

### 11.1 ユニットテスト

| 対象 | テストケース |
|---|---|
| `ImmediateAnonymizationEventListener`（案 ε 新設）| 受付イベント → 4 弱匿名化リスナーが起動することを `verify` |
| 既存 `UserServiceTest#WithdrawUser` の 4 ケース | 案 ε 採用なら「強匿名化のみ」になるため `verify` 対象差替 |
| `cancelWithdrawal` テスト追加 | Day 0 受付 → Day 10 cancel → 弱匿名化済データは戻らないが user 本体 PII は無傷であることを確認 |

### 11.2 統合テスト（`@SpringBootTest` + Testcontainers）

| シナリオ | 検証内容 |
|---|---|
| 退会受付 → AFTER_COMMIT | `push_subscriptions`, `user_google_calendar_connections`, `user_weather_locations`, `user_favorites` が消えていることを DB クエリで確認 |
| 退会受付 → cancel | user 本体 PII（email / 氏名 / @ハンドル）が無加工で残っていることを確認 |
| 30 日経過 → purge | 残り強匿名化対象（`oauth_accounts`, `two_factor_auth`, `follows`, `user_social_profiles`, `user_village_*`）が消えていることを確認 |
| イベント順序 | `WithdrawalRequestedEvent` → 弱リスナー実行 → 30 日後 `AccountPurgedEvent` → 強リスナー実行の 2 段が正しく機能 |

### 11.3 E2E テスト（Playwright）

- WFI-001: 退会 → 即時に プッシュ通知が止まる
- WFI-002: 退会 → Google Calendar 連携 UI が即時無効になる
- WFI-003: 退会 → 10 日後 cancel → 復帰してログイン可能
- WFI-004: 退会 → 31 日後 → ログイン不可・user 行物理削除済

### 11.4 GDPR シナリオテスト

- `data-export` を Day 0 と Day 10 で取得し、弱匿名化対象のデータがエクスポートに含まれないことを確認
- 「忘れられる権利の即時行使」テスト: 退会 1 時間後にプッシュ通知が来ないことを実機検証

---

## §12. 親設計書 Phase B-1 への影響

| 観点 | 影響 |
|---|---|
| 着手順序 | **本軍議の W-A（御裁可）と並行に Phase B-1（role）を着工可能**。役務分担: 親 B-1 は role / 本軍議 W-C は notification/schedule/weather/favorite |
| `RolePurgeEventListener` の設計 | 影響なし — role ドメインは弱匿名化対象ではない |
| `AccountPurgedEvent` 体系 | 案 ε 採用なら親が定義する `AccountPurgedEvent` をそのまま 30 日後の強匿名化チャネルとして流用 → **親 §3.2 payload は変更不要** |
| 親 §3.5（F15.4 Caveat 解消）| 影響なし |
| 親 §9.6（最後の ADMIN 退会）| 兄弟設計書 PR #772 がスコープ。本軍議は触れない |

**結論:** 本軍議は親 Phase B-1 の前提条件ではなく、**「親 §2.3 の表記憶を正す」ための addendum**。親 Phase B-1 出陣を阻害しない。

---

## §13. 未解決事項（マスター御裁可待ち → **2026-05-18 一括裁定済**）

> **🏯 マスター裁定（2026-05-18・「よきにはからえ」一括承認）**
>
> 下記の論点はすべて家老推奨案を採用とする。重大論点（§13.8 / §13.12）含む全項目で殿推奨案 A を採用：
>
> - **§13.1 採用案**: ✅ **案 ε 採用**（二段匿名化モデル）
> - **§13.2 法務レビュー**: ✅ Phase W-A + W-F の両方で実施（**必須化**）
> - **§13.3 8 リスナーの弱/強仕分け**: ✅ 家老暫定案採用（弱: notification / schedule / weather / favorite、強: auth / social / village / scopefolder）
> - **§13.4 cancel-withdrawal API レスポンス**: ✅ `requiresReconfiguration` 配列を返し再設定 UI 整備、i18n 6 言語同時投入
> - **§13.5 W-D 合流タイミング**: ✅ 親 Phase B の各 PR（B-1 以外）と同時実施
> - **§13.6 案 δ サンクコスト**: 案 ε 採用のため不要（記録のみ）
> - **§13.7 Favorite TX 伝播**: ✅ **A 採用**（`@Async + REQUIRES_NEW + AFTER_COMMIT` に揃える、W-A 前提条件）
> - **§13.8 案 ε のキャンセル UX 部分復帰**: ✅ **A 採用**（許容 + 再設定 UI 整備）。「退会キャンセルは『気が変わった』時の救済」と整理。Phase W-A リリース時に「キャンセル時の挙動変更」を Release Notes に明示
> - **§13.9 弱匿名化リスナー切替の不可逆性**: ✅ **A + B 採用**（§9 表に「データ復元不能」明記 + W-C はカナリアリリース 1% × 7 日で段階的有効化必須化）
> - **§13.10 event-pool 枯渇 + Google Calendar revoke**: ✅ **A + B 両方採用**（`withdrawal-pool` 分離を W-A 前提インフラに、Google `oauth2/revoke` 呼出を W-C で追加・3 回リトライ + WARN）
> - **§13.11 WithdrawalCancelledEvent 発火欠落**: ✅ **別途早馬案件として 1 PR で根治**（本軍議 W-C と並行可能）
> - **§13.12 CLAUDE.md 原則 4 改訂**: ✅ **A 採用**（原則 4 を「PII 消去のタイミングは GDPR 30 日タイムリミット内であれば段階実施可」に緩める）。Phase W-A の同時 PR で CLAUDE.md 改訂文面案を含める
>
> 残論点なし。本設計書は出陣可能状態。


### 13.1 採用案の決定（最重要）

**論点:** §6 の 6 案のうちどれを採用するか？

- 推奨: 案 ε（二段匿名化モデル）
- 次善: 案 δ（即時匿名化撤去 + F12.3 二段モデル帰結）

**マスター御裁可必須項目:**
1. 即時に止めたい外部連携の特定（プッシュ・Calendar・地理情報・お気に入り で過不足ないか）
2. キャンセル UX を維持する方針で確定するか（案 α/β/γ を排除するか）
3. F12.3 既存設計書を更新する範囲

### 13.2 法務レビューの実施有無・タイミング

**論点:** 案 ε 採用時、法務レビューを W-A 御裁可時 / W-F リリース前 のどちらで行うか（あるいは両方）？

**家老推奨:** 両方。最低でも W-A の前にスケッチ法務確認、W-F の前に正式レビュー。

### 13.3 8 リスナーの「弱/強」仕分け（W-B スコープだが大方針確定が必要）

**論点:** §3 表のドメインを「Day 0 即時実行」/「Day 30 まで猶予」のどちらに分類するか？
家老の暫定案:

| Day 0 即時実行（弱）| 理由 |
|---|---|
| notification | 退会後にプッシュ通知が飛ぶのは利用者の期待外。Art.7 同意撤回権 |
| schedule（Google Calendar）| OAuth トークン残存は最重大級のセキュリティ・GDPR リスク |
| weather | 地理情報は個人特定可能性ありと設計書明記 |
| favorite | 個人嗜好データ。キャンセルしても復元価値が低い |

| Day 30 まで猶予（強）| 理由 |
|---|---|
| auth（OAuth 連携・2FA） | キャンセルしたユーザーは元の OAuth・2FA で再ログインしたい |
| social（follows / profile） | フォロー関係は cancel 時に復元したい |
| village | コミュニティ所属はキャンセル後に戻したい |
| scopefolder（リスナー未配線）| 同上。配線するなら強側 |

マスター御裁可で覆る可能性あり。

### 13.4 cancel-withdrawal API のレスポンス改善

**論点:** 案 ε 採用時、cancel 後に「以下の機能は再設定が必要です: プッシュ通知 / Google Calendar / 天気地点 / お気に入り」のメッセージを返すべきか？

**家老推奨:** YES。レスポンスに `requiresReconfiguration: ["notification", "calendar", ...]` を含める。i18n も同時整備。

### 13.5 W-D を親 Phase B-1〜B-6 のどこに合流させるか

**論点:** 強匿名化リスナー（auth / social / village / scopefolder）を `AccountPurgedEvent` に統合するタイミング。

**家老推奨:** 親 Phase B の各 PR（B-1=role 以外）と歩調を合わせ、「該当ドメインの強匿名化リスナーを `AccountPurgedEvent` 購読化」を **その PR 内で同時に行う**。例: 親 B-2（team）相当を行うとき、村ドメインの `VillageUserCleanerEventListener` を `AccountPurgedEvent` 購読に切替える PR にする。

### 13.6 案 δ を選択する場合の損失承認

**論点:** 案 δ 採用時、過去 1 年弱の `withdrawUser` 実装労力（コミット `badfe701e` / `5d064d4d2`）と 7 リスナー設計を捨てる判断が必要。これは「サンクコスト」だが念のため記録。

### 13.7（家老検分追加 / 2026-05-18）— `FavoriteAnonymizationEventListener` トランザクション伝播の差異解消

**論点:** 他リスナーが `@Async("event-pool") + REQUIRES_NEW + AFTER_COMMIT` の三重防御なのに対し、`FavoriteAnonymizationEventListener` だけ `@EventListener + @Transactional`（同期・同一 TX 内）。
案 ε で Favorite を「弱匿名化（Day 0 実行）」に分類した場合、**`requestWithdrawal` の TX 内で同期実行され、Favorite 削除失敗時に退会受付自体がロールバックする** リスクがある。

**選択肢:**
- A. Favorite リスナーを他リスナーと同型に書き換え（`@Async + REQUIRES_NEW + AFTER_COMMIT`）→ Phase W-A の前提条件として実施
- B. 弱匿名化分類から Favorite を外し強匿名化（30 日後）に回す → ただし「お気に入りは即時消去すべき」という案 ε の思想に反する

**家老推奨:** A（伝播設計を揃える）。Phase W-A の最初の差分として実施。

### 13.8（家老検分追加 / 2026-05-18）— 案 ε の「キャンセル UX」が部分復帰になる事実

**論点:** 案 ε の「キャンセル UX 維持」主張は **氏名・メール・auth/social/village のみ復帰可能**。push 購読・Google Calendar 連携・天気地点・お気に入りは Day 0 で消えるため復帰不能。
これは「F12.3 で約束したキャンセル UX」の文言と厳密には乖離する。**マスターは「氏名/メール/OAuth ログイン情報のみ復帰可能・通知設定や連携は一からやり直し」という UX 制約を許容できるか判断必須**。

**選択肢:**
- A. この UX 制約を許容する（案 ε 推奨形）→ cancel-withdrawal レスポンスで `requiresReconfiguration` 配列を返し、再設定 UI を整備
- B. 案 δ に転換（即時匿名化を諦め、現状の遅延モデルを正とする）→ §13.6 サンクコスト承認とセット
- C. 弱匿名化対象を更に絞り込み「Google Calendar 外部 revoke のみ Day 0、push 購読等は Day 30 強匿名化」とする → 案 ε の純粋性は損なうが、キャンセル UX の死亡範囲を最小化

**家老推奨:** A。「退会キャンセルは『気が変わった』時の救済であり、再設定 UI の整備で十分」と整理。**ただしマスターの UX 哲学次第なので明示裁定必須**。

### 13.9（家老検分追加 / 2026-05-18）— 弱匿名化リスナー切替の不可逆性と「データ復元不能性」

**論点:** §9 表で W-C ロールバックを「`git revert` 5 分」としているが、**本番運用後の `WithdrawalRequestedEvent` 駆動で物理削除された `push_subscriptions` / `user_google_calendar_connections` を元に戻す手段はない**。「コードはロールバック可能だが消えたデータは戻らない」差し戻し戦略を明記すべき。

**選択肢:**
- A. ロールバック計画 §9 表に「データ復元不能・既退会者には新フローを継続適用」を明記し、ロールバック判断者の責任範囲を明確化
- B. W-C 着手前に「カナリアリリース：1% トラフィックで 7 日間運用 → 問題なければ全展開」のような段階的有効化を必須化
- C. 安全弁として、削除前に対象行を `withdrawal_anonymization_audit` テーブルに JSON snapshot で退避し、ロールバック時に再投入可能とする（高コスト・高価値）

**家老推奨:** A + B。C はオーバースペック。

### 13.10（家老検分追加 / 2026-05-18）— 退会バースト時の event-pool 枯渇 + Google Calendar 外部 API revoke 方針

**論点 A（event-pool）:** 1000 万ユーザー × 退会バースト（規約改定後の集団退会 1% = 10 万件）で案 ε 採用後は即時匿名化 4 リスナー × 10 万 = **40 万非同期タスクが即時 enqueue**。兄弟設計書 §10.14 で同論点が指摘されているのに本書は連携していない。
**論点 B（Google Calendar 外部 revoke）:** 現行 `IntegrationAnonymizationEventListener` は DB DELETE のみ（外部 API call なし）。GDPR Art.17「第三者からの消去」義務に抵触する可能性。案 ε で「Day 0 即時消去」を謳うなら **Google `oauth2/revoke` エンドポイント呼出を含めるか** 方針確定が必要。

**家老推奨:**
- A: 親設計書 §9.8 と統合し、退会経路専用の `withdrawal-pool`（または `purge-pool` 共用）を Phase W-A の前提インフラとして整備
- B: Phase W-C 着手時に Google `oauth2/revoke` 呼出を追加（失敗時は WARN + 監査ログ、リトライは 3 回 + バックオフ）

### 13.11（家老検分追加 / 2026-05-18）— `WithdrawalCancelledEvent` 発火コード欠落の並行調査

**論点:** `AuditLogEventListener#handleWithdrawalCancelled`（行 222-234）が `WithdrawalCancelledEvent` を購読しているが、**`UserService#cancelWithdrawal`（行 447-459）内に `eventPublisher.publish(new WithdrawalCancelledEvent(...))` の発火コードが見当たらない**（殿 verify 確定 2026-05-18）。
これは本軍議のスコープではないが、**`cancelWithdrawal` が監査ログを残せていない既存隠れバグ**。

**家老推奨:** 別途「早馬」案件として 1 PR で発火コードを追加（5 行差分）。本軍議の W-C と同時着手可能。

### 13.12（家老検分追加 / 2026-05-18）— CLAUDE.md 原則 4「退会時は匿名化」と案 ε の二段モデルの整合性宣言

**論点:** CLAUDE.md DB 設計原則 4 は「**退会したら個人情報のみ消去**」を要求している（Day 0 完全匿名化の含意）。案 ε は強匿名化（auth/social/village/scopefolder）を 30 日後まで猶予するため、形式上原則 4 の文面と部分的に矛盾する。

**選択肢:**
- A. CLAUDE.md 原則 4 を更新し「即時匿名化は段階実施可能、ただし 30 日以内に完全匿名化」と緩める → 本軍議 W-A の同時 PR で実施
- B. 案 ε を諦め案 α/β（全リスナー Day 0 同期/非同期実行）に転換 → キャンセル UX の死亡を許容
- C. 案 δ に転換 → サンクコスト承認

**家老推奨:** A。原則 4 の真意は「投稿・履歴を物理削除しない」が主であり、PII 消去のタイミングは GDPR 30 日タイムリミット内であれば許容と解釈。CLAUDE.md 改訂の文面案を W-A の同時 PR に含める。

---

## 関連ドキュメント

| パス | 内容 |
|---|---|
| [`docs/architecture/account_purge_cross_domain_refactor.md`](./account_purge_cross_domain_refactor.md) | 親設計書（main マージ済・§2.3 が本軍議の波及対象）|
| [`docs/architecture/account_purge_last_admin_succession.md`](./account_purge_last_admin_succession.md) | 兄弟設計書（PR #772 OPEN・§10.10 が本軍議起票元）|
| [`docs/features/F12.3_gdpr_personal_data.md`](../features/F12.3_gdpr_personal_data.md) | GDPR 設計書（二段モデルを正としている）|
| [`backend/src/main/java/com/mannschaft/app/auth/service/UserService.java`](../../backend/src/main/java/com/mannschaft/app/auth/service/UserService.java) | `requestWithdrawal` / `withdrawUser` / `cancelWithdrawal` の定義（行 408 / 478 / 446）|
| [`backend/src/main/java/com/mannschaft/app/auth/entity/UserEntity.java`](../../backend/src/main/java/com/mannschaft/app/auth/entity/UserEntity.java) | `anonymize()` / `softDelete()` / `requestDeletion()` / `cancelDeletion()` |
| [`backend/src/main/java/com/mannschaft/app/auth/event/UserAnonymizedEvent.java`](../../backend/src/main/java/com/mannschaft/app/auth/event/UserAnonymizedEvent.java) | 即時匿名化イベント（休眠中）|
| [`backend/src/main/java/com/mannschaft/app/auth/event/WithdrawalRequestedEvent.java`](../../backend/src/main/java/com/mannschaft/app/auth/event/WithdrawalRequestedEvent.java) | 退会受付イベント（実働中）|
| [`backend/src/main/java/com/mannschaft/app/gdpr/service/AccountPurgeService.java`](../../backend/src/main/java/com/mannschaft/app/gdpr/service/AccountPurgeService.java) | 30 日後物理削除バッチ |
| [`backend/src/main/java/com/mannschaft/app/gdpr/service/WithdrawalStripeHandler.java`](../../backend/src/main/java/com/mannschaft/app/gdpr/service/WithdrawalStripeHandler.java) | `WithdrawalRequestedEvent` 購読（Stripe 未実装 WARN）|
| 各 `*AnonymizationEventListener` / `WeatherLocationCleanupListener` / `VillageUserCleanerEventListener` | §3 表参照 |

---

## 変更履歴

| 日付 | 内容 | 担当 |
|---|---|---|
| 2026-05-18 | 初版作成（陣立て書）。兄弟設計書 §10.10 専用深堀。案 α〜ζ の 6 案比較・案 ε を推奨。親 §2.3 + PR #772 §10.10 への波及対応計画記載。マスター御裁可待ち事項 6 件提示 | 家老（Plan agent）|
| 2026-05-18 | 検分修正反映 #1: 軽微修正 — §3 章タイトル「9 ドメイン (7 配線済 + 1 未配線 + 1 別構造)」に統一、§1.2 / §2.4 / §2.5 結論ブロックの数字を「7 ドメイン配線済 + scopefolder 配線後 8」に揃え、§3 表 #8 scopefolder 行番号 451→457 訂正 | 殿（家老検分反映）|
| 2026-05-18 | 検分修正反映 #2: §13 補強 6 件追加 — 13.7 Favorite TX 伝播差異 / 13.8 案 ε のキャンセル UX 部分復帰 / 13.9 弱匿名化リスナー切替の不可逆性 / 13.10 event-pool 枯渇 + Google Calendar 外部 revoke / 13.11 WithdrawalCancelledEvent 発火欠落（殿 verify 確定）/ 13.12 CLAUDE.md 原則 4 と案 ε の整合性宣言 | 殿（家老検分反映 + 殿 verify）|
| 2026-05-18 | マスター「よきにはからえ」一括裁定反映: §13 全 12 項目を採用形に確定（案 ε / 法務必須 / 仕分け確定 / `requiresReconfiguration` / W-D 親 B 同時 / Favorite TX 伝播揃え / UX 部分復帰許容 / カナリア 1% × 7 日 / withdrawal-pool 分離 + Google revoke / WithdrawalCancelledEvent 早馬 / CLAUDE.md 原則 4 緩める）。残論点ゼロ・出陣可能状態 | 殿（マスター御裁可反映）|
| 2026-05-18 | Phase W-A 実装 PR `_TBD_` で実施: ①`AsyncConfig` に `withdrawal-pool` Bean 追加（§13.10 A）/ ②`FavoriteAnonymizationEventListener` の TX 伝播を他リスナーと同型に揃え（§13.7）/ ③CLAUDE.md 原則 4 に「PII 消去は GDPR 30 日内段階実施可」追記＋二段モデル明文化（§13.12）| 足軽（Phase W-A 第一陣）|
