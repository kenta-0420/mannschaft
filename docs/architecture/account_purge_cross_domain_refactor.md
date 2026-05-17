# AccountPurgeService 越境 DELETE 全廃リファクタ 陣立て書

> 起票日: 2026-05-18
> 担当: kenta（マスター御裁可待ち）
> ステータス: 🟡 設計段階（実コード変更なし）
> 帰属: `docs/architecture/db_scalability.md` 「次フェーズ」候補（退会経路のドメイン境界正規化）

---

## §1. 背景と問題定義

### 1.1 発端

F15.4 Phase 4（`teams.member_count` 事前集計, PR #718, 2026-05-17）の検分で **Caveat** が記録された:

- `AccountPurgeService#purgeUser(user)` は `team_org_memberships` を直接 DML 操作するが `MembershipChangedEvent` を発火しないため、退会経路で `teams.member_count` の同期更新が抜ける
- 夜次バッチ `TeamMemberCountBackfillBatchService`（02:00 JST）で翌朝までに補正されるため**現状は機能的に問題なし**
- ただし**そもそも `AccountPurgeService`（gdpr/auth ドメイン）が `team_org_memberships`（team ドメイン）に直接 DML を打っていること自体が CLAUDE.md ドメイン境界原則違反**である

### 1.2 真の問題

退会（30日後物理削除）バッチの現状実装が、`gdpr` ドメインの 1 つの `@Transactional` メソッドから**多数の他ドメインの Repository に直接 DML を打つ越境構造**になっている。
F15.4 Phase 4 で表面化した「member_count 同期漏れ」はその一症状にすぎず、根本は退会経路の越境そのもの。

CLAUDE.md 原則 1（クロスドメイン FK 禁止）・原則 5（@Transactional ドメイン内）・モジュラーモノリス指向の精神に反する。
1000万ユーザー耐久ロードマップ（`docs/architecture/db_scalability.md`）で将来の水平シャーディングを進めるとき、この越境が**シャード境界をまたぐ DML として詰む**。

### 1.3 「即時匿名化 → 30日後物理削除」二段階構造の現状

| フェーズ | 場所 | イベント | 越境 DML | 状態 |
|---|---|---|---|---|
| 即時匿名化（退会ボタン押下時） | `UserService#withdrawUser()` | ✅ `UserAnonymizedEvent` 発火（`UserService.java:497`） | なし（個人情報のみ自ドメイン更新） | **既に綺麗に分離されている** |
| 30日後物理削除（深夜バッチ） | `AccountPurgeService#purgeExpiredAccounts()` → `purgeUser()` | ❌ **どのイベントも発火しない** | **多数（後述）** | **本リファクタの対象** |

つまり、**即時匿名化フェーズ側は既にイベント駆動で 9 ドメイン以上の `*AnonymizationEventListener` が動いている**にもかかわらず、**30日後物理削除フェーズだけが旧式の越境 DELETE のまま取り残されている**。本リファクタは「30日後フェーズも同じ流儀に揃える」ことが本質。

---

## §2. 現状の越境マップ

### 2.1 `AccountPurgeService#purgeUser()` の越境 DML 一覧

`backend/src/main/java/com/mannschaft/app/gdpr/service/AccountPurgeService.java` の `purgeUser()` メソッド（1 つの `@Transactional`）内で呼び出している Repository を、所属ドメインで分類:

