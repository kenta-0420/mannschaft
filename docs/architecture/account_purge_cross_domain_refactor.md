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

**意思決定（マスター御裁可 2026-05-19）:**

| # | 論点 | 選択肢 | 採用 |
|---|---|---|---|
| ① | 全ドメイン完了監査の実装方式（§9.11） | A. `account_purge_completion_status` テーブル新設 / B. 孤児検出 SQL / C. 個別監査ログ | **案 A 採用** — GDPR Art.17 30 日証跡として per-domain 完了表が最も明示的 |
| ② | `*PurgeEventListener` への `@Retryable` 付与（§3.4） | 採用 / 不採用 | **不採用** — 夜次補正バッチが保険として十分。`@Retryable` を重ねると「補正バッチと二重実行」の冪等性リスクが増す。Phase D-2 以降で必要性が判明した時点で再評価 |
| ③ | `event-pool` 枯渇対策（§9.8） | A. 専用プール `purge-pool` 新設 / B. `event-pool` 増強 / C. バッチ粒度削減 | **案 A 採用** — `event-pool` の品質保証を侵さず退会経路を独立管理。Phase D-1 で実施済み（PR #XXX） |

**Phase D-1 実施内容（本 PR）:**
- `AsyncConfig.java` に `purge-pool` Bean を追加（corePoolSize=2 / maxPoolSize=10 / queueCapacity=500）
- 6 本の `*PurgeEventListener` の `@Async("event-pool")` を `@Async("purge-pool")` に切り替え
- 対象リスナー: `RolePurgeEventListener` / `TeamPurgeEventListener` / `PaymentPurgeEventListener` / `ChartPurgeEventListener` / `ProxyPurgeEventListener` / `ErrorReportPurgeEventListener`

**1. 夜次補正バッチ整備（1 ドメイン = 1 PR）**

各ドメインに「孤児 user_id 検出 → 削除」バッチを設置（既存 `TeamMemberCountBackfillBatchService` と同形）:
```java
@Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
@SchedulerLock(name = "teamPurgeBackfillBatch", lockAtMostFor = "PT10M")
public void backfill() {
    // users から物理削除済の user_id を取得し、team 配下のテーブルで孤児を削除
}
```

**2. `@Retryable` 導入** — **不採用（意思決定 ② 参照）**

**3. GDPR 監査ログ強化（意思決定 ① 案 A）** ✅ Phase D-8 で実施済み

- `account_purge_completion_status` テーブル新設（6 ドメイン × `(userId, domain, completed_at, status)` で per-domain 完了を記録）
  - Flyway: `V9.179__create_account_purge_completion_status.sql`
  - Entity: `com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity`（UUIDv7, FK なし）
  - `email_hash` は SHA-256 の64桁hexを保持する。正本DDLの `CHAR(64) NOT NULL` を維持し、Entity は JDBC 型 `CHAR` と `columnDefinition = "CHAR(64)"` を明示する。
  - `retry_count` は正本DDLの `TINYINT UNSIGNED NOT NULL DEFAULT 0` と Java `Integer` を維持する。GDPR限定の JDBC 型でDDL照合コードだけを `TINYINT` にし、bind/extract は `INTEGER` の `setInt` / `getInt` を継承して0..255を保持する。256はMySQLの範囲外拒否とし、rollback後もcommit済み255の行を保全する。
  - Repository: `com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository`
- `AccountPurgeService#purgeUser()` が `AccountPurgedEvent` 発火前に 6 ドメイン分の PENDING レコードを INSERT
- 各 `*PurgeEventListener` が処理成功時に対応ドメインの status を SUCCESS に更新
- `GdprPurgeAuditBatchService`（毎日 05:00 JST）が PENDING のまま 2 時間以上経過したレコードを検出して `log.error` アラートを出力
  - `@BatchEndpoint(name = "gdpr-purge-audit-daily")`
  - `@SchedulerLock(name = "gdprPurgeAuditBatch", lockAtMostFor = "PT30M")`

**Phase D-8 実施内容（PR #XXX）:**

| 追加・変更ファイル | 内容 |
|---|---|
| `V9.179__create_account_purge_completion_status.sql` | Flyway マイグレーション（証跡テーブル新設） |
| `AccountPurgeCompletionStatusEntity` | Entity（UUIDv7 主キー、FK なし） |
| `AccountPurgeCompletionStatusRepository` | Repository（findByStatusAndAttemptedAtBefore / findByUserId / findByUserIdAndDomainName） |
| `AccountPurgeService` | purgeUser() に PENDING INSERT ロジック追加 |
| `RolePurgeEventListener` | 処理完了時に SUCCESS 更新を追加 |
| `TeamPurgeEventListener` | 処理完了時に SUCCESS 更新を追加 |
| `PaymentPurgeEventListener` | 処理完了時に SUCCESS 更新を追加 |
| `ChartPurgeEventListener` | 処理完了時に SUCCESS 更新を追加 |
| `ProxyPurgeEventListener` | 処理完了時に SUCCESS 更新を追加 |
| `ErrorReportPurgeEventListener` | 処理完了時に SUCCESS 更新を追加 |
| `GdprPurgeAuditBatchService` | 監査バッチ（05:00 JST 日次実行、2 時間超 PENDING をアラート） |
| `AccountPurgeServiceTest` | Phase D-8 テスト追加（PENDING 6 件 INSERT 確認） |
| `GdprPurgeAuditBatchServiceTest` | 監査バッチテスト新規作成（4 ケース） |

**4. 法務レビュー**
- 全 Phase 完了後にマスター主導で実施

**PR 数:** 7〜8（purge-pool D-1 済 + 補正バッチ 6 + 監査バッチ 1〜2）

### 全体 PR 数概算

