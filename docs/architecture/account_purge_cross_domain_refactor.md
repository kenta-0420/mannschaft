# AccountPurgeService 越境 DELETE 全廃リファクタ 陣立て書

> 起票日: 2026-05-18
> 担当: kenta
> ステータス: 🟡 Phase C 完了 / Phase D 未着手
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
| 10 | `WebAuthnCredentialRepository` | auth | `deleteAll(findByUserId)` | gdpr → auth | `AuthAnonymizationEventListener` には削除なし。auth ドメイン側で残す |
| 11 | `ChartRecordRepository` | chart | `anonymizeCustomerUserId()` | **🔴 越境** | chart ドメイン |
| 12 | `ErrorReportOccurrenceRepository` | errorreport | `anonymizeByUserId()` | **🔴 越境** | errorreport ドメイン |
| 13 | `UserRoleRepository` | role | `nullifyGrantedBy()`, `deleteAllByUserId()` | **🔴 越境** | role ドメイン。**F15.4 Caveat の真の発火点（§3.5）** |
| 14 | `TeamOrgMembershipRepository` | team | `nullifyInvitedBy()`, `nullifyRespondedBy()` | **🔴 越境** | team ドメイン（NULL 化のみ・DELETE なし） |
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
| その他ドメインへの越境（gdpr → chart/errorreport/role/team/payment/proxy） | **8（chart 1, errorreport 1, role 1, team 1, payment 2, proxy 2）** |
| **`@Transactional` 1 個でまたいでいるドメイン数** | **gdpr / auth / chart / errorreport / role / team / payment / proxy = 8 ドメイン** |

### 2.3 二重実行リスクの既存箇所

> ⚠️ **検分修正 2026-05-18**: 当初「二重実行リスク」としていた前提は、即時匿名化リスナーが全休眠中のため実態としては「即時実行が無く 30 日後のみが動く」状態であることが PR #793 で確定。本節は現状の正確な記述に修正済。
>
> 具体的には `UserService#withdrawUser()` の呼出元がリポジトリ内に一切存在せず、`UserAnonymizedEvent` の発火実績がゼロ。下表で「即時時に削除済」としていた 9 ドメインの `*AnonymizationEventListener` 群（`AuthAnonymizationEventListener` / `NotificationAnonymizationEventListener` / `FavoriteAnonymizationEventListener` / `SocialAnonymizationEventListener` / `IntegrationAnonymizationEventListener` / `VillageUserCleanerEventListener` 等）は**いずれも休眠状態**であり、現状の clean 経路は 30 日後 `AccountPurgeService` の越境 DML のみが事実上唯一である。

`UserAnonymizedEvent`（即時匿名化フェーズ）と `AccountPurgeService`（30 日後物理削除フェーズ）の実態整理：