| # | Repository | 所属ドメイン | 操作 | 越境? | 備考 |
|---|---|---|---|---|---|
| 1 | `UserRepository` | auth | `save()`, `delete()` | 同ドメイン | gdpr → auth は責務 |
| 2 | `RefreshTokenRepository` | auth | `delete()` | gdpr → auth | トークン系 |
| 3 | `EmailVerificationTokenRepository` | auth | `deleteByUserIdIn()` | gdpr → auth | トークン系 |
| 4 | `PasswordResetTokenRepository` | auth | （未実装 WARN） | gdpr → auth | `user_id` なし設計 |
| 5 | `EmailChangeTokenRepository` | auth | （未実装 WARN） | gdpr → auth | メソッド欠落 |
| 6 | `MfaRecoveryTokenRepository` | auth | （未実装 WARN） | gdpr → auth | メソッド欠落 |
| 7 | `OAuthLinkTokenRepository` | auth | （未実装 WARN） | gdpr → auth | メソッド欠落 |
| 8 | `OAuthAccountRepository` | auth | `deleteAll(findByUserId)` | gdpr → auth | **既に `AuthAnonymizationEventListener` で削除済の二重実行** |
| 9 | `TwoFactorAuthRepository` | auth | `delete(findByUserId)` | gdpr → auth | **既に `AuthAnonymizationEventListener` で削除済の二重実行** |
| 10 | `WebAuthnCredentialRepository` | auth | `deleteAll(findByUserId)` | gdpr → auth | 同ドメイン |
| 11 | `ChartRecordRepository` | chart | `anonymizeCustomerUserId()` | **🔴 越境** | chart ドメイン |
| 12 | `ErrorReportOccurrenceRepository` | errorreport | `anonymizeByUserId()` | **🔴 越境** | errorreport ドメイン |
| 13 | `UserRoleRepository` | role | `nullifyGrantedBy()`, `deleteAllByUserId()` | **🔴 越境** | role ドメイン |
| 14 | `TeamOrgMembershipRepository` | team | `nullifyInvitedBy()`, `nullifyRespondedBy()` | **🔴 越境** | F15.4 Caveat の発火点 |
| 15 | `MemberPaymentRepository` | payment | `anonymizeUserId()` | **🔴 越境** | payment ドメイン |
| 16 | `StripeCustomerRepository` | payment | `delete(findByUserId)` | **🔴 越境** | payment ドメイン |
| 17 | `DataExportRepository` | gdpr | `findByExpiresAtBeforeAndS3KeyIsNotNull()`, `delete()` | 同ドメイン | gdpr 自ドメイン |
| 18 | `StorageService` | common | `delete()` | infra | R2 ストレージ |
| 19 | `ProxyInputRecordRepository` | proxy | `deleteAllBySubjectUserId()` | **🔴 越境** | F14.1 Phase 13-γ |
| 20 | `ProxyInputConsentRepository` | proxy | `logicalDeleteAllBySubjectUserId()` | **🔴 越境** | proxy ドメイン |
| 21 | `AuditLogService` | auth | `record()` | gdpr → auth | 監査ログ |

### 2.2 越境統計

| 区分 | 件数 |
|---|---|
| 全 Repository / Service 呼び出し | 21 |
| 同ドメイン内（gdpr 自身） | 2 |
| auth ドメインへの越境（gdpr → auth） | 11 |
| その他ドメインへの越境（gdpr → chart/errorreport/role/team/payment/proxy） | **7（chart 1, errorreport 1, role 1, team 1, payment 2, proxy 2）** |
| **`@Transactional` 1 個でまたいでいるドメイン数** | **gdpr / auth / chart / errorreport / role / team / payment / proxy = 8 ドメイン** |

### 2.3 二重実行リスクの既存箇所

`UserAnonymizedEvent`（即時匿名化フェーズで発火済）を購読する既存リスナーが、**30日後の `AccountPurgeService` でも同じテーブルに DELETE を打っている**：