| Phase | PR 数 | 累計 | ステータス |
|---|---|---|---|
| A | 1 | 1 | ✅ 完了 |
| B | 6 | 7 | ✅ 完了（PR #837/#845/#855/#850/#851/#847） |
| C | 7 | 14 | ✅ 完了（PR #858 含む） |
| D-1 | 1 | 15 | ✅ 完了（purge-pool 専用スレッドプール） |
| D-8 | 1 | 16 | ✅ 完了（GDPR 監査バッチ + completion_status テーブル） |
| D（残） | 6〜7 | **22〜23** | 🟡 夜次補正バッチ 6 ドメイン + 法務レビュー |

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
| 2026-05-19 | **Phase D-1 実装 PR #XXX（purge-pool 専用プール新設 + 6 リスナー切り替え）**: 意思決定 ①②③ を設計書に記録（案 A/不採用/案 A）。`AsyncConfig.java` に `purge-pool` Bean を追加（corePoolSize=2 / maxPoolSize=10 / queueCapacity=500）。6 本の `*PurgeEventListener` の `@Async("event-pool")` を `@Async("purge-pool")` に切り替え。退会バッチ 100 件 × 6 ドメイン = 600 タスクが `event-pool`（queueCapacity=100）を枯渇させる問題を根治 | 足軽（Phase D-1）|
| 2026-05-19 | **Phase C 実装 PR #858（越境 DML 一括撤去）**: `AccountPurgeService#purgeUser()` から 6 ドメイン越境 DML を全廃。削除した import: `ChartRecordRepository` / `UserRoleRepository` / `TeamOrgMembershipRepository` / `MemberPaymentRepository` / `StripeCustomerRepository` / `ProxyInputConsentRepository` / `ProxyInputRecordRepository` / `ErrorReportOccurrenceRepository` / `UserConstants`。削除したフィールド: 上記 8 Repository 注入。削除した DML 呼び出し: B-1〜B-6 の各 PurgeEventListener が担う全操作（chart匿名化 / role DELETE / team NULL化 / payment センチネル+Stripe削除 / proxy 物理・論理削除 / errorreport 匿名化）。`AccountPurgeServiceTest` を Phase C 後の状態に更新（越境 @Mock 8 フィールド削除・stubAuthAndGdprMocks ヘルパー抽出・data_exports S3削除継続確認テスト追加）。設計書 §4 Phase B PR# 確定・Phase C 完了を記録 | 足軽（Phase C）|


## CMP-260822-1243: 本人設定の30日後強消去

既存8domain（role/team/payment/chart/proxy/errorreport/resume/billing）を維持し、本人設定27domainを
`AccountPurgeService` の本体TXでPENDING登録する。対象はactionmemo、pointcard、timeline、search、
dashboard、scopefolder、schedule、quickmemo、auth、notification、filesharing、contact、user、appearance、
navsettings、gamification、reflection、timetable.personal、cms、chat、knowledgebase、favorite、membership、
weather、inbox、timetable.notes、sealである。設定38親表と同domainの子4表の所有境界・最終FKは
`PersonalSettingsAccountPurgeIT` と `FlywayFromScratchMigrationTest` の判断一覧・native件数で照合する。
公開batchのapp-schema子2表と、Flyway実schemaの子4表の証跡範囲は区別する。

各ownerの新規TXで所有設定を全削除し、コミット成立後のafterCommitから既存CompletionService新規TXへ
SUCCESSを記録する。途中SQL失敗はowner全体をrollbackし、完了更新失敗・queue拒否はPENDINGを保持する。
設定27domainの手動retryはowner proxyのコミット成立後だけSUCCESSを保存し、失敗はPENDINGと試行数に残す。
旧7domainの手動retryとresume未対応は変更しない。
非TXの `GdprPurgeRetryFacade` が入口を振り分け、旧7domainは既存
`GdprPurgeRetryService` の外側TXと処理を維持する。新27domainは非TXの
`GdprSettingsPurgeRetryService` からownerのREQUIRES_NEWを呼び、ownerコミット後に
GDPRリポジトリ自身のTXで結果を保存する。旧7のTX入口から新27へ到達させない。空・重複は冪等に処理する。

弱イベントは引き続き休眠中であり、この強側安全網は即時匿名化の有効化を意味しない。
共有本文・membership・visibility_templates・村pin/nicknameは今回対象外。時間割メモの定義のみ削除し本文JSONを残し、
seal_scope_defaultsの選択のみ削除し印鑑・押印履歴を残す。新DDL・pool・共通busは追加しない。
正式GREENと全AC完了は、この実装sourceでのCI/実機証跡取得後に判断する。

### 監査対象と可否判断（38親表・27設定domain）

以下は強側の消去対象である。所有列は特記以外`user_id`。本人の表示・分類・選択を残す保持根拠が
アカウント物理削除後にないため消去し、参照先の共同本文は保持する。既存弱側の時期・購読本体は変更しない。
猶予内保持の承認済み集合は`PersonalSettingsAccountPurgeIT#RETAINED_UNTIL_STRONG`で別に検証する。
弱側の残存リカバリも含む強側の網羅集合を、猶予保持集合と同一視しない。

| domain | 設定親表 | 可否判断・所有境界 |
| --- | --- | --- |
| actionmemo | `action_memo_tags` / `user_action_memo_settings` | 本人の分類・入力設定を消去。タグ論理行も含め、行動メモ本文の弱側は変更しない |
| pointcard | `point_card_groups` / `point_card_user_settings` | 本人のカード整理・入力設定を消去。カード本文の弱側は変更しない |
| timeline | `timeline_bookmarks` / `user_mutes` | 本人の保存・表示分類を消去。投稿と他所有者の設定は保持 |
| search | `search_saved_queries` | 本人の保存検索条件を消去。検索履歴の弱側は変更しない |
| dashboard | `dashboard_widget_settings` / `dashboard_scope_tab_order` / `chat_contact_folders` | 本人の画面・並び・分類を消去。scope tab即時消去の承認と未配線は別事実で、強側安全網を即時成立としない |
| scopefolder | `my_scope_folders` | 本人の分類設定を論理行も含め消去。チーム・組織本文は保持 |
| schedule | `user_calendar_sync_settings` / `user_calendar_layer_settings` | 本人の同期・レイヤー設定を消去。既存弱側とスコープ削除の別Bean境界は保持 |
| quickmemo | `user_quick_memo_settings` | 本人の入力設定のみ消去。本文・タグ・Sagaは対象外 |
| auth | `user_interest_tags` | 本人の関心分類を消去。OAuth・2FAの弱側は変更しない |
| notification | `notification_settings` / `notification_preferences` / `notification_type_preferences` / `push_subscriptions` | 本人の通知設定を消去。通知本文・archiveの即時PII層は変更しない |
| filesharing | `shared_file_stars` | 本人の保存分類のみ消去。共同ファイル・タグ・フォルダは保持 |
| contact | `contact_request_blocks` | `user_id`または`blocked_id`が退会者の関係を消去。他者間の関係は保持（F04.8の既存両端CASCADE契約回復） |
| user | `user_blocks` | `blocker_id`または`blocked_id`が退会者の関係を消去。他者間の関係は保持（V62.002の両端FK撤廃に対応） |
| appearance | `appearance_settings` | 本人の外観設定を消去。UUID主キーではなく所有列で絞る |
| navsettings | `user_nav_settings` | 本人の表示設定を消去 |
| gamification | `gamification_user_settings` | 本人の全スコープ設定を消去。ランキング本文は対象外 |
| reflection | `user_reflection_settings` | 本人のリマインド設定を消去。テーマ・振り返り本文は対象外 |
| timetable.personal | `personal_timetable_settings` | 本人の表示設定を消去。時間割本文は対象外 |
| cms | `user_blog_settings` | 本人のブログ設定を消去。記事本文は対象外 |
| chat | `chat_message_bookmarks` | 本人の保存分類のみ消去。message・channel・membershipは対象外 |
| knowledgebase | `kb_page_favorites` | 本人の保存分類のみ消去。KB本文は保持 |
| favorite | `user_favorites` | 本人の保存分類を強側でも消去。既存弱側の時期は変更しない |
| membership | `scope_member_calendar_settings` | 本人の色設定のみ強側でも消去。membership本文・状態は対象外 |
| weather | `user_weather_locations` | 本人の地点設定を強側でも消去。既存即時PII契約の時期は変更しない |
| inbox | `inbox_item_states` / `notification_labels` / `inbox_label_links` | 本人の受信箱分類を消去。論理削除ラベルも含め、既存弱側の時期は変更しない |
| timetable.notes | `timetable_slot_user_note_fields` | 本人定義設定を強側のみ消去し、本文JSONは残す（F03.15の本人定義項目契約） |
| seal | `seal_scope_defaults` | 本人のDEFAULT/TEAM/ORGANIZATION選択を強側のみ消去し、印鑑・押印履歴は保持（F05.3の猶予復元契約） |