| テーブル | 即時時に削除済 | 30日後にも削除 | 実態リスク |
|---|---|---|---|
| `oauth_accounts` | ❌ `AuthAnonymizationEventListener`（リスナー休眠中・PR #793 で根治治療着手） | ✅ `AccountPurgeService` (#8) | 即時実行が無く 30 日後のみが clean。Phase B 完了まで 30 日間 PII 残存 |
| `two_factor_auth` | ❌ `AuthAnonymizationEventListener`（同上） | ✅ `AccountPurgeService` (#9) | 同上 |
| `push_subscriptions` 等 | ❌ `NotificationAnonymizationEventListener`（同上） | （`AccountPurgeService` 未削除） | **完全に未 clean**（リスナー休眠 × バッチ未実装） |
| `user_favorites` | ❌ `FavoriteAnonymizationEventListener`（同上） | （`AccountPurgeService` 未削除） | 同上 |
| `follows`, `user_social_profiles` | ❌ `SocialAnonymizationEventListener`（同上） | （`AccountPurgeService` 未削除） | 同上 |
| `user_google_calendar_connections` | ❌ `IntegrationAnonymizationEventListener`（schedule・同上） | （`AccountPurgeService` 未削除） | 同上 |
| `user_village_*` | ❌ `VillageUserCleanerEventListener`（同上） | （`AccountPurgeService` 未削除） | 同上 |

**結論:** 現状は **即時匿名化フェーズ自体が実行されていない**（PR #793 §1 / `withdrawal_flow_immediate_anonymization_fix.md` で確定）。30 日後 `AccountPurgeService` が唯一の clean 経路となっているが、Phase B 完了までは 30 日間 PII が残存する状態。**本リファクタは両系統の統合ではなく、休眠リスナーの 30 日後フェーズ統合 + 即時匿名化の再有効化（W-A〜W-F）の両輪で進める。**

### 2.4 既存テスト網羅性

`AccountPurgeServiceTest`（`backend/src/test/java/com/mannschaft/app/gdpr/AccountPurgeServiceTest.java`, 281 行）:

- ✅ 「対象なし」「ユーザー物理削除」「WITHDRAWAL_COMPLETED 監査ログ」「chart_records 匿名化」「member_payments センチネル」「error_report_occurrences 匿名化」「1件失敗で他継続」の 7 ケース
- ❌ 越境 Repository は全て Mock で、リスナー連動の確認なし
- ❌ チャネル別（team / payment / proxy / chart）の DELETE 漏れを検出する仕組みなし
- ❌ GDPR 法的タイムリミット（30日以内に全 clean 完了）の保証テストなし

---

## §3. 目標アーキテクチャ

### 3.1 採用方針: `AccountPurgedEvent` + 各ドメイン listener

**選定理由（他案との比較）:**

| 案 | 説明 | 不採用理由 |
|---|---|---|
| 案 A | `AccountPurgeService` に各ドメインの `*PurgeService.purgeByUserId(userId)` メソッドを呼び出すパブリック API を追加 | 結局 `AccountPurgeService` が他ドメインの Service を直接呼ぶ越境構造が残り、`@Transactional` も依然として横断する。マイクロサービス分割境界の改善にならない |
| 案 B | `UserAnonymizedEvent`（既存・即時匿名化用）を 30 日後にもう一度発火させ流用 | 「即時匿名化」と「30 日後物理削除」は責務が異なる（前者は氏名/メール等の個人情報マスク、後者は user 本体物理削除と完全クローズ）。既存リスナーの分岐ロジックが膨張し可読性が劣化 |
| **案 C（採用）** | 新規 `AccountPurgedEvent` を発行、各ドメインに `*PurgeEventListener` を新設 | ✅ 既存 `UserAnonymizedEvent` 系 9 リスナーと**完全に同じ流儀**で揃う／✅ `@Transactional` が gdpr ドメインに閉じる／✅ ドメイン分割境界が明確化／✅ Phase B 併走中も冪等で安全 |



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

### 3.5 F15.4 Phase 4 Caveat の自動解消（副次効果）

本リファクタが完成すると、F15.4 Phase 4（PR #718 / 2026-05-17）で記録された Caveat ——
「`AccountPurgeService#purgeUser()` が **`user_roles` を直接 DML 操作（`deleteAllByUserId`）** するため `MembershipChangedEvent` が発火せず、退会経路で `teams.member_count` が最大 24h ズレる」——
が **追加のコード変更なしに自動的に解消される**。

> **【家老 検分修正 / 2026-05-18】** 当初記載の「`team_org_memberships` を直接 DML」は事実誤認。
> 実コード `AccountPurgeService.java:164` は `userRoleRepository.deleteAllByUserId(userId)` を呼んでおり、これが **role ドメイン越境（§2.1 表 #13）** に相当する。
> `team_org_memberships` への操作（§2.1 表 #14・行 167-168）は `nullifyInvitedBy` / `nullifyRespondedBy` の NULL 化のみで DELETE ではない。
> `MembershipChangedEvent` の発火源は `RoleService` の 5 箇所 + `MembershipService` の 2 箇所であり、`team` ドメインからは発火しない。

**解消メカニズム:**

```
[Before（現状）]
  AccountPurgeService
    └─ userRoleRepository.deleteAllByUserId(userId)  ← 越境 DML（gdpr → role）
         └─ MembershipChangedEvent(REMOVED) 発火されず
              └─ teams.member_count ズレ → 翌朝 02:00 JST 夜次バッチ
                 （TeamMemberCountBackfillBatchService）で補正

[After（本リファクタ完成後）]
  AccountPurgeService
    └─ eventPublisher.publish(AccountPurgedEvent)
         └─ RolePurgeEventListener（role ドメイン側、@TransactionalEventListener AFTER_COMMIT）
              └─ userRoleRepository.findAllByUserId(userId) でループ
                   └─ 各 UserRoleEntity に対し
                      RoleService#removeMember(scopeId, scopeType, userId) を呼ぶ
                        └─ user_roles から DELETE（同ドメイン内 @Transactional）
                             └─ MembershipChangedEvent(REMOVED) 発火（RoleService.java:168）
                                  └─ 既存 TeamMemberCountListener が即時減算
                                       → teams.member_count は退会と同時に正しい値
```

**ポイント:**

| 項目 | 方針 |
|---|---|
| 真の越境点 | `user_roles.deleteAllByUserId`（§2.1 表 #13・role ドメイン）。`team_org_memberships` ではない |
| `MembershipChangedEvent(REMOVED)` 発火経路 | 既存 `RoleService#removeMember`（`RoleService.java:124-150`）を `RolePurgeEventListener` 内から呼び、自然な経路で発火させる（§9.2 推奨案 B と整合） |
| ループ粒度 | `findAllByUserId(userId)` で取得した各 `UserRoleEntity` の `(scopeId, scopeType)` を引数に `removeMember` を順次呼ぶ（1 ユーザーが複数組織/チームに所属するケースに対応） |
| 最後の ADMIN ガード | `RoleService#removeMember` の `checkLastAdmin` ガード（`RoleService.java:142`）が退会者を `BusinessException(ROLE_001)` で弾く。退会経路ではこの保護を**バイパス or 事前昇格**する設計が別途必要（**§9.6 未解決事項**として新設） |
| 既存 `TeamMemberCountListener` (F15.4 Phase 4) | **変更なし**。`MembershipChangedEvent` 購読側のロジックはそのまま再利用 |
| 既存 `TeamMemberCountBackfillBatchService`（夜次補正） | **撤去しない・保険として残す**。理由: ①リスナー失敗時の三重防御（§3.4 ④）/ ②本リファクタ外の経路で集計ズレが入った場合の最終防衛線 / ③ Phase B 併走期間中の冪等性検証用 |
| 検証手段 | Phase B-1（**role ドメイン**）の統合テストで「退会即時に `teams.member_count` が減る」アサーションを追加 |

**汎用性 — 同パターンが将来の集計カラムにも適用可能:**

`teams.member_count` で確立した「ドメイン横断イベント → 自ドメイン Service 経由 → ドメインイベント発火 → 既存リスナーが即時集計更新 + 夜次バッチが保険」の三段構えは、
将来同種の集計カラム導入時にそのまま流用できる:

| 想定 | 集計対象イベント | 集計カラム例 |
|---|---|---|
| チャット未読件数の即時化 | `ChatMessageDeletedEvent(by purge)` | `chat_channels.message_count` / `chat_channels.last_message_at` |
| シフト割当残数の即時化 | `ShiftAssignmentRemovedEvent(by purge)` | `shifts.assigned_count` |
| 組織アクティブユーザー数の即時化 | `MembershipChangedEvent(REMOVED, by purge)` | `organizations.active_member_count`（将来追加候補） |

これにより F15.4 Phase 4 の苦労が「個別の例外対応」ではなく「再利用可能な設計パターン」として結実する。

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

| PR | ドメイン | 新設リスナー | 取り込む既存 DML | 御裁可後 PR# |
|---|---|---|---|---|
| B-1 | **role** | `RolePurgeEventListener` | `user_roles.nullifyGrantedBy` + `findAllByUserId` ループで `RoleService#removeMember` 呼び出し（`MembershipChangedEvent(REMOVED)` を自然発火させ F15.4 Caveat を自動解消） | **PR #837 マージ済み** |
| B-2 | team | `TeamPurgeEventListener` | `team_org_memberships.nullifyInvitedBy` / `nullifyRespondedBy` | **PR #845 マージ済み** |
| B-3 | payment | `PaymentPurgeEventListener` | `member_payments.anonymizeUserId(SENTINEL)` / `stripe_customers.delete` | **PR #855 マージ済み** |
| B-4 | chart | `ChartPurgeEventListener` | `chart_records.anonymizeCustomerUserId` | **PR #850 マージ済み** |
| B-5 | proxy | `ProxyPurgeEventListener` | `proxy_input_records.deleteAllBySubjectUserId` / `proxy_input_consents.logicalDeleteAllBySubjectUserId` | **PR #851 マージ済み** |
| B-6 | errorreport | `ErrorReportPurgeEventListener` | `error_report_occurrences.anonymizeByUserId` | **PR #847 マージ済み** |

**B-1 を role に変更した理由（検分指摘 / 2026-05-18）:**
当初 B-1 を `team` ドメインに据えていたのは「F15.4 Caveat 発火点」を最優先に固めるため。しかし家老検分で、Caveat の真の発火源は `user_roles.deleteAllByUserId`（role ドメイン）であることが判明（§3.5 参照）。
したがって最高影響度・最も検証価値の高いドメインは **role**。`RolePurgeEventListener` を最初に確立することで、`MembershipChangedEvent(REMOVED)` 経由で `teams.member_count` 即時減算が動くことを Phase B-1 統合テストで保証できる。
`team` ドメイン（B-2）の `nullifyInvitedBy` / `nullifyRespondedBy` は NULL 化のみで影響が小さく、`role` の後で十分。

**B-1 の冪等性留意（Phase B 併走期間中）:**
`member_payments.anonymizeUserId(userId, SENTINEL=0)` はターゲット選択型のため、2 回目の呼び出しでは「既に user_id=SENTINEL になっている行」をターゲットにできない（once-only ターゲット選択）。
これは「2 回呼んでも有害ではない」が「冪等」ではない。Phase B 併走中は「listener が先に anonymize → 後で AccountPurgeService の越境 DML が改めて anonymizeUserId を呼ぶ」順序で、2 回目は 0 件処理になることを前提とする（B-3 の統合テストで検証）。

**4. 統合テスト**
- `AccountPurgedEvent` 発火 → 各ドメインの行が消えていることを `@SpringBootTest` で確認
- 1 PR ＝ 1 ドメインリスナー + 統合テスト 2〜3 件

**PR 数:** 6（B-1 〜 B-6）

### Phase C: 越境 DELETE 撤去 ✅ 完了

各ドメインのリスナーが安定稼働していることを Phase B のテスト + ステージング数日運用で確認した後、`AccountPurgeService#purgeUser()` から該当ドメインへの直接 DML を一括削除。

**Phase C 実施内容（1 PR に一括まとめ）:**

| 撤去対象 | 内容 |
|---|---|
| `ChartRecordRepository` | import・フィールド・`anonymizeCustomerUserId()` 呼び出し・`log.debug` を削除 |
| `UserRoleRepository` | import・フィールド・`nullifyGrantedBy()` / `deleteAllByUserId()` 呼び出しを削除 |
| `TeamOrgMembershipRepository` | import・フィールド・`nullifyInvitedBy()` / `nullifyRespondedBy()` 呼び出しを削除 |
| `MemberPaymentRepository` | import・フィールド・`anonymizeUserId()` 呼び出し・`log.debug` を削除 |
| `StripeCustomerRepository` | import・フィールド・`findByUserId().ifPresent(delete)` 呼び出しを削除 |
| `ProxyInputRecordRepository` | import・フィールド・`deleteAllBySubjectUserId()` 呼び出しを削除 |
| `ProxyInputConsentRepository` | import・フィールド・`logicalDeleteAllBySubjectUserId()` 呼び出しを削除 |
| `ErrorReportOccurrenceRepository` | import・フィールド・`anonymizeByUserId()` 呼び出し・`log.debug` を削除 |
| `UserConstants` import | `SENTINEL_USER_ID` の参照が無くなったため削除 |

**達成後の状態:** `AccountPurgeService` のフィールドが下記のみになった:
- `UserRepository`（gdpr → auth、user 本体操作）
- `DataExportRepository`（gdpr 自ドメイン）
- `StorageService`（common infra、R2 削除）
- `RefreshTokenRepository` / `EmailVerificationTokenRepository` / `OAuthAccountRepository` / `TwoFactorAuthRepository` / `WebAuthnCredentialRepository` 等（auth トークン系）
- `AuditLogService`（監査ログ）
- `ApplicationEventPublisher`（AccountPurgedEvent 発火）

**PR #858**（マージ後に「マージ済み」に更新）

**`AccountPurgeServiceTest` 更新内容:**
- 削除した越境 DML の mock 設定（`given(...).willReturn(...)`）・`verify` を除去
- 越境 Repository（`ChartRecordRepository` 等 8 つ）の `@Mock` フィールドを削除
- テストヘルパー `stubAuthAndGdprMocks()` を抽出して各テストを簡潔化
- Phase C 後の gdpr 自ドメイン操作（data_exports S3 削除）継続確認テストを追加

**PR 数:** 1（Phase C 一括撤去 PR）

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
| 1 | **role** | **high** | **B-1** | **F15.4 Caveat の真の発火点（`user_roles.deleteAllByUserId`）。`MembershipChangedEvent(REMOVED)` 発火経路の検証が必要なため最初に着手し品質を固める** |
| 2 | team | medium | B-2 | `team_org_memberships` は NULL 化のみで影響軽微。role B-1 完了後の安全な統合先 |
| 3 | payment | **high** | B-3 | 金銭情報。GDPR + 決済法的要件で複雑。Stripe API 連携の確認が必要。冪等性留意（§4 B-3 ノート） |
| 4 | chart | low | B-4 | 単純な anonymize のみ |
| 5 | proxy | medium | B-5 | F14.1 Phase 13-γ で導入された機能。論理削除と物理削除の使い分けに注意 |
| 6 | errorreport | low | B-6 | 単純な anonymize のみ。F12.5 Phase 2-F の挙動維持 |

**着手順序の根拠（家老検分 / 2026-05-18 反映後）:**
- **role を最優先に固める** — F15.4 Caveat の真の発火点であり、`MembershipChangedEvent(REMOVED)` 発火経路を最初に確立することで「退会即時に `teams.member_count` が減る」ことを Phase B-1 統合テストで保証できる
- 高影響ドメイン（payment）を中盤に置き、リスナー基盤の品質が固まった段階で複雑案件に着手
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

**判断トリガー条件（具体化 / 検分指摘 2026-05-18）:**
- (a) 複数 Spring Boot インスタンス起動が定常化した時点（現状: 単一インスタンス想定）
- (b) AFTER_COMMIT 取りこぼしが**月次 10 件以上**観測された時点
- (c) Phase D 完了から **6 ヶ月経過後の定期見直し**（memory にリマインダ記録）

上記いずれか早い方の達成時に別軍議を起こす。

### 9.6 最後の ADMIN が退会した場合の組織オーナー継承（検分追加 / 必須）

**論点:** `RoleService#removeMember`（`RoleService.java:142`）の `checkLastAdmin` ガードが「最後の ADMIN を削除しようとすると `BusinessException(ROLE_001)` を投げる」設計。退会経路で `RolePurgeEventListener` がこのメソッドを呼ぶと、退会者が最後の ADMIN だった組織で例外が発生し purge が止まる。

**選択肢:**
- A. `RolePurgeEventListener` 専用に `removeMemberWithoutAdminCheck(scopeId, scopeType, userId)` を `RoleService` に新設し、退会経路では `checkLastAdmin` をバイパス。残った組織は「ADMIN 不在」状態で運用判断（手動で他メンバー昇格 or arch化）
- B. 退会受付時（即時匿名化フェーズ）に「最後の ADMIN かつ他メンバーがいる組織」を検出し、ユーザーに「後任 ADMIN を指名するか組織を arch化するか」を選択させる UX を追加
- C. 退会受付時に自動で「次の最古参メンバーを ADMIN に昇格」させる規約を制定（民主主義/独裁の選択を一律機械化）
- D. 「最後の ADMIN かつ他メンバー 1 人以上」の組織は退会自体を拒否する規約を制定（ユーザー責任で事前整理）

**推奨:** B + A の併用。Phase B-1 出陣前に**マスター御裁可必須**（UX 設計 + 法務・運用ルール影響大）。
B が間に合わない場合の安全弁として A の `removeMemberWithoutAdminCheck` を先行実装し、運用通知（孤児 ADMIN 不在組織アラート）で人的対応する暫定運用も可。

### 9.7 監視・アラート要件 — 「夜次バッチで拾うから OK」を技術的負債にしないために（検分追加 / 必須）

**論点:** `*PurgeEventListener` の `try-catch WARN`（`TeamMemberCountListener` 同形）は「失敗してもログ警告のみ・夜次バッチが拾う」設計。これは CLAUDE.md「障害対応の原則 — 症状を隠さない」と緊張関係にあり、可観測性が無いと**誰も気付かない技術的負債**に転化する。

**選択肢:**
- A. WARN ログ件数をメトリクス化（Datadog / CloudWatch）し、**日次 1 件以上で PagerDuty 発火**
- B. WARN ログを構造化（`{event: "purge_listener_failure", domain: "role", userId: ..., error: ...}`）し、Sentry / Honeybadger に送信
- C. 夜次補正バッチが「listener が拾いきれなかった孤児行数」を返り値とし、閾値超過で運用通知

**推奨:** A + C の併用。Phase B-1 と同時に必須インフラとして整備。
Phase B 併走期間中は「listener 失敗 → 既存越境 DELETE が結局拾う」ので影響軽微だが、Phase C で既存越境 DELETE を撤去した瞬間に取りこぼしが本物の不整合になる。**Phase C 着手の前提条件**とすべき。

### 9.8 退会バースト時の `event-pool` 枯渇対策（検分追加 / 推奨）

**論点:** 既存 `event-pool` は `WebhookDeliveryService` / `OgpFetchService` / `BudgetWorkflowListener` 等多数で共用されている（`AsyncConfig.java`）。退会バッチが 100 件処理 × 6 ドメイン listener = **600 タスクが瞬時 enqueue** される可能性があり、`event-pool` の queueCapacity を超えると他機能（Webhook 配信等）に影響する。

**選択肢:**
- A. 専用プール `purge-pool`（小規模・別管理）を新設し、`@Async("purge-pool")` で分離
- B. `AsyncConfig` の `event-pool` の queueCapacity / maxPoolSize を増強
- C. 退会バッチ自体の処理粒度を絞る（1 サイクルあたり 10 件まで等）

**推奨:** A。`event-pool` の品質保証を侵さず、退会経路の独立性を担保。Phase B-1 と同時着手で大きな追加コストにならない。
ただし `AsyncConfig.java` の現状値（`corePoolSize / maxPoolSize / queueCapacity`）を Phase B-1 出陣前に確認し、B が必要十分なら A を後回しにする判断もあり。

### 9.9 `AccountPurgedEvent` payload にシャーディング考慮を含めるか（検分追加 / 推奨）

**論点:** 現案 `AccountPurgedEvent(userId, emailHash)` は `organization_id` を持たない。1000 万ユーザー時のシャードキーが `organization_id` になると（`db_scalability.md` Phase 4 想定）、各 `*PurgeEventListener` は「userId だけで全シャードを横断する DML を打つ」ことになり、シャードルーティング層で全シャードブロードキャスト化する。

**選択肢:**
- A. `AccountPurgedEvent(userId, emailHash)` のまま（YAGNI / 現状最小）
- B. `AccountPurgedEvent(userId, emailHash, Set<Long> organizationIds)` でその user が所属していた全組織を含める
- C. `AccountPurgedEvent` は最小に保ち、各 listener が `user_roles` から `organization_id` を逆引きする

**推奨:** A を Phase B では採用し、§9.5 outbox 判断と同時にシャーディング着手時に B / C への移行を別軍議で判断。
理由: シャーディング着手まで最低 2〜3 年は単一 DB ノード前提なので、現時点では YAGNI で十分。

### 9.10 夜次補正バッチの O(N) スキャン対策（検分追加 / 推奨）

**論点:** Phase D の「孤児 user_id 検出 → 削除」バッチは、無策だと
`SELECT user_id FROM team_org_memberships WHERE user_id NOT IN (SELECT id FROM users)`
という相関サブクエリで 1000 万ユーザー × 子テーブル N で**フルスキャン**に陥る。

**選択肢:**
- A. `users.purged_at` カラムにインデックスを張り、「最新の purge から N 日以内に purge された user_id のみ」を対象にする差分検出
- B. `users` 物理削除前に「`pre_purge_user_ids` 一時テーブル」に user_id を退避し、バッチはそれを参照
- C. `db_scalability.md` Phase 3 のパーティショニング（月次パーティション）を子テーブル側にも適用し、補正バッチも月次パーティション単位で走らせる

**推奨:** A を Phase D で必須化、B / C は 1000 万ユーザー到達近辺で再評価。
Phase D 設計書冒頭に「孤児検出は差分方式・フルスキャン禁止」の制約を明記すべき。

### 9.11 部分失敗時の「全ドメイン完了監査ログ」発行判定（検分追加 / 推奨）

**論点:** 各 `*PurgeEventListener` が `REQUIRES_NEW` で独立 commit するため「team listener 成功 + payment listener 失敗」のような部分失敗状態が発生し得る。§5.3「`ACCOUNT_PURGE_COMPLETED_ALL_DOMAINS` 監査ログ」発行時にどう判定するか曖昧。

**選択肢:**
- A. `account_purge_completion_status` テーブルを新設し、6 ドメイン × `(userId, domain, completed_at, status)` で per-domain 完了表を持ち、全行 SUCCESS で監査ログ発行
- B. 夜次監査バッチが「孤児 user_id が全ドメインで 0」を確認した時点で監査ログ発行（テーブル不要だが SQL コスト高）
- C. listener 完了時に各ドメインが個別に `ACCOUNT_PURGE_COMPLETED_<DOMAIN>` 監査ログを発行し、運用側で集約

**推奨:** A。GDPR Art.17 「30 日以内に削除完了」の証跡として per-domain 完了表が最も明示的。Phase D の最重要成果物として設計書に組み込むべき。

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
| `docs/architecture/withdrawal_flow_immediate_anonymization_fix.md` | 即時匿名化リスナー全休眠の根治治療設計（PR #793 / f3708a9b4 main マージ済 2026-05-18） |

---

## 変更履歴

| 日付 | 内容 | 担当 |
|---|---|---|
| 2026-05-18 | 初版作成（陣立て書） | 家老（Plan agent） |
| 2026-05-18 | §3.5 追加（F15.4 Caveat 自動解消・副次効果） | 殿（追記指示反映） |
| 2026-05-18 | 検分修正反映：§3.5 Before/After 図の事実誤認修正（team→role）／§2.1 表 #14・#10 追記／§2.2 算数誤り（7→8）／§3.1 採用方針 A/B/C 案比較表新設／§4 Phase B-1 順序入替（team→role）+ PR# 記入欄追加 + 冪等性留意ノート／§6 影響度マトリクス並び替え／§9 必須追記 2 件（9.6 最後の ADMIN 退会／9.7 監視・アラート要件）＋推奨追記 4 件（9.8 event-pool ／9.9 organization_id payload／9.10 補正バッチ O(N)／9.11 部分失敗監査） | 殿（家老検分反映） |
| 2026-05-18 | addendum: §2.3 事実誤認訂正。PR #793（main マージ済 / f3708a9b4）の検分で `UserService#withdrawUser()` 呼出元ゼロ・9 ドメインの即時匿名化リスナー全休眠中が確定。「即時時に削除済 ✅」7 行を全て ❌（休眠中）に修正、結論段落を「両系統統合ではなく休眠リスナーの 30 日後フェーズ統合 + 即時匿名化再有効化（W-A〜W-F）の両輪」に書き換え。関連ドキュメント表に `withdrawal_flow_immediate_anonymization_fix.md` を追加 | 足軽（addendum PR）|
| 2026-05-18 | Phase B-1 実装 PR `_TBD_`：`AccountPurgedEvent` 新規（gdpr.event）+ `AccountPurgeService#purgeUser` 末尾に `eventPublisher.publishEvent(...)` 発火追加 + `RolePurgeEventListener` 新設（role.event、`@Async` + `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` 三重防御）。`UserRoleRepository#findAllByUserId(Long)` 追加。SYSTEM_ADMIN（team_id・organization_id 共に NULL）はスキップ。既存越境 DELETE は Phase C で撤去するまで併走。テスト追加: `AccountPurgedEventTest`（3 件）+ `RolePurgeEventListenerTest`（4 件、正常/失敗継続/0件/SystemAdmin スキップ）+ `AccountPurgeServiceTest` に Phase B-1 発火検証 1 件追加。F15.4 Caveat 自動解消メカニズム発動 | 足軽（Phase B-1 v2）|
| 2026-05-18 | Phase B-2 実装 PR `_TBD_`：`TeamPurgeEventListener` 新設（team.event、PR #837 Phase B-1 と同型の三重防御 `@Async("event-pool")` + `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`）。`TeamOrgMembershipRepository#nullifyInvitedBy` / `nullifyRespondedBy` を購読側で呼び出し。`nullifyInvitedBy` 失敗時も `nullifyRespondedBy` を継続する独立 try-catch 構造（GDPR 30 日タイムリミット遵守）。既存 `AccountPurgeService#purgeUser` の越境 NULL 化 DML は当面残置（冪等のため二重実行で機能影響なし、Phase C で撤去）。テスト追加: `TeamPurgeEventListenerTest`（4 件、正常両方呼出 / invitedBy 失敗継続 / respondedBy 失敗継続 / 0 件）。F15.4 Caveat には team_org_memberships が member_count 直接関与しないため影響なし | 足軽（Phase B-2）|
| 2026-05-18 | Phase B-6 実装 PR `_TBD_`：`ErrorReportPurgeEventListener` 新設（errorreport.event、`@Async("event-pool")` + `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` 三重防御）。`AccountPurgedEvent` を購読し `errorReportOccurrenceRepository.anonymizeByUserId(userId)` を呼出。匿名化対象は F12.5 Phase 2-F 仕様維持（`ip_address` / `user_agent` / `user_id` を NULL 化）。既存越境 DML（`AccountPurgeService.java:161-164`）は Phase C-6 まで併走。テスト追加: `ErrorReportPurgeEventListenerTest`（3 件、正常/0件/例外伝播せず） | 足軽（Phase B-6）|
| 2026-05-18 | Phase B-4 実装 PR `_TBD_`：`ChartPurgeEventListener` 新設（chart.event、`@Async("event-pool")` + `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` 三重防御）。`AccountPurgedEvent` を購読し `chartRecordRepository.anonymizeCustomerUserId(userId)` を呼出（`chart_records.customer_user_id` を NULL 化）。既存越境 DML（`AccountPurgeService.java:152`）は Phase C-4 まで併走。テスト追加: `ChartPurgeEventListenerTest`（3 件、正常/0件/例外伝播せず）。PR #837（B-1 role）/ #845（B-2 team）/ #847（B-6 errorreport）と同型 | 足軽（Phase B-4）|
| 2026-05-18 | Phase B-5 実装 PR `_TBD_`：`ProxyPurgeEventListener` 新設（proxy.event、PR #837 Phase B-1 と同型の三重防御 `@Async("event-pool")` + `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`）。`AccountPurgedEvent` を購読し **2 操作・混在型**（`proxyInputRecordRepository.deleteAllBySubjectUserId(userId)` で **物理削除** + `proxyInputConsentRepository.logicalDeleteAllBySubjectUserId(userId)` で **論理削除**）を実行。records は本人特定情報そのものを含むため GDPR 削除権により物理削除、consents は監査証跡として記録自体を保持するため論理削除（`deleted_at` セット）。各操作を独立 try-catch で囲み、片方が失敗してももう片方を継続（GDPR 30 日タイムリミット遵守）。既存越境 DML（`AccountPurgeService.java:201-203`）は Phase C-5 まで併走。テスト追加: `ProxyPurgeEventListenerTest`（5 件、正常両Repo呼出 / 0 件 / records失敗時もconsents継続 / consents失敗_伝播せず / 両方失敗_伝播せず）。F14.1 Phase 13-γ 由来データの清掃が完了 | 足軽（Phase B-5）|
| 2026-05-18 | Phase B-3 実装 PR `_TBD_`：`PaymentPurgeEventListener` 新設（payment.event、`@Async("event-pool")` + `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` 三重防御）。`AccountPurgedEvent` を購読し **2 操作混在パターン** を実行: (1) `memberPaymentRepository.anonymizeUserId(userId, SENTINEL_USER_ID)` でセンチネル化（GDPR Art.17 と会計税法 7 年保持の両立、once-only ターゲット選択型）/ (2) `stripeCustomerRepository.findByUserId().ifPresent(delete)` で Stripe 顧客行物理削除（Stripe API 側顧客は本 PR スコープ外）。2 操作はそれぞれ独立 try-catch で囲み 1 操作失敗時も他継続。既存越境 DML（`AccountPurgeService.java:177-183`）は Phase C-2 まで併走。テスト追加: `PaymentPurgeEventListenerTest`（5 件、正常両操作 / 0 件両方 / センチネル失敗→Stripe 継続 / Stripe 不在で delete 未呼出 / Stripe 削除失敗で例外伝播なし）。Phase B シリーズ最終陣。PR #837（B-1 role）/ #845（B-2 team）/ #850（B-4 chart）/ #851 (B-5 proxy) / #847（B-6 errorreport）と同型 | 足軽（Phase B-3）|
| 2026-05-19 | **Phase C 実装 PR #858（越境 DML 一括撤去）**: `AccountPurgeService#purgeUser()` から 6 ドメイン越境 DML を全廃。削除した import: `ChartRecordRepository` / `UserRoleRepository` / `TeamOrgMembershipRepository` / `MemberPaymentRepository` / `StripeCustomerRepository` / `ProxyInputConsentRepository` / `ProxyInputRecordRepository` / `ErrorReportOccurrenceRepository` / `UserConstants`。削除したフィールド: 上記 8 Repository 注入。削除した DML 呼び出し: B-1〜B-6 の各 PurgeEventListener が担う全操作（chart匿名化 / role DELETE / team NULL化 / payment センチネル+Stripe削除 / proxy 物理・論理削除 / errorreport 匿名化）。`AccountPurgeServiceTest` を Phase C 後の状態に更新（越境 @Mock 8 フィールド削除・stubAuthAndGdprMocks ヘルパー抽出・data_exports S3削除継続確認テスト追加）。設計書 §4 Phase B PR# 確定・Phase C 完了を記録 | 足軽（Phase C）|