| テーブル | 即時時に削除済 | 30日後にも削除 | リスク |
|---|---|---|---|
| `oauth_accounts` | ✅ `AuthAnonymizationEventListener` | ✅ `AccountPurgeService` (#8) | 二重 DELETE（冪等で実害なし、ただし無駄） |
| `two_factor_auth` | ✅ `AuthAnonymizationEventListener` | ✅ `AccountPurgeService` (#9) | 同上 |
| `push_subscriptions` 等 | ✅ `NotificationAnonymizationEventListener` | （`AccountPurgeService` 未削除） | 一貫性欠落 |
| `user_favorites` | ✅ `FavoriteAnonymizationEventListener` | （`AccountPurgeService` 未削除） | 一貫性欠落 |
| `follows`, `user_social_profiles` | ✅ `SocialAnonymizationEventListener` | （`AccountPurgeService` 未削除） | 一貫性欠落 |
| `user_google_calendar_connections` | ✅ `IntegrationAnonymizationEventListener`（schedule） | （`AccountPurgeService` 未削除） | 一貫性欠落 |
| `user_village_*` | ✅ `VillageUserCleanerEventListener` | （`AccountPurgeService` 未削除） | 一貫性欠落 |

**結論:** 現状は「即時匿名化リスナー」と「30日後 `AccountPurgeService`」が**互いを知らないまま並走している**。本リファクタはこの 2 系統を統合する。

### 2.4 既存テスト網羅性

`AccountPurgeServiceTest`（`backend/src/test/java/com/mannschaft/app/gdpr/AccountPurgeServiceTest.java`, 281 行）:

- ✅ 「対象なし」「ユーザー物理削除」「WITHDRAWAL_COMPLETED 監査ログ」「chart_records 匿名化」「member_payments センチネル」「error_report_occurrences 匿名化」「1件失敗で他継続」の 7 ケース
- ❌ 越境 Repository は全て Mock で、リスナー連動の確認なし
- ❌ チャネル別（team / payment / proxy / chart）の DELETE 漏れを検出する仕組みなし
- ❌ GDPR 法的タイムリミット（30日以内に全 clean 完了）の保証テストなし

---

## §3. 目標アーキテクチャ

### 3.1 採用方針: 案 C（`AccountPurgedEvent` + 各ドメイン listener）

```java
// gdpr ドメイン
@Service
public class AccountPurgeService {

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "accountPurgeBatch", lockAtMostFor = "PT30M")
    public void purgeExpiredAccounts() {
        // 既存と同じバッチ駆動 + 1ユーザーずつ purgeUser を REQUIRES_NEW で呼ぶ
    }

    @Transactional
    void purgeUser(UserEntity user) {
        Long userId = user.getId();
        String emailHash = SessionHashUtil.hash(user.getEmail());

        // ① 自ドメイン（gdpr）の片付け
        cleanupDataExports(userId);

        // ② 自ドメイン経由で許可される auth ドメイン操作（user 本体 + 監査ログ）
        user.setPurgedAt(LocalDateTime.now());
        userRepository.save(user);
        auditLogService.record(AuditEventType.WITHDRAWAL_COMPLETED.name(), ...);
        userRepository.delete(user);

        // ③ 全クロスドメイン処理は AccountPurgedEvent に委譲
        eventPublisher.publish(new AccountPurgedEvent(userId, emailHash));
    }
}
```

```java
// 各ドメイン側に *PurgeEventListener を新設
@Component
class TeamPurgeEventListener {
    @Async("event-pool")
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(propagation = REQUIRES_NEW)
    void on(AccountPurgedEvent ev) {
        teamOrgMembershipRepository.nullifyInvitedBy(ev.userId());
        teamOrgMembershipRepository.nullifyRespondedBy(ev.userId());
        // memberships は MembershipChangedEvent 経由で REMOVED を発火させる経路を別途検討
    }
}
```

### 3.2 `AccountPurgedEvent` の payload 設計

```java
package com.mannschaft.app.gdpr.event;

import com.mannschaft.app.common.event.BaseEvent;

/**
 * GDPR 30日経過後の物理削除完了イベント。
 * AccountPurgeService が user_repository.delete() の直後（同一トランザクション内）に発行する。
 * AFTER_COMMIT で各ドメインの *PurgeEventListener が購読し、自ドメインの関連データを片付ける。
 *
 * <p>UserAnonymizedEvent（退会即時）とは別物。
 * - UserAnonymizedEvent: 個人情報の匿名化完了時に発行（user 本体は残存）
 * - AccountPurgedEvent: user 本体物理削除時に発行（30日後）</p>
 */
public final class AccountPurgedEvent extends BaseEvent {
    private final Long userId;
    /** 削除時点の email を SHA-256 でハッシュ化した値。監査ログ用途のみ。 */
    private final String emailHash;

    public AccountPurgedEvent(Long userId, String emailHash) {
        super();
        this.userId = userId;
        this.emailHash = emailHash;
    }
    public Long userId() { return userId; }
    public String emailHash() { return emailHash; }
}
```

**設計判断:**
- `UserAnonymizedEvent`（即時匿名化）と**完全に分ける**。理由: 30日後フェーズは「user 本体物理削除」「監査ログ確定」「外部システム連携の完全クローズ」など、即時時とは責務が異なる
- `emailHash` のみ持つ（生 email は持たない）。GDPR 削除権の徹底
- `originalEmail` は持たない（即時時点で既に匿名化されている）

### 3.3 既存パターンとの整合

| 先行事例 | 流用ポイント |
|---|---|
| `AuthAnonymizationEventListener` | `@Async("event-pool")` + `REQUIRES_NEW` + `AFTER_COMMIT` + `try-catch WARN` の金型を踏襲 |
| `FavoriteAnonymizationEventListener` | 単純削除のみのリスナーの最小実装例 |
| `VillageUserCleanerEventListener` | `cleanupX()` / `anonymizeX()` でメソッド分割する設計（テスト容易性） |
| `TeamMemberCountListener` (F15.4 Phase 4) | `try-catch WARN` で失敗は夜次バッチ補正に任せる思想を踏襲 |
| `CirculationDocumentDeletedEvent` + `DisclosureCirculationCleanupHandler` (F09.14 Phase 4) | 「FK 撤去 → イベント駆動整合性」の同形パターン |
| `MembershipChangedEvent` + `TeamMemberCountListener` | **本リファクタで `team` ドメインの listener から発火させる経路で再利用** |

### 3.4 失敗時のリトライ戦略（三重防御）

```
   ① AccountPurgedEvent 発火（gdpr / 同期コミット）
        ↓ AFTER_COMMIT
   ② 各ドメイン *PurgeEventListener（@Async / REQUIRES_NEW）
        ├─ 成功 → 完了
        └─ 失敗 → ③④
   ③ @Retryable（Spring Retry）で 3 回まで自動再試行（指数バックオフ）
        ├─ 成功 → 完了
        └─ 失敗 → ④
   ④ 夜次補正バッチ（既存の TeamMemberCountBackfillBatchService と同形）
        ├─ 各ドメインに「孤児 user_id 検出 → 削除」する補正バッチを 1 本ずつ用意
        └─ 毎日 03:00 JST に走らせ、リスナー失敗の取りこぼしを確実に拾う

   さらに監査ログ:
   ⑤ AccountPurgedEvent 発火 → 30日以内に各ドメインの clean が完了したかを
      日次監査バッチが GDPR 監査ログとして記録する
```

**outbox パターンの採否:**
- Phase B/C では `@Retryable` + 夜次補正で十分（既存パターンと同等）
- outbox 本格導入は、Phase D（または別軍議）で「複数インスタンス起動 + Kafka/SQS 投入」が必要になった段階で検討。本リファクタの第一目標ではない

---

## §4. 段階リリース計画（Phase A〜D）

### Phase A: 基盤導入（実装はせず本陣立て書の御裁可のみ）

**本 PR の範囲。以下を docs として確定:**
- §2 越境マップ
- §3 目標アーキテクチャ
- §4〜§9 計画・テスト・GDPR・未解決事項

**成果物:** 本設計書（`docs/architecture/account_purge_cross_domain_refactor.md`）  
**PR 数:** 1（本 PR）

### Phase B: イベント基盤導入 + リスナー併走

**1. `AccountPurgedEvent` クラス追加（gdpr ドメイン）**
- `backend/src/main/java/com/mannschaft/app/gdpr/event/AccountPurgedEvent.java`

**2. `AccountPurgeService#purgeUser()` の末尾に `eventPublisher.publish(new AccountPurgedEvent(...))` を追加**
- **既存の越境 DELETE は当面残す**（二重実行になるが冪等なので機能影響なし）

**3. 各ドメインに `*PurgeEventListener` を新設（1 ドメイン = 1 PR）**

| PR | ドメイン | 新設リスナー | 取り込む既存 DML |
|---|---|---|---|
| B-1 | team | `TeamPurgeEventListener` | `nullifyInvitedBy` / `nullifyRespondedBy` + 将来の `MembershipChangedEvent(REMOVED)` 発火 |
| B-2 | payment | `PaymentPurgeEventListener` | `member_payments.anonymizeUserId` / `stripe_customers.delete` |
| B-3 | role | `RolePurgeEventListener` | `user_roles.nullifyGrantedBy` / `deleteAllByUserId` |
| B-4 | chart | `ChartPurgeEventListener` | `chart_records.anonymizeCustomerUserId` |
| B-5 | proxy | `ProxyPurgeEventListener` | `proxy_input_records.deleteAllBySubjectUserId` / `proxy_input_consents.logicalDeleteAllBySubjectUserId` |
| B-6 | errorreport | `ErrorReportPurgeEventListener` | `error_report_occurrences.anonymizeByUserId` |

**4. 統合テスト**
- `AccountPurgedEvent` 発火 → 各ドメインの行が消えていることを `@SpringBootTest` で確認
- 1 PR ＝ 1 ドメインリスナー + 統合テスト 2〜3 件

**PR 数:** 6（B-1 〜 B-6）

### Phase C: 越境 DELETE 撤去

各ドメインのリスナーが安定稼働していることを Phase B のテスト + ステージング数日運用で確認した後、`AccountPurgeService#purgeUser()` から該当ドメインへの直接 DML を 1 ドメインずつ削除。

| PR | 撤去内容 |
|---|---|
| C-1 | team Repository 注入を削除し、関連呼び出しを撤去 |
| C-2 | payment Repository 注入を削除し、関連呼び出しを撤去 |
| C-3 | role Repository 注入を削除し、関連呼び出しを撤去 |
| C-4 | chart Repository 注入を削除し、関連呼び出しを撤去 |
| C-5 | proxy Repository 注入を削除し、関連呼び出しを撤去 |
| C-6 | errorreport Repository 注入を削除し、関連呼び出しを撤去 |
| C-7 | auth トークン系（`oauth_accounts` / `two_factor_auth` 二重実行解消）— 即時匿名化リスナーで既に削除されているので 30日後の重複削除を撤去 |

**完了条件:** `AccountPurgeService` のフィールドが `UserRepository` / `DataExportRepository` / `StorageService` / `AuditLogService` / `RefreshTokenRepository` / `EmailVerificationTokenRepository` / `WebAuthnCredentialRepository` / `EventPublisher` のみになる（auth ドメイン自体への直接アクセスは「gdpr が auth の親バッチを担う」位置付けで残す）。

**PR 数:** 7（C-1 〜 C-7）

### Phase D: 監査・保険レイヤ強化

**1. 夜次補正バッチ整備（1 ドメイン = 1 PR）**

各ドメインに「孤児 user_id 検出 → 削除」バッチを設置（既存 `TeamMemberCountBackfillBatchService` と同形）:
```java
@Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
@SchedulerLock(name = "teamPurgeBackfillBatch", lockAtMostFor = "PT10M")
public void backfill() {
    // users から物理削除済の user_id を取得し、team 配下のテーブルで孤児を削除
}
```

**2. `@Retryable` 導入**
- 各 `*PurgeEventListener` に `@Retryable(maxAttempts=3, backoff=...)` を付与

**3. GDPR 監査ログ強化**
- `AccountPurgedEvent` 発火後 30日以内に全ドメインの clean が完了したことを記録するバッチ（`GdprPurgeAuditBatchService`）
- 完了未達のユーザー ID をアラート通知

**4. 法務レビュー**
- 全 Phase 完了後にマスター主導で実施

**PR 数:** 7〜9（補正バッチ 6 + Retryable 1 + 監査バッチ 1〜2）

### 全体 PR 数概算

| Phase | PR 数 | 累計 |
|---|---|---|
| A | 1 | 1 |
| B | 6 | 7 |
| C | 7 | 14 |
| D | 7〜9 | **21〜23** |

---

## §5. GDPR / 法的観点での留意点

### 5.1 削除権（GDPR Art. 17）タイムリミット

- 現状: 退会から 30日以内に物理削除を完了する設計（`RETENTION_DAYS=30`）
- 本リファクタ: 同期 DELETE → 非同期 listener への移行で「listener 失敗で部分残存」のリスクが新規発生
- 対策: 三重防御（listener / @Retryable / 夜次補正）+ 監査バッチで「完了未達ユーザー」を検出し、運用アラート化

### 5.2 部分失敗時のフォールバック

- 「user 本体は消えたが team 配下が残った」状態が放置されないよう、夜次補正バッチを**ドメインごとに 1 本ずつ**用意
- バッチが「孤児 user_id（users テーブルに存在しない userId）」を毎日チェックして自動削除
- 補正バッチ失敗時は `Slack` / `PagerDuty` 等の運用通知（既存基盤を流用）

### 5.3 監査ログ要件

- `WITHDRAWAL_COMPLETED` 監査ログ: 既存どおり `AccountPurgeService` で記録
- 追加: `ACCOUNT_PURGE_COMPLETED_ALL_DOMAINS` 監査ログを新設し、Phase D の監査バッチが「全ドメイン clean 完了」を確認した時点で記録
- 監査ログから `userId` で逆引きできるが、`email` は SHA-256 ハッシュのみ保持（既存設計を踏襲）

### 5.4 法務レビューのタイミング

- Phase A〜C は技術的なリファクタのため法務レビュー不要（機能要件は不変）
- Phase D 完了時にマスター主導で法務レビュー実施（GDPR 30日要件・監査ログ要件・削除証跡の整合性確認）

---

## §6. 各ドメインへの影響度と着手順序

| # | ドメイン | 影響度 | 着手順序 | 理由 |
|---|---|---|---|---|
| 1 | team | **high** | B-1 | F15.4 Caveat の発火点。`MembershipChangedEvent(REMOVED)` 発火経路の検証が必要なため最初に着手し品質を固める |
| 2 | payment | **high** | B-2 | 金銭情報。GDPR + 決済法的要件で複雑。Stripe API 連携の確認が必要 |
| 3 | role | medium | B-3 | F02.2.1 ダッシュボードキャッシュ無効化への波及確認が必要 |
| 4 | proxy | medium | B-4 | F14.1 Phase 13-γ で導入された機能。論理削除と物理削除の使い分けに注意 |
| 5 | chart | low | B-5 | 単純な anonymize のみ |
| 6 | errorreport | low | B-6 | 単純な anonymize のみ。F12.5 Phase 2-F の挙動維持 |

**着手順序の根拠:**
- 高影響ドメイン（team / payment）を先に着手し、リスナー基盤の品質を早期に固める
- 低影響ドメイン（chart / errorreport）を最後に着手することで Phase B 後半は安定運用フェーズになる

---

## §7. ロールバック計画

### Phase B（リスナー追加）でのロールバック
- 追加した `*PurgeEventListener` を `@Profile("disabled")` 化、または `revert` で削除
- 既存の越境 DELETE はそのまま残っているので機能影響なし
- ロールバック所要時間: 1 PR で 5 分以内

### Phase C（越境 DELETE 撤去）でのロールバック
- 撤去した DML 行を `git revert` で復元
- ただし、リスナー側で既に削除が走った行は復活しないため、**夜次補正バッチで 1 日以内に再 clean** することで結局同じ状態に収束する
- ロールバック所要時間: 1 PR で 5 分 + 補正バッチ実行待ち

### Phase D（補正バッチ追加）でのロールバック
- バッチを `@Scheduled` を外して停止、または `@Profile("disabled")` 化
- リスナー側で大半は清掃済のため、補正バッチ停止のみでは機能影響軽微
- ロールバック所要時間: 1 PR で 5 分以内

### 緊急時の最終手段
- `AccountPurgeService` の `@Scheduled` を一時停止し、退会済ユーザーの物理削除を停止
- GDPR 30日要件への影響は法務報告レベル（マスター判断事項）

---

## §8. テスト計画

### 8.1 ユニットテスト

| 対象 | テストケース |
|---|---|
| `AccountPurgedEvent` | コンストラクタ・getter / `userId == null` で IAE |
| 各 `*PurgeEventListener` | 正常系（削除実行）/ Repository 例外時の WARN ログ・例外スロー無し |
| `AccountPurgeService` (Phase C 後) | 越境呼び出しが消えていることを Mock の `verifyNoInteractions` で保証 |

### 8.2 統合テスト（`@SpringBootTest` + Testcontainers MySQL）

| シナリオ | 検証内容 |
|---|---|
| 正常系 | `AccountPurgedEvent` 発火 → 各ドメインの行が消えていることを DB クエリで確認 |
| 部分失敗 | 1 リスナーが例外を投げても他のリスナーが完了することを確認（既存パターンの再現） |
| 二重実行冪等性 | 同じ `AccountPurgedEvent` を 2 回発火しても DB 状態が変わらないことを確認 |
| 夜次補正バッチ | 孤児 user_id を意図的に作成 → バッチ実行 → 削除されることを確認 |

### 8.3 E2E テスト（Playwright）

- 退会フローを通して 30日相当経過後 (`@Scheduled` を手動トリガ) に全ドメインの行が消えていることを確認
- E2E は Phase D 完了時に 1 本追加

### 8.4 GDPR シナリオテスト

- 「30日以内に全ドメイン clean が完了する」ことを `GdprPurgeAuditBatchService` の戻り値で保証
- 完了未達ケースを意図的に発生させて運用通知が発火することを確認

### 8.5 既存テストへの影響

- `AccountPurgeServiceTest`（281 行・7 ケース）: Phase C で `verify(...Repository).deleteByUserId(...)` を `verifyNoInteractions(...)` に書き換える必要あり
- 既存 `*AnonymizationEventListenerTest` 群: 変更不要（即時匿名化フェーズは無傷）

---

## §9. 未解決事項（マスター御裁可待ち）

### 9.1 `AccountPurgedEvent` の payload に追加メタデータを含めるか

**論点:** 現案は `userId` + `emailHash` のみ。各ドメインで「いつ匿名化されたか」を知りたい場合 `anonymizedAt` も含めるか？

**選択肢:**
- A. `userId` + `emailHash` のみ（現案・最小）
- B. `userId` + `emailHash` + `anonymizedAt` + `purgedAt`（監査用途に手厚い）

**推奨:** A（YAGNI）。必要になった時点で `version 2` を導入。

### 9.2 `team_org_memberships` の DELETE を Phase B でどう扱うか

**論点:** `team_org_memberships` の行 DELETE は本来 `MembershipChangedEvent(REMOVED)` 発火経路（`RoleService#removeMember`）で行うべき。だが 30日後物理削除フェーズで `RoleService` を直接呼ぶと「gdpr → role の呼び出し」になり、また越境が発生する。

**選択肢:**
- A. `TeamPurgeEventListener` が `RoleService#removeMember()` を呼ぶ（gdpr → role になるが Service 層越境は許容と判断）
- B. `RolePurgeEventListener` が `removeMember` 相当の処理を行い、`MembershipChangedEvent(REMOVED)` を発火させる
- C. `team_org_memberships` の DELETE 自体は別途 F15.4 Phase 5 として切り出し、本リファクタでは扱わない

**推奨:** B。`role` ドメインが自己の `MembershipChangedEvent(REMOVED)` を発火させる構図が CLAUDE.md 原則 5 に最も忠実。

### 9.3 `member_payments` のセンチネル差替えと GDPR の整合

**論点:** 現状 `member_payments.user_id` を `SENTINEL_USER_ID=0` に差替える設計。会計税法的に支払い履歴は 7 年保持が望ましい一方、GDPR 削除権との整合性をどう取るか？

**選択肢:**
- A. 現状維持（センチネル差替）— 個人特定不能化 + 支払い履歴保持
- B. 全文物理削除 — GDPR 完全準拠だが会計法的にリスク
- C. `member_payments` の `user_id` を NULL 化 + 別テーブル `legal_payment_audit` に最小限の集計のみ保持

**推奨:** A（現状維持）。法務レビューで再検討。

### 9.4 Phase B のリスナー失敗時、Phase C 移行判断基準

**論点:** リスナー失敗率がどの程度なら Phase C（越境 DELETE 撤去）に進めるか？

**推奨基準（暫定）:**
- ステージング 7 日連続実行で失敗率 < 0.1%
- 本番デプロイ後 30 日（= GDPR タイムリミット 1 サイクル）で失敗率 < 0.01%
- 上記達成後にマスター御裁可で C-1 から順次撤去

### 9.5 outbox パターン導入の判断

**論点:** §3.4 では outbox を Phase D 範囲外としたが、複数インスタンス起動 + AFTER_COMMIT イベントの取りこぼし保証を強化すべきか？

**推奨:** Phase D 完了後の「運用知見蓄積期間（3〜6ヶ月）」を経て、outbox 必要性を判断する別軍議を起こす。本リファクタの第一目標は越境構造の解消であり、信頼性向上は次フェーズ。

---

## 関連ドキュメント

| パス | 内容 |
|---|---|
| `docs/architecture/db_scalability.md` | 1000万ユーザー耐久 DB 再構築 Phase 0〜4（本リファクタの帰属親） |
| `CLAUDE.md` §アーキテクチャ思想 | ドメイン境界・DB 設計原則 1〜7 |
| `backend/src/main/java/com/mannschaft/app/gdpr/service/AccountPurgeService.java` | リファクタ対象本体（223 行） |
| `backend/src/main/java/com/mannschaft/app/auth/event/UserAnonymizedEvent.java` | 即時匿名化イベント（流用しない・別物として並走） |
| `backend/src/main/java/com/mannschaft/app/auth/event/AuthAnonymizationEventListener.java` | リスナー実装の金型 |
| `backend/src/main/java/com/mannschaft/app/team/listener/TeamMemberCountListener.java` | F15.4 Phase 4 同形パターン |
| `backend/src/main/java/com/mannschaft/app/team/batch/TeamMemberCountBackfillBatchService.java` | 夜次補正バッチの金型 |
| `backend/src/main/java/com/mannschaft/app/circulation/event/CirculationDocumentDeletedEvent.java` | F09.14 Phase 4 同形パターン |

---

## 変更履歴

| 日付 | 内容 | 担当 |
|---|---|---|
| 2026-05-18 | 初版作成（陣立て書） | 家老（Plan agent） |