| 子表 | 所有経路・現FK | 消去経路と証跡 |
| --- | --- | --- |
| `chat_contact_folder_items` | folder→本人所有親。V3.033の同domain CASCADE維持 | 本人子を明示削除し、他所有者の退会者向けCONTACT参照子も消去。app-schema公開batchと最終Flyway実seedで件数確認 |
| `my_scope_folder_items` | folder→本人所有親。V9.101 `fk_msfi_folder`維持、V62.013はusers FKのみ撤廃 | 論理親配下も明示削除。app-schema公開batchと最終Flyway実seedで件数確認 |
| `action_memo_tag_links` | tag→本人分類。V5.043のtag/memo両CASCADE維持 | 強側の本人tag削除に連動。最終Flyway実seedの本人子0・他所有者保持で証明 |
| `point_card_group_items` | group→本人分類。V9.139のgroup/card両CASCADE維持 | 強側の本人group削除に連動。最終Flyway実seedの本人子0・他所有者保持で証明 |

設定全消去から除外する対象は、`shared_folders` / `shared_file_tags`（共同コンテンツ）、
`onboarding_progresses`（業務進捗）、`confirmable_notification_settings`（本人所有ではないスコープ設定）、
`chat_channel_members`（membership共有契約）、`activity_feed`（既存30日TTLの共有履歴）である。
本文・認証・同意・参加履歴等を設定と一律に扱わない。
`visibility_templates` / 子rulesは正本の理由文言に矛盾がありユーザー判断待ちで変更しない。
村pin/nicknameは既存即時匿名化契約の別残件とし、この設定強消去へ混ぜない（第2陣で御裁可を得て強側へ追加。下記）。
これらの除外・保留を含む棚卸であり、今回の38親表が全システムの個人データ全体の上限であるとはしない。

### 第2陣（2026-10-31）: 所有列の再走査と個人所有表の判断一覧

第1陣の棚卸しは Entity の `private Long userId` を起点にしていたため、`owner_user_id` や
`*_user_id` を所有列に持つ表、Entity を持たない native 専用表を拾えていなかった。第2陣では
Entity（`@Table` と `@Column`／命名規則から導いた列名）と Flyway（`CREATE TABLE`／`ALTER TABLE ADD`
の列、`RENAME`・`DROP` を反映）の両方から、列名が `user_id` または `_user_id` で終わる表を全数列挙し、
次の判断一覧に1表1行で載せた。

**御裁可済みで本PRが強側へ追加した7表**（退会30日後の強匿名化で所有列 `user_id` の一括 DELETE。
行ごとのループは行わない）:

| domain | 表 | 強消去リスナー |
| --- | --- | --- |
| shift | `member_availability_defaults` | `ShiftAvailabilityPurgeEventListener` |
| parking | `parking_watchlist` | `ParkingWatchlistPurgeEventListener` |
| reflection | `reflection_spaced_reminders` | 既存 `ReflectionSettingsPurgeEventListener` の同一TXへ同梱 |
| advertising | `user_ad_preferences` | `UserAdPreferencePurgeEventListener` |
| property | `property_work_history_views` | `PropertyWorkHistoryViewPurgeEventListener` |
| village | `user_village_pins` / `user_village_nicknames` | `VillagePersonalSettingsPurgeEventListener` |

新5domain（shift / parking / advertising / property / village）は第1陣と同じ型で組み込む。
`AccountPurgeService` の本体TXで PENDING を登録し、各リスナーは `purge-pool` 上の REQUIRES_NEW TX で
自ドメインの表だけを削除して、commit 成立後の afterCommit でのみ SUCCESS を記録する。途中の SQL 失敗は
owner の TX 全体をロールバックし、queue 拒否・完了更新失敗はいずれも PENDING を保持する。手動 retry は
非TXの `GdprPurgeRetryFacade` → `GdprSettingsPurgeRetryService` へ振り分け、owner の REQUIRES_NEW が
commit して戻った後にだけ SUCCESS を保存する。対象0件でも SUCCESS、SUCCESS 済みの再 retry はドメイン操作を
行わない（冪等）。村 pin/nickname の弱側（`VillageUserCleanerEventListener`、`UserAnonymizedEvent` 購読）は
発火経路が休眠中のため変更せず、強側で確実に消す。新DDL・新pool は追加しない。

**判断一覧の読み方**: `強消去` は退会30日後に物理削除（既存または本PR）、`匿名化` は所有列の NULL 化・
センチネル化、`弱消去` は弱側購読者で削除（発火経路は休眠中。強側未接続のものは別途判断が要る）、`保持` は
理由を付けて残す、`要裁可` は境界判断が必要なためこの戦役では消さずマスターの判断を待つ。
番人 `WithdrawalPersonalDataListenerCoverageGuardTest` が、所有列（`user_id` / `owner_user_id` / `*_user_id`）
を持つ表がこの一覧に無い場合と、一覧に残った表が実装から消えた場合の両方を CI で失敗させる。新しい表を
追加したら、判断と理由を添えて一覧へ1行足すこと。

<!-- PERSONAL_OWNER_TABLE_LEDGER_START -->
| 表 | 所有列 | 判断 | 理由・経路 |
| --- | --- | --- | --- |
| `account_purge_completion_status` | `user_id` | 保持 | GDPR 完了監査。email_hash のみで本人を再特定できない |
| `action_memo_tags` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `action_memos` | `user_id` | 弱消去 | 弱側 `UserAnonymizedEvent` 購読者で物理削除（発火経路は休眠中。正本 withdrawal_flow_immediate_anonymization_fix.md） |
| `activity_comments` | `user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `activity_participants` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `ad_announcement_deliveries` | `user_id` | 要裁可 | 広告の配信・計測ログ（個人の行動履歴か、広告主課金の根拠か） |
| `ad_banner_deliveries` | `user_id` | 要裁可 | 広告の配信・計測ログ（個人の行動履歴か、広告主課金の根拠か） |
| `ad_campaign_delivery_claims` | `user_id` | 要裁可 | 広告の配信・計測ログ（個人の行動履歴か、広告主課金の根拠か） |
| `ad_campaign_moderation_logs` | `moderator_user_id` | 保持 | 監査ログ（改ざん防止・説明責任） |
| `ad_clicks` | `user_id` | 要裁可 | 広告の配信・計測ログ（個人の行動履歴か、広告主課金の根拠か） |
| `ad_conversions` | `converted_user_id` | 要裁可 | 広告の配信・計測ログ（個人の行動履歴か、広告主課金の根拠か） |
| `ad_email_deliveries` | `user_id` | 要裁可 | 広告の配信・計測ログ（個人の行動履歴か、広告主課金の根拠か） |
| `ad_impressions` | `user_id` | 要裁可 | 広告の配信・計測ログ（個人の行動履歴か、広告主課金の根拠か） |
| `ad_messaging_campaigns` | `created_by_user_id` | 保持 | 共有物の作成者・行為者参照（行は共有物そのもの） |
| `ad_ng_words` | `created_by_user_id` | 保持 | 共有物の作成者・行為者参照（行は共有物そのもの） |
| `ad_push_deliveries` | `user_id` | 要裁可 | 広告の配信・計測ログ（個人の行動履歴か、広告主課金の根拠か） |
| `ad_user_reports` | `reporter_user_id` | 保持 | 通報・モデレーション証跡（被通報者・通報者の記録は運営判断の根拠） |
| `announcement_read_status` | `user_id` | 保持 | 発信側が参照する既読・確認状況（共同業務の記録） |
| `annual_review_responses` | `respondent_user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `appearance_settings` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `attendance_disclosure_records` | `student_user_id` | 保持 | 学校・出欠の業務記録（学籍・法定保存の根拠） |
| `attendance_location_changes` | `student_user_id` | 保持 | 学校・出欠の業務記録（学籍・法定保存の根拠） |
| `attendance_requirement_evaluations` | `resolver_user_id` / `student_user_id` | 保持 | 学校・出欠の業務記録（学籍・法定保存の根拠） |
| `attendance_transition_alerts` | `student_user_id` | 保持 | 学校・出欠の業務記録（学籍・法定保存の根拠） |
| `audit_logs` | `target_user_id` / `user_id` | 保持 | 監査ログ（改ざん防止・説明責任） |
| `billing_contracts` | `payer_user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `billing_payer_handover_requests` | `new_payer_user_id` / `old_payer_user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `birth_profile_commands` | `user_id` | 要裁可 | 出生プロフィールの確認・操作記録（diagnosis 強消去の対象外） |
| `birth_profile_confirmations` | `user_id` | 要裁可 | 出生プロフィールの確認・操作記録（diagnosis 強消去の対象外） |
| `blog_post_reactions` | `user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `blog_posts` | `first_published_author_user_id` / `user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `blog_ranch_admin_commands` | `actor_user_id` | 強消去 | 本人が実行した牧場管理コマンドの冪等記録（結果JSON・本文ハッシュ）。cms の強消去リスナー `UserBlogSettingsPurgeEventListener` が `cms` 強消去TX内で `actor_user_id` 一致行を一括削除 |
| `blog_ranch_outboxes` | `recipient_user_id` | 強消去 | 本人宛ての牧場配送 outbox（payload JSON を含む技術配送表）。`UserBlogSettingsPurgeEventListener` が同TX内で `recipient_user_id` 一致行を一括削除 |
| `blog_ranch_witnesses` | `recipient_user_id` | 強消去 | 本人の牧場資格判定の証跡（内容ダイジェスト・週次）。`UserBlogSettingsPurgeEventListener` が同TX内で `recipient_user_id` 一致行を一括削除 |
| `bulletin_reactions` | `user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `bulletin_read_status` | `user_id` | 保持 | 発信側が参照する既読・確認状況（共同業務の記録） |
| `chart_records` | `customer_user_id` / `staff_user_id` | 匿名化 | `ChartPurgeEventListener` が `customer_user_id` を NULL 化。`staff_user_id` は施術者の業務記録として保持 |
| `chat_channel_members` | `user_id` | 保持 | membership・参加の共有契約（既決の除外対象。RolePurge が権限を除去） |
| `chat_contact_folders` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `chat_message_bookmarks` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `chat_message_reactions` | `user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `circulation_comments` | `user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `circulation_recipients` | `user_id` | 保持 | 発信側が参照する既読・確認状況（共同業務の記録） |
| `circulation_stamp_delegations` | `delegatee_user_id` / `delegator_user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `class_homerooms` | `homeroom_teacher_user_id` | 保持 | 学校・出欠の業務記録（学籍・法定保存の根拠） |
| `coin_toss_results` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `committee_invitations` | `invitee_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `committee_members` | `user_id` | 保持 | membership・参加の共有契約（既決の除外対象。RolePurge が権限を除去） |
| `confirmable_notification_recipients` | `user_id` | 保持 | 発信側が参照する既読・確認状況（共同業務の記録） |
| `contact_invite_tokens` | `user_id` | 要裁可 | 外部連携・購読トークン（本人以外に用途がないが既存弱側の対象外） |
| `contact_request_blocks` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `content_reports` | `target_user_id` | 保持 | 通報・モデレーション証跡（被通報者・通報者の記録は運営判断の根拠） |
| `content_reports_archive` | `target_user_id` | 保持 | 通報・モデレーション証跡（被通報者・通報者の記録は運営判断の根拠） |
| `coupon_distributions` | `user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `daily_attendance_records` | `student_user_id` | 保持 | 学校・出欠の業務記録（学籍・法定保存の根拠） |
| `dashboard_scope_tab_order` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `dashboard_widget_settings` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `data_exports` | `user_id` | 強消去 | `AccountPurgeService` 本体TX（S3 ファイルと行を削除） |
| `diagnosis_commands` | `user_id` | 強消去 | `DiagnosisPurgeEventListener` |
| `diagnosis_results` | `user_id` | 強消去 | `DiagnosisPurgeEventListener` |
| `diagnosis_sessions` | `user_id` | 強消去 | `DiagnosisPurgeEventListener` |
| `direct_mail_recipients` | `user_id` | 要裁可 | 広告の配信・計測ログ（個人の行動履歴か、広告主課金の根拠か） |
| `direct_mail_recipients_archive` | `user_id` | 要裁可 | 広告の配信・計測ログ（個人の行動履歴か、広告主課金の根拠か） |
| `disclosure_exports` | `requester_user_id` | 保持 | 監査ログ（改ざん防止・説明責任） |
| `electronic_seals` | `user_id` | 保持 | 印鑑・押印履歴（F05.3 の既決で保持） |
| `email_change_tokens` | `user_id` | 強消去 | `AccountPurgeService` 本体TX（auth トークン・認証情報） |
| `email_outbox` | `user_id` | 要裁可 | 送信待ち・一時データ（宛先PIIを含む） |
| `email_verification_tokens` | `user_id` | 強消去 | `AccountPurgeService` 本体TX（auth トークン・認証情報） |
| `emergency_closure_confirmations` | `user_id` | 弱消去 | 弱側 `UserAnonymizedEvent` 購読者で物理削除（発火経路は休眠中。正本 withdrawal_flow_immediate_anonymization_fix.md） |
| `equipment_assignments` | `assigned_by_user_id` / `assigned_to_user_id` / `returned_by_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `equipment_ranking_exclusions` | `excluded_by_user_id` | 保持 | 共有物の作成者・行為者参照（行は共有物そのもの） |
| `error_report_occurrences` | `user_id` | 匿名化 | `ErrorReportPurgeEventListener` が user_id・IP・UA を NULL 化 |
| `error_reports` | `user_id` | 要裁可 | エラー報告本体の報告者（occurrence のみ匿名化済み） |
| `event_care_notification_logs` | `care_recipient_user_id` / `watcher_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `event_checkins` | `roll_call_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `event_registrations` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `event_rsvp_responses` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `event_survey_responses` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `external_agent_delegations` | `agent_user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `facility_booking_payments` | `payer_user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `family_attendance_notices` | `student_user_id` / `submitter_user_id` | 保持 | 学校・出欠の業務記録（学籍・法定保存の根拠） |
| `feedback_votes` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `gamification_user_settings` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `gdpr_s3_purge_failures` | `user_id` | 保持 | GDPR S3 削除の再試行運用記録（S3キーのみ） |
| `geonames_metadata` | `imported_by_user_id` | 保持 | 共有物の作成者・行為者参照（行は共有物そのもの） |
| `google_calendar_webhook_channels` | `user_id` | 要裁可 | 外部連携・購読トークン（本人以外に用途がないが既存弱側の対象外） |
| `guardianship_transition_notifications` | `child_user_id` / `recipient_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `inbox_item_states` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `inbox_label_links` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `incident_assignments` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `incident_comments` | `user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `incident_maintenance_schedules` | `default_assignee_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `invite_tokens` | `target_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `job_applications` | `applicant_user_id` / `decided_by_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `job_check_ins` | `worker_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `job_contracts` | `requester_user_id` / `worker_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `job_postings` | `created_by_user_id` | 保持 | 共有物の作成者・行為者参照（行は共有物そのもの） |
| `job_qr_tokens` | `issued_by_user_id` | 保持 | 共有物の作成者・行為者参照（行は共有物そのもの） |
| `join_requests` | `requester_user_id` / `reviewer_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `kb_page_favorites` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `match_events` | `player_user_id` / `related_player_user_id` | 保持 | 試合・大会・成績の共同記録 |
| `match_roster_staff` | `user_id` | 保持 | 試合・大会・成績の共同記録 |
| `match_score_entries` | `competitor_user_id` | 保持 | 試合・大会・成績の共同記録 |
| `matches` | `scorekeeper_user_id` | 保持 | 試合・大会・成績の共同記録 |
| `member_availability_defaults` | `user_id` | 強消去 | 本PRで追加。退会30日後の強匿名化で所有列一括DELETE（御裁可済）。shift ドメイン `ShiftAvailabilityPurgeEventListener` |
| `member_cards` | `active_platform_user_id` / `user_id` | 要裁可 | チームが参照する本人プロフィール・回答（PIIを含む） |
| `member_payments` | `payer_user_id` / `user_id` | 匿名化 | `PaymentPurgeEventListener` がセンチネル化（会計7年保持との両立） |
| `member_profiles` | `user_id` | 要裁可 | チームが参照する本人プロフィール・回答（PIIを含む） |
| `member_skills` | `user_id` | 要裁可 | 本人の勤務条件・技能（シフト業務の判断材料か本人設定か） |
| `member_work_constraints` | `user_id` | 要裁可 | 本人の勤務条件・技能（シフト業務の判断材料か本人設定か） |
| `membership_beneficiary_withdrawal_cancellations` | `beneficiary_user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `membership_payer_withdrawal_cancellations` | `payer_user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `membership_subscriptions` | `beneficiary_user_id` / `payer_user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `memberships` | `user_id` | 保持 | membership・参加の共有契約（既決の除外対象。RolePurge が権限を除去） |
| `mentions` | `mentioned_user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `mfa_recovery_tokens` | `user_id` | 強消去 | `AccountPurgeService` 本体TX（auth トークン・認証情報） |
| `moderation_appeals` | `user_id` | 保持 | 通報・モデレーション証跡（被通報者・通報者の記録は運営判断の根拠） |
| `monitoring_committee_visits` | `subject_user_id` / `visitor_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `my_scope_folders` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `notification_credit_purchases` | `purchased_by_user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `notification_labels` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `notification_preferences` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `notification_settings` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `notification_type_preferences` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `notifications` | `user_id` | 弱消去 | 弱側 `UserAnonymizedEvent` 購読者で物理削除（発火経路は休眠中。正本 withdrawal_flow_immediate_anonymization_fix.md） |
| `notifications_archive` | `user_id` | 弱消去 | 弱側 `UserAnonymizedEvent` 購読者で物理削除（発火経路は休眠中。正本 withdrawal_flow_immediate_anonymization_fix.md） |
| `oauth_accounts` | `user_id` | 強消去 | `AccountPurgeService` 本体TX（auth トークン・認証情報） |
| `oauth_link_tokens` | `user_id` | 強消去 | `AccountPurgeService` 本体TX（auth トークン・認証情報） |
| `offline_sync_conflicts` | `user_id` | 要裁可 | 送信待ち・一時データ（宛先PIIを含む） |
| `onboarding_progresses` | `user_id` | 保持 | 業務進捗（既決の除外対象） |
| `organization_blocks` | `user_id` | 保持 | 通報・モデレーション証跡（被通報者・通報者の記録は運営判断の根拠） |
| `otp_challenges` | `user_id` | 要裁可 | 外部連携・購読トークン（本人以外に用途がないが既存弱側の対象外） |
| `ownership_transfer_offers` | `target_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `page_view_logs` | `user_id` | 要裁可 | 既知の境界案件: 閲覧ログ（90日TTLで消えるが退会時に前倒すか） |
| `parental_consent_links` | `child_user_id` / `parent_user_id` | 強消去 | `AccountPurgeService` 本体TXで子側リンクを削除。親側は REVOKED のまま値参照のみ保持 |
| `parking_applications` | `user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `parking_assignments` | `user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `parking_listings` | `transferee_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `parking_sublease_applications` | `user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `parking_sublease_payments` | `payee_user_id` / `payer_user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `parking_visitor_recurring` | `user_id` | 要裁可 | 本人の車両情報・定期来客（区画契約の根拠か本人データか） |
| `parking_watchlist` | `user_id` | 強消去 | 本PRで追加。退会30日後の強匿名化で所有列一括DELETE（御裁可済）。parking ドメイン `ParkingWatchlistPurgeEventListener` |
| `password_reset_tokens` | `user_id` | 強消去 | `AccountPurgeService` 本体TX（auth トークン・認証情報） |
| `payment_request_payment_attempts` | `payer_user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `pending_uploads` | `user_id` | 要裁可 | 送信待ち・一時データ（宛先PIIを含む） |
| `performance_monthly_summaries` | `user_id` | 保持 | 試合・大会・成績の共同記録 |
| `performance_records` | `user_id` | 保持 | 試合・大会・成績の共同記録 |
| `period_attendance_records` | `student_user_id` / `teacher_user_id` | 保持 | 学校・出欠の業務記録（学籍・法定保存の根拠） |
| `personal_timetable_settings` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `personal_timetables` | `user_id` | 要裁可 | 本人専用の本文（既存方針は本文を対象外としたが、他者と共有しない私有本文の保持根拠は未裁定） |
| `player_appearances` | `player_user_id` | 保持 | 試合・大会・成績の共同記録 |
| `point_card_balance_events` | `operated_by_user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `point_card_groups` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `point_card_stamp_events` | `pressed_by_user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `point_card_user_settings` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `point_transactions` | `user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `presence_events` | `user_id` | 要裁可 | 本人の行動・在席記録 |
| `promotion_deliveries` | `user_id` | 要裁可 | 広告の配信・計測ログ（個人の行動履歴か、広告主課金の根拠か） |
| `property_listing_inquiries` | `user_id` | 弱消去 | 弱側 `UserAnonymizedEvent` 購読者で物理削除（発火経路は休眠中。正本 withdrawal_flow_immediate_anonymization_fix.md） |
| `property_work_history_views` | `user_id` | 強消去 | 本PRで追加。退会30日後の強匿名化で所有列一括DELETE（御裁可済）。property ドメイン `PropertyWorkHistoryViewPurgeEventListener` |
| `proxy_input_consents` | `approved_by_user_id` / `proxy_user_id` / `revoke_witnessed_by_user_id` / `subject_user_id` / `witness_user_id` | 匿名化 | `ProxyPurgeEventListener` が records を物理削除、consents を論理削除（監査証跡） |
| `proxy_input_records` | `proxy_user_id` / `subject_user_id` | 匿名化 | `ProxyPurgeEventListener` が records を物理削除、consents を論理削除（監査証跡） |
| `proxy_vote_motion_comments` | `user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `proxy_votes` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `push_subscriptions` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `queue_tickets` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `quick_memos` | `user_id` | 要裁可 | 本人専用の本文（既存方針は本文を対象外としたが、他者と共有しない私有本文の保持根拠は未裁定） |
| `ranch_admin_commands` | `actor_user_id` | 保持 | 監査ログ（改ざん防止・説明責任） |
| `ranch_affinity_units` | `user_id` | 強消去 | `RanchPurgeEventListener`（`RanchPurgeService`） |
| `ranch_care_week_budgets` | `user_id` | 強消去 | `RanchPurgeEventListener`（`RanchPurgeService`） |
| `ranch_collectible_inventory` | `user_id` | 強消去 | `RanchPurgeEventListener`（`RanchPurgeService`） |
| `ranch_commands` | `user_id` | 強消去 | `RanchPurgeEventListener`（`RanchPurgeService`） |
| `ranch_dinosaurs` | `user_id` | 強消去 | `RanchPurgeEventListener`（`RanchPurgeService`） |
| `ranch_owners` | `user_id` | 強消去 | `RanchPurgeEventListener`（`RanchPurgeService`） |
| `ranch_participation_periods` | `user_id` | 強消去 | `RanchPurgeEventListener`（`RanchPurgeService`） |
| `ranch_point_ledger` | `user_id` | 強消去 | `RanchPurgeEventListener`（`RanchPurgeService`） |
| `ranch_reward_decisions` | `user_id` | 強消去 | `RanchPurgeEventListener`（`RanchPurgeService`） |
| `ranch_room_placements` | `user_id` | 強消去 | `RanchPurgeEventListener`（`RanchPurgeService`） |
| `ranch_week_budgets` | `user_id` | 強消去 | `RanchPurgeEventListener`（`RanchPurgeService`） |
| `ranking_snapshots` | `user_id` | 保持 | 試合・大会・成績の共同記録 |
| `recall_attempts` | `user_id` | 要裁可 | 本人専用の本文（既存方針は本文を対象外としたが、他者と共有しない私有本文の保持根拠は未裁定） |
| `receipt_issuer_settings` | `default_seal_user_id` | 保持 | 印鑑・押印履歴（F05.3 の既決で保持） |
| `receipt_queue` | `recipient_user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `receipts` | `recipient_user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `recruitment_cancellation_records` | `user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `recruitment_listings` | `payee_user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `recruitment_no_show_records` | `user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `recruitment_participants` | `user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `recruitment_user_penalties` | `user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `reflection_entries` | `user_id` | 要裁可 | 本人専用の本文（既存方針は本文を対象外としたが、他者と共有しない私有本文の保持根拠は未裁定） |
| `reflection_ranch_admin_commands` | `actor_user_id` | 強消去 | `ReflectionSettingsPurgeEventListener`（想起・牧場輸送の私有行） |
| `reflection_ranch_outboxes` | `recipient_user_id` | 強消去 | `ReflectionSettingsPurgeEventListener`（想起・牧場輸送の私有行） |
| `reflection_ranch_witnesses` | `recipient_user_id` | 強消去 | `ReflectionSettingsPurgeEventListener`（想起・牧場輸送の私有行） |
| `reflection_recall_commands` | `user_id` | 強消去 | `ReflectionSettingsPurgeEventListener`（想起・牧場輸送の私有行） |
| `reflection_recall_sessions` | `user_id` | 強消去 | `ReflectionSettingsPurgeEventListener`（想起・牧場輸送の私有行） |
| `reflection_spaced_reminders` | `user_id` | 強消去 | 本PRで追加。退会30日後の強匿名化で所有列一括DELETE（御裁可済）。既存 reflection 強消去 TX に同梱 |
| `reflection_themes` | `user_id` | 要裁可 | 本人専用の本文（既存方針は本文を対象外としたが、他者と共有しない私有本文の保持根拠は未裁定） |
| `refresh_tokens` | `user_id` | 強消去 | `AccountPurgeService` 本体TX（auth トークン・認証情報） |
| `refunds` | `refunded_by_user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `registered_vehicles` | `user_id` | 要裁可 | 本人の車両情報・定期来客（区画契約の根拠か本人データか） |
| `reservation_lines` | `default_staff_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `reservation_slot_templates` | `staff_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `reservation_slots` | `staff_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `reservation_waitlist_entries` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `reservations` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `resident_activity_snapshots` | `subject_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `resident_registry` | `user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `resumes` | `user_id` | 強消去 | `ResumePurgeEventListener` |
| `return_stay_plan_owner_locks` | `owner_user_id` | 強消去 | `ReturnStayPlanLifecycleListener`（弱・強の両側で冪等削除） |
| `return_stay_plans` | `owner_user_id` | 強消去 | `ReturnStayPlanLifecycleListener`（弱・強の両側で冪等削除） |
| `safety_responses` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `schedule_attendances` | `ranch_first_self_user_id` / `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `schedule_comments` | `user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `schedule_keeps` | `user_id` | 弱消去 | 弱側で論理削除（`ScheduleKeepAnonymizationEventListener`。発火経路は休眠中） |
| `schedule_ranch_admin_commands` | `actor_user_id` | 強消去 | 本人が実行した牧場管理コマンドの冪等記録（結果JSON・本文ハッシュ）。schedule の強消去リスナー `CalendarLayerLifecycleListener`（`CalendarLayerCleanupExecutor`） が `schedule` 強消去TX内で `actor_user_id` 一致行を一括削除 |
| `schedule_ranch_outboxes` | `recipient_user_id` | 強消去 | 本人宛ての牧場配送 outbox（payload JSON を含む技術配送表）。`CalendarLayerLifecycleListener`（`CalendarLayerCleanupExecutor`） が同TX内で `recipient_user_id` 一致行を一括削除 |
| `schedule_ranch_witnesses` | `recipient_user_id` | 強消去 | 本人の牧場資格判定の証跡（内容ダイジェスト・週次）。`CalendarLayerLifecycleListener`（`CalendarLayerCleanupExecutor`） が同TX内で `recipient_user_id` 一致行を一括削除 |
| `schedule_targets` | `user_id` | 弱消去 | 弱側 `UserAnonymizedEvent` 購読者で物理削除（発火経路は休眠中。正本 withdrawal_flow_immediate_anonymization_fix.md） |
| `schedules` | `user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `scope_member_calendar_settings` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `seal_scope_defaults` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `seal_stamp_logs` | `user_id` | 保持 | 印鑑・押印履歴（F05.3 の既決で保持） |
| `search_histories` | `user_id` | 弱消去 | 弱側 `UserAnonymizedEvent` 購読者で物理削除（発火経路は休眠中。正本 withdrawal_flow_immediate_anonymization_fix.md） |
| `search_saved_queries` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `service_record_reactions` | `user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `service_records` | `member_user_id` / `staff_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `shared_file_comments` | `user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `shared_file_stars` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `shared_file_tags` | `user_id` | 保持 | 共同コンテンツ（既決の除外対象） |
| `shared_folders` | `user_id` | 保持 | 共同コンテンツ（既決の除外対象） |
| `shift_assignments` | `user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `shift_budget_consumptions` | `user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `shift_hourly_rates` | `user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `shift_requests` | `user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `shift_swap_requests` | `target_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `stripe_connect_accounts` | `user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `stripe_customers` | `user_id` | 強消去 | `PaymentPurgeEventListener` が Stripe 顧客行を削除 |
| `student_attendance_summaries` | `student_user_id` | 保持 | 学校・出欠の業務記録（学籍・法定保存の根拠） |
| `succession_covenants` | `signer_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `succession_pre_registrations` | `owner_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `supporter_applications` | `user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `survey_responses` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `survey_result_viewers` | `user_id` | 要裁可 | 既知の境界案件: 結果閲覧者の指定（調査主催者側の権限設定か本人データか） |
| `survey_targets` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `team_blocks` | `user_id` | 保持 | 通報・モデレーション証跡（被通報者・通報者の記録は運営判断の根拠） |
| `team_loyalty_accounts` | `user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `team_loyalty_award_requests` | `terminal_actor_user_id` / `user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `team_loyalty_transactions` | `actor_user_id` / `user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `team_member_info_responses` | `user_id` | 要裁可 | チームが参照する本人プロフィール・回答（PIIを含む） |
| `team_member_terms` | `user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `team_payment_advances` | `payer_user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `ticket_books` | `user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `ticket_payments` | `user_id` | 保持 | 会計・契約・決済記録（税法等の保存義務。billing は契約を解約済み） |
| `timeline_bookmarks` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `timeline_poll_votes` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `timeline_post_reactions` | `user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `timeline_posts` | `ranch_qualified_user_id` / `user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `timeline_ranch_admin_commands` | `actor_user_id` | 強消去 | 本人が実行した牧場管理コマンドの冪等記録（結果JSON・本文ハッシュ）。timeline の強消去リスナー `TimelineBookmarkAnonymizationEventListener` が `timeline` 強消去TX内で `actor_user_id` 一致行を一括削除 |
| `timeline_ranch_outboxes` | `recipient_user_id` | 強消去 | 本人宛ての牧場配送 outbox（payload JSON を含む技術配送表）。`TimelineBookmarkAnonymizationEventListener` が同TX内で `recipient_user_id` 一致行を一括削除 |
| `timeline_ranch_witnesses` | `recipient_user_id` | 強消去 | 本人の牧場資格判定の証跡（内容ダイジェスト・週次）。`TimelineBookmarkAnonymizationEventListener` が同TX内で `recipient_user_id` 一致行を一括削除 |
| `timetable_slot_user_note_attachments` | `user_id` | 保持 | 時間割メモ本文（F03.15 の既決: 定義のみ消去し本文は保持） |
| `timetable_slot_user_note_fields` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `timetable_slot_user_notes` | `user_id` | 保持 | 時間割メモ本文（F03.15 の既決: 定義のみ消去し本文は保持） |
| `todo_assignees` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `todo_comments` | `user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `todo_handoffs` | `from_user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `todo_personal_memos` | `user_id` | 要裁可 | 本人専用の本文（既存方針は本文を対象外としたが、他者と共有しない私有本文の保持根拠は未裁定） |
| `todo_shared_memo_entries` | `user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `tournament_entry_members` | `user_id` | 保持 | 試合・大会・成績の共同記録 |
| `tournament_entry_template_members` | `user_id` | 要裁可 | 既知の境界案件: 大会エントリーの雛形メンバー（雛形所有者の設定に退会者IDが残る） |
| `tournament_entry_template_staff` | `user_id` | 要裁可 | 既知の境界案件: 大会エントリーの雛形メンバー（雛形所有者の設定に退会者IDが残る） |
| `tournament_individual_rankings` | `user_id` | 保持 | 試合・大会・成績の共同記録 |
| `tournament_match_player_stats` | `user_id` | 保持 | 試合・大会・成績の共同記録 |
| `tournament_match_rosters` | `user_id` | 保持 | 試合・大会・成績の共同記録 |
| `tournament_scorekeepers` | `user_id` | 保持 | 試合・大会・成績の共同記録 |
| `translation_assignments` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `two_factor_auth` | `user_id` | 強消去 | `AccountPurgeService` 本体TX（auth トークン・認証情報） |
| `unseal_audit_views` | `viewer_user_id` | 保持 | 監査ログ（改ざん防止・説明責任） |
| `unseal_requests` | `first_approver_user_id` / `second_approver_user_id` | 保持 | 監査ログ（改ざん防止・説明責任） |
| `user_action_memo_settings` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `user_ad_delivery_counters` | `user_id` | 要裁可 | 本人専用の頻度上限カウンタ（設定に近いが計測と連動） |
| `user_ad_preferences` | `user_id` | 強消去 | 本PRで追加。退会30日後の強匿名化で所有列一括DELETE（御裁可済）。advertising ドメイン `UserAdPreferencePurgeEventListener` |
| `user_badges` | `user_id` | 要裁可 | 本人の獲得バッジ（ランキング本文か本人データか） |
| `user_blog_settings` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `user_calendar_layer_settings` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `user_calendar_sync_settings` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `user_care_links` | `care_recipient_user_id` / `watcher_user_id` | 要裁可 | 見守り関係（相手方の設定でもある） |
| `user_favorites` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `user_google_calendar_connections` | `user_id` | 弱消去 | 弱側 `UserAnonymizedEvent` 購読者で物理削除（発火経路は休眠中。正本 withdrawal_flow_immediate_anonymization_fix.md） |
| `user_ical_tokens` | `user_id` | 要裁可 | 外部連携・購読トークン（本人以外に用途がないが既存弱側の対象外） |
| `user_interest_tags` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `user_line_connections` | `user_id` | 要裁可 | 外部連携・購読トークン（本人以外に用途がないが既存弱側の対象外） |
| `user_mutes` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `user_nav_settings` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `user_permission_groups` | `user_id` | 要裁可 | 権限グループ所属（RolePurge の対象外） |
| `user_point_cards` | `user_id` | 弱消去 | 弱側 `UserAnonymizedEvent` 購読者で物理削除（発火経路は休眠中。正本 withdrawal_flow_immediate_anonymization_fix.md） |
| `user_quick_memo_settings` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `user_reflection_settings` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `user_roles` | `user_id` | 強消去 | `RolePurgeEventListener` |
| `user_schedule_google_events` | `user_id` | 要裁可 | 外部連携・購読トークン（本人以外に用途がないが既存弱側の対象外） |
| `user_social_profiles` | `user_id` | 弱消去 | 弱側で無効化（`SocialAnonymizationEventListener`。発火経路は休眠中） |
| `user_village_nicknames` | `user_id` | 強消去 | 本PRで追加。退会30日後の強匿名化で所有列一括DELETE（御裁可済）。village ドメイン `VillagePersonalSettingsPurgeEventListener`（弱側 `VillageUserCleanerEventListener` は休眠中のため強側で確実に消す） |
| `user_village_pins` | `user_id` | 強消去 | 本PRで追加。退会30日後の強匿名化で所有列一括DELETE（御裁可済）。village ドメイン `VillagePersonalSettingsPurgeEventListener`（弱側 `VillageUserCleanerEventListener` は休眠中のため強側で確実に消す） |
| `user_violations` | `user_id` | 保持 | 通報・モデレーション証跡（被通報者・通報者の記録は運営判断の根拠） |
| `user_voice_input_consents` | `user_id` | 保持 | 同意記録（同意の証跡） |
| `user_weather_locations` | `user_id` | 強消去 | CMP-260822-1243 第1陣の本人設定38親表（上表）。各domainの強消去リスナー |
| `users` | `account_created_by_watcher_user_id` | 保持 | `users` 本体は強消去で物理削除。この列は他ユーザー行が持つ見守り作成者参照 |
| `village_calendar_event_logs` | `created_by_user_id` | 保持 | 共有物の作成者・行為者参照（行は共有物そのもの） |
| `village_calendar_events` | `created_by_user_id` | 保持 | 共有物の作成者・行為者参照（行は共有物そのもの） |
| `village_charter_drafters` | `user_id` | 匿名化 | 弱側 `VillageUserCleanerEventListener` が `user_id` を NULL 化し仮名スナップショットを残す（憲章の史料） |
| `village_creation_requests` | `requester_user_id` / `reviewer_user_id` | 要裁可 | 村作成申請の申請者（設計書 §7.1 の申請者0化は未実装） |
| `village_festival_rsvps` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `village_festivals` | `created_by_user_id` | 保持 | 共有物の作成者・行為者参照（行は共有物そのもの） |
| `village_invitations` | `target_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `village_join_requests` | `requester_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `village_match_recruit_applications` | `applicant_user_id` / `reviewed_by_user_id` | 保持 | 業務・契約・申請の記録（相手方・運営が参照する） |
| `village_match_recruits` | `posted_by_user_id` | 保持 | 共有物の作成者・行為者参照（行は共有物そのもの） |
| `village_meetup_attendances` | `user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `village_meetup_comments` | `author_user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `village_meetup_todos` | `assignee_user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `village_meetup_votes` | `voter_user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `village_meetups` | `organizer_user_id` | 保持 | 共有物の作成者・行為者参照（行は共有物そのもの） |
| `village_newsletter_opt_outs` | `user_id` | 要裁可 | 村の個人化データ（配信停止・推薦・スコア） |
| `village_pilgrimage_recommendations` | `user_id` | 要裁可 | 村の個人化データ（配信停止・推薦・スコア） |
| `village_reports` | `reporter_user_id` | 保持 | 通報・モデレーション証跡（被通報者・通報者の記録は運営判断の根拠） |
| `village_representatives` | `granted_by_user_id` / `representative_user_id` / `revoked_by_user_id` | 保持 | 共有物の作成者・行為者参照（行は共有物そのもの） |
| `village_serendipity_scores` | `user_id` | 要裁可 | 村の個人化データ（配信停止・推薦・スコア） |
| `villages` | `created_by_user_id` | 保持 | 共有物の作成者・行為者参照（行は共有物そのもの） |
| `visibility_templates` | `owner_user_id` | 強消去 | `VisibilityTemplatePurgeEventListener`（退会者所有のカスタムのみ） |
| `warning_re_reviews` | `user_id` | 保持 | 通報・モデレーション証跡（被通報者・通報者の記録は運営判断の根拠） |
| `webauthn_credentials` | `user_id` | 強消去 | `AccountPurgeService` 本体TX（auth トークン・認証情報） |
| `withdraw_jobs` | `user_id` | 保持 | 退会処理そのものの運用記録 |
| `workflow_request_approvers` | `approver_user_id` | 保持 | 参加・出欠・回答・投票・担当の共同記録（他メンバーの集計・業務根拠） |
| `workflow_request_comments` | `user_id` | 保持 | 共有本文・反応（投稿は残し著者表示を共通ラベル化する既決。CMP-261011-0124） |
| `yabai_unflag_requests` | `user_id` | 保持 | 通報・モデレーション証跡（被通報者・通報者の記録は運営判断の根拠） |
<!-- PERSONAL_OWNER_TABLE_LEDGER_END -->
