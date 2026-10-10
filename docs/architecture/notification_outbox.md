# 通知 push 予約の transactional outbox

| 項目 | 内容 |
|---|---|
| ステータス | 🟡 実装中（P1 試練まで。P1 出陣で 🟢 にする） |
| 起草 | 2026-10-10（陣立て書 `2026-10-10-notification-outbox-gungi.md` 改訂第2版。マスター裁可 Q1〜Q8 すべて推奨案） |
| 関連 | [F01.2.1 §6.7・§8.5.3](../features/F01.2.1_org_team_groups.md)、[ドメイン・DB 設計原則](domain_db_design_principles.md) 原則1・5・6・7 |

## 1. 何を解決するか

業務の書き込み（加盟の申請・承諾・招待、告知の送信など）と「通知の push を予約すること」を、**ドメインをまたがずに**同時に確定させる。

これまでは2通りの形があり、どちらにも穴があった。

| 旧来の形 | 例 | 穴 |
|---|---|---|
| 業務の tx の中で通知ドメインの `MANDATORY` メソッドを呼ぶ（ポート越し） | 申請（`TeamAffiliationNotifier#enqueue` → `NotificationFanoutJobService#enqueueInCurrentTransaction`）、6-E の告知 push | team の tx が notification の表へ書く**越境 tx**。ポートにしていても実質は越境で、D-3T は interface の先と非 `.repository` パッケージを辿らないため見逃していた |
| 業務のコミット後に通知ドメインの別 tx で登録する | 2-C の招待・承諾（`enqueueAfterCommit` → `enqueueInOwnTransaction`） | コミットと登録の間でプロセスが落ちると通知が**消える**（at-most-once） |

outbox にすると、業務の tx は**自ドメインの表に1行書くだけ**になり、通知ドメインはコミット後にそれを取り込む。業務と予約は同時に確定し（業務が巻き戻れば予約も消える）、取り込みは少なくとも1回・結果はちょうど1回になる。

## 2. 方式（Q1）

```
[送り手ドメインの tx]  業務の書き込み + <domain>_notification_outbox に1行（冪等 INSERT）+ 起こしイベント publish ── commit
                              └ AFTER_COMMIT・@Async("notification-outbox-pool") ─┐
[通知ドメインの relay（tx なしの指揮役）] ←──── 予備ポーラー 5秒 ────────────────┘
   ① source.claim()        … 送り手ドメインの REQUIRES_NEW（FOR UPDATE SKIP LOCKED → RELAYING・claim_token）
   ② ingest.ingest(依頼)   … 通知ドメインの「別 Bean」の REQUIRES_NEW（fan-out ジョブ＋文面、6-E' では宛先集合も）
   ③ source.markRelayed / markFailed … 送り手ドメインの REQUIRES_NEW（claim_token 一致のときだけ当たる）
```

- **表は送り手ドメインごとに置く**（`team_notification_outbox`、6-E' で `social_notification_outbox`）。common の単一表にしない理由: ①裁定（自ドメインに置く）②common は D-3T の判定外（`DomainPackages.isSharedDomain`）で抜け穴になる ③将来の分割で表を割り直す手間。
- **通知ドメインが SPI `NotificationOutboxSource` を持ち、送り手が実装する**（前例: `notification.fanout.FanoutRecipientSource`）。relay は全 source を順に回る（公平性。1回の claim は最大100行）。
- 送り手の書き込み口は既存のポートの形を保つ（`TeamAffiliationNotifier#enqueue` の実装を「自ドメインの outbox に1行書く」`OutboxTeamAffiliationNotifier`（`MANDATORY`、同一ドメイン）に差し替える）。呼ぶ側（`TeamOrgAffiliationCommandService` など）は変えない。

### 2.1 クラス構成

| パッケージ | クラス | 役割 |
|---|---|---|
| `common.outbox` | `OutboxStatus` | `PENDING` / `RELAYING` / `RELAYED` / `DEAD` |
| `common.outbox` | `AbstractNotificationOutboxEntity` | outbox 表に共通する列の `@MappedSuperclass`（`UuidV7Entity` 継承。時刻は `Instant`）|
| `notification.outbox` | `NotificationOutboxPayload` / `NotificationOutboxMessageKind` | 取り込みの依頼（`FANOUT` は `FanoutEnqueueCommand` 一式、`FANOUT_WITH_AUDIENCE` は宛先集合のキーと送信時のチーム ID を足す）|
| `notification.outbox` | `NotificationOutboxPayloadCodec` | 版つきの JSON 変換（§6）|
| `notification.outbox` | `NotificationOutboxSource`（SPI）/ `NotificationOutboxMessage` | claim・印付け・回収・掃除・最古の PENDING |
| `notification.outbox` | `NotificationOutboxIngestService` | 取り込み（**別 Bean・`REQUIRES_NEW`**。通知ドメインの表だけ）|
| `notification.outbox` | `NotificationOutboxRelay` | tx なしの指揮役。起こし `onAppended`・予備ポーラー `poll`・同期 `drainAll` |
| `notification.outbox` | `NotificationOutboxAppendedEvent` | 起こしのイベント（`sourceName`）|
| `notification.outbox` | `NotificationOutboxStuckRecoveryBatch` / `NotificationOutboxSweepBatch` | 回収（毎分）/ 掃除（日次）|
| `team.entity` / `team.repository` | `TeamNotificationOutboxEntity` / `TeamNotificationOutboxRepository` | team の outbox 表（`JpaRepository` 直継承）|
| `team.service` | `OutboxTeamAffiliationNotifier` | `TeamAffiliationNotifier` の実装（`MANDATORY`。outbox への冪等 INSERT と起こしの publish）|
| `team.service` | `TeamNotificationOutboxSource` | SPI 実装（各操作 `REQUIRES_NEW`、team の Repository だけ）|

`FanoutTeamAffiliationNotifier` と `TeamAffiliationNotifier#enqueueAfterCommit` は削除する（OG03）。

## 3. DDL（team。social も同型）

Flyway `V238.20261010120527__create_team_notification_outbox.sql`（major は PR の前とマージの前に origin/main の最大 major を取り直して確定する）。

```sql
CREATE TABLE team_notification_outbox (
    id                BINARY(16)        NOT NULL,
    idempotency_key   BINARY(16)        NOT NULL COMMENT 'fan-out の冪等キー（notification_fanout_jobs.source_event_uuid と同じ値）',
    message_kind      VARCHAR(32)       NOT NULL COMMENT 'FANOUT / FANOUT_WITH_AUDIENCE',
    payload_version   SMALLINT UNSIGNED NOT NULL COMMENT 'payload_json の版（reader を先、writer を後に展開する）',
    payload_json      JSON              NOT NULL,
    notification_type VARCHAR(64)       NOT NULL COMMENT '運用・監視用の写し',
    organization_id   BIGINT UNSIGNED   NULL     COMMENT 'テナント（運用用。クロスドメインFKなし）',
    status            VARCHAR(16)       NOT NULL DEFAULT 'PENDING',
    attempt_count     INT UNSIGNED      NOT NULL DEFAULT 0,
    next_attempt_at   DATETIME(6)       NOT NULL,
    claim_token       BINARY(16)        NULL     COMMENT 'claim の世代。mark はこの値が一致するときだけ当たる',
    claimed_at        DATETIME(6)       NULL,
    relayed_at        DATETIME(6)       NULL,
    dead_at           DATETIME(6)       NULL,
    last_error        VARCHAR(500)      NULL,
    created_at        DATETIME(6)       NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at        DATETIME(6)       NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    UNIQUE KEY uq_team_notification_outbox_idempotency_key (idempotency_key),
    KEY idx_team_notification_outbox_status_next_attempt_at (status, next_attempt_at),
    KEY idx_team_notification_outbox_status_claimed_at (status, claimed_at),
    KEY idx_team_notification_outbox_status_relayed_at (status, relayed_at),
    KEY idx_team_notification_outbox_status_dead_at (status, dead_at),
    KEY idx_team_notification_outbox_organization_id (organization_id),
    CONSTRAINT chk_team_notification_outbox_status CHECK (status IN ('PENDING','RELAYING','RELAYED','DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

### 3.1 書き込み
- `INSERT ... ON DUPLICATE KEY UPDATE id = id`（重複で例外を出さず、呼び出し側の tx を rollback-only にしない。F01.2.1 §6.7 と同じ理由）。同じ tx で同じキーを2回書いても1行（OB05）。
- `next_attempt_at` は書き込み時刻（すぐ claim できる）、`payload_version` は `NotificationOutboxPayloadCodec.CURRENT_VERSION`。
- 冪等キーは従来の fan-out の冪等キーと同じ値（加盟なら `UUID.nameUUIDFromBytes("F01.2.1:" + notificationType + ":" + membershipId)`）。取り込みでもそのまま `source_event_uuid` に使う。
- 同じ tx で起こしのイベント `NotificationOutboxAppendedEvent("team")` を publish する。

### 3.2 時刻
起きた瞬間なので Entity は `Instant`（`hibernate.jdbc.time_zone=UTC` で UTC の DATETIME として保存）。`LocalDateTime` のフィールドは新設しない（`DateTimeAndZoneGuardTest`）。relay・バッチは注入した `Clock` から `Instant` を取る。

### 3.3 境界（原則1・5・6・7）
- クロスドメイン FK なし（`organization_id` は運用用の写しで索引のみ）。
- 主キーは UUIDv7（`UuidV7Entity`）。
- 書き込み側の入口から届く Repository は自ドメインのものだけ。取り込みは通知ドメインの中、relay は tx を持たない（D-3T の判定で凍結増分0）。

### 3.4 `AbstractTenantAwareRepository` を使わない理由（原則7 の対象外）
outbox の Repository は `organization_id` で絞り込む問い合わせを持たない（relay は全テナントの行を状態と時刻で引く）。`organization_id` は監視・調査用の写しであり、テナント境界の判定には使わない。

## 4. relay の動き

### 4.1 取り込みの依頼（payload）
- P1 は `FANOUT` のみ: `FanoutEnqueueCommand` 一式（受信者は Worker の処理時点で解決）。
- `FANOUT_WITH_AUDIENCE`（6-E'）: 送信時の宛先チーム ID を payload に固定し、宛先集合（見出し `notification_fanout_audiences`・宛先チーム `notification_fanout_audience_teams`）とジョブを**1つの tx**で冪等に登録する。`registerAudience` の冪等 SQL 化（見出しは `INSERT ... ON DUPLICATE KEY UPDATE audience_snapshot_id = audience_snapshot_id`、宛先チームは多値 `INSERT ... ON DUPLICATE KEY UPDATE id = id`、`organization_id` の不一致は例外、デッドロックは失敗として再試行）は 6-E' で行う。

### 4.2 claim と印付け
- claim: `SELECT ... WHERE status='PENDING' AND next_attempt_at <= :now ORDER BY id LIMIT :n FOR UPDATE SKIP LOCKED` → RELAYING・新しい `claim_token`・`claimed_at=:now`（送り手の `REQUIRES_NEW`）。
- 印付け: `UPDATE ... WHERE id=:id AND status='RELAYING' AND claim_token=:token`。影響0行なら古い世代（回収後に別の relay が claim し直した）として WARN とメトリクス `stale_mark`、状態は変えない（OB08b）。
- 1回の claim は最大100行。1回の drain は claim を空になるまで繰り返し、30秒で打ち切る（OB19）。行ごとに例外を捕まえ、1行の失敗で残りを止めない（OB06a）。

### 4.3 取り込み（`NotificationOutboxIngestService`）
- relay とは**別の Bean**で `@Transactional(propagation = REQUIRES_NEW)`。起こしの executor が飽和して CallerRuns になり、AFTER_COMMIT の中で同期実行されても、既にコミット済みの業務の tx に参加して書き込みが消えることがない（OB15・OB16）。
- 冪等: outbox の UNIQUE（業務の二重書き込み）＋ `uk_fanout_idempotency`・`insertIdempotent`（二重取り込み）で「少なくとも1回の取り込み、結果はちょうど1回」（OB04・OB08b・OB09）。

### 4.4 再試行・DEAD・回収
- 取り込みの例外 → PENDING に戻し `attempt_count+1`、`next_attempt_at = now + 30秒×2^(n-1)`（上限1時間）、`last_error` を記録、`claim_token=NULL`（OB06）。業務の行は巻き戻らない。
- 10回目の失敗で DEAD と `dead_at`、メトリクス `dead`＋ERROR ログ。以後 claim されない（OB07・Q7）。管理画面からの再送は作らない。
- 回収: `claimed_at` が2分を超えた RELAYING を PENDING・`claim_token=NULL` に戻す（毎分、`@SchedulerLock(name="notificationOutboxStuckRecovery")`＋`@BatchEndpoint(name="notification-outbox-stuck-recovery")`、メトリクス `recovered`。OB08）。

### 4.5 掃除
RELAYED は `relayed_at` から7日、DEAD は `dead_at` から30日を過ぎたものを削除する（起算点は `created_at` ではない）。PENDING・RELAYING は消さない。日次、`@SchedulerLock(name="notificationOutboxSweep")`＋`@BatchEndpoint(name="notification-outbox-sweep")`（OB11）。

### 4.6 順序
保証しない（UUIDv7 の id 昇順でおおむね FIFO）。通知は互いに独立したジョブである。

## 5. 起こしとポーラー（Q6）

- 起こし: 書き込み側が tx の中で `NotificationOutboxAppendedEvent` を publish → relay の `onAppended` が `@Async("notification-outbox-pool") @TransactionalEventListener(phase = AFTER_COMMIT)` で即 drain（OB10）。
- 専用の executor `notification-outbox-pool`: core 2・max 4・キュー100 程度、拒否時は CallerRuns（捨てない・例外を投げない）、拒否をメトリクス `nudge_rejected` に数える。
- 予備ポーラー: `poll()` に `@Scheduled(fixedDelay = 5000)`・`@SchedulerLock(name = "notificationOutboxRelay", lockAtMostFor = "PT1M", lockAtLeastFor = "PT1S")`・`@BackgroundFeaturePolicy(mode = ALWAYS)`。成功のたびに `poller_last_success_epoch` を更新する。ポーラーのノードが lock を持ったまま落ちても、`lockAtMostFor` の1分で別ノードが引き継ぐ（OB12a）。
- 起こしと予備ポーラーが重なっても、`SKIP LOCKED` で行が分かれ、重なっても冪等。

## 6. payload_version の2段階展開（Q8）

- reader を先、writer を後に展開する（新しい版を読める relay を全ノードに出してから、新しい版を書く writer を出す）。
- relay は claim した行の版を `NotificationOutboxPayloadCodec#supports` で確かめ、読めない版は**失敗に数えず** PENDING のまま `next_attempt_at = now + 60秒`、メトリクス `unsupported_version`。DEAD にしない（ローリングデプロイ中に古いノードが新しい行を掴む状況を吸収する。OB20）。

## 7. 遅延の目標・メトリクス・試験（Q6）

| 状況 | 目標 |
|---|---|
| 無負荷で起こしが効く | p95 ≤ 1秒（＋既存 fan-out Worker の周期）|
| 起こしが失敗 | ≤ 5秒＋処理時間 |
| ポーラーのノードが落ちる | ≤ 1分＋5秒 |
| 滞留時の処理量 | ≥ 50行/秒 |

- メトリクス（prefix `mannschaft.notification.outbox.`、tag `source`）: カウンタ `relayed`・`failed`・`dead`・`recovered`・`stale_mark`・`unsupported_version`・`nudge_rejected`、ゲージ `oldest_pending_age_seconds{source}`（起動時に source ごとに登録し、読まれた時点の最古の PENDING の経過秒。無ければ0。OB12b）、`poller_last_success_epoch`。
- 警報: 「最古の PENDING が60秒超の状態が5分続く」。
- 試験プロファイルでは起こしを止める（`mannschaft.notification.outbox.nudge-enabled=false`）。予備ポーラーは test で `@EnableScheduling` が無効なので走らない。IT は `NotificationOutboxRelay#drainAll()` を同期で呼ぶ。起こしを検証する IT だけ `@TestPropertySource` で有効にする。

## 8. 番人 D-3P（`CrossDomainMandatoryPropagationArchTest`）

| 番人 | 禁止すること | P1 時点の実測 |
|---|---|---|
| D-3P-1 | ドメイン X のクラスが、ドメイン Y（≠X）の `MANDATORY` メソッドを直接呼ぶ（呼ぶ側・呼ばれる側が common なら対象外）| 0件（`FanoutTeamAffiliationNotifier` の削除後）|
| D-3P-2 | ドメイン Y の `MANDATORY` メソッドが、ドメイン X（≠Y・common 以外）の interface のメソッドを実装する（逆向きポート）| 監査済みの1件（`OrganizationAffiliationPortAdapter#lockForAffiliation`。加盟申請の直列化に組織行の行ロックが team の tx 内で必要。書き込みはしない。Q4）|
| D-3P-3 | notification 以外から `NotificationFanoutJobService#enqueueInCurrentTransaction*`・`#enqueueInOwnTransaction`・`NotificationFanoutAudienceService`・`NotificationOutboxIngestService` へ依存する | 0件 |

- **MANDATORY の判定は実効値**: メソッド → 宣言クラス（`@Inherited` なので親クラスの宣言も含む）→ 継承元の同じシグネチャのメソッド → その宣言型（Spring の `AnnotationTransactionAttributeSource` の探索順）。
- **検出範囲は「直接のメソッド呼び出しと interface の実装関係」だけ**。イベント経由（`@EventListener`・`@TransactionalEventListener`）、`REQUIRED` や無印での参加、リフレクション、動的ディスパッチは対象外（`REQUIRED` の越境は D-3T が Repository への到達で見る）。
- 凍結ストアを使わない。既存の違反は理由つきの監査済み例外だけで、監査済み例外が実在しなくなると赤になる（台帳の腐り防止）。
- 検出の陽性・陰性は検体（test ソースの `*.mandatoryport`）で `CrossDomainMandatoryPropagationGuardConditionTest` が固定する（OG01・OG02）。
- ingest が独立した tx であることは静的には見ず、契約テスト（OB15・OB16）で確かめる。

## 9. 範囲外（今回やらない）

| 項目 | 理由・扱い |
|---|---|
| 確認通知 `CommitteeDistributionService#distribute`・`PaymentRequestService#send` → `ConfirmableNotificationService#send`（REQUIRED）| 既存の監査済み例外（`NotificationTransactionBoundaryGuardTest` の `AUDITED_EXCEPTIONS`、D-3T 凍結ストアに記載）|
| family → notification（`EventCareNotificationTriggerListener` → `CareEventNotificationService` → `NotificationService`）| 既存の監査済み例外（`AUDITED_EXCEPTIONS`・凍結リスト）|
| team → auth の監査（`TeamAffiliationAuditRecorder` → `AuditLogService.recordSync`。呼び出し側 tx に参加）| Q4。別の小 PR でコミット後へ移す |
| team → organization の行ロック（`lockForAffiliation`）| Q4。直列化に必要なので D-3P-2 の監査済み例外 |
| メール outbox `EmailOutboxServiceImpl#enqueue`（REQUIRED、20か所以上）| Q5。台帳を起こす |
| D-3T の Repository 判定（パッケージ名 `.repository`）の穴 | Q5。判定を「Spring Data Repository 継承型」に改める別 PR で凍結増分を実測 |
| AFTER_COMMIT リスナーからの `enqueue`（REQUIRES_NEW）| 越境 tx ではない |
| 照合バッチ（招待のコミットから outbox 書き込みまでの間に落ちた分の回収）| Q3。作らない（§10）|
| DEAD の管理画面からの再送 | Q7。作らない |

## 10. 2-C（招待・承諾）の扱い（Q3・C 案）

- 招待: コミット後の組織状態の読み直し（閉鎖なら招待を取り下げる。F01.2.1 §6.9）を**通過した後**に、team の別の tx（`TeamOrgInviteCommandService#appendInviteReceivedNotice`）で、招待の行が PENDING のまま残っていることを `FOR UPDATE` で確かめてから `TeamAffiliationNotifier#enqueue` で outbox に書く（OB13a・OB13b・OB13c）。
- 承諾: `accept` の tx の中で outbox に書く（承諾と通知の予約が同時に確定する。OB14）。
- 招待のコミットから outbox への書き込みまでの間にプロセスが落ちると、招待の通知が消える余地は今と同じ程度に残る（照合バッチは作らない）。
- 退けた案: A（現状維持）、B（同一 tx で書き取り込み時に判定。取り下げとの競合が残る）、D（組織の行をロックして直列化。§6.5 の方針と D-3P-2 に反する）。

## 11. 段取り

1. P0（本書・F01.2.1 の改訂）＋ P1 試練（red）— 本 PR。
2. P1 出陣（同じブランチで green 化）: common.outbox・notification.outbox（SPI・ingest・relay・バッチ・executor）・team outbox・`OutboxTeamAffiliationNotifier` への差し替えと `FanoutTeamAffiliationNotifier` の削除・2-C の C 案・D-3P。
3. 6-E'（social）: `social_notification_outbox`、`SocialNotificationOutboxSource`、`AnnouncementPushEnqueuer` の実装の差し替え、`FANOUT_WITH_AUDIENCE`、AC-H21 の再定義（F01.2.1 §16）。
4. 2-B2・2-D: P1 の後に追従（本体コード不変、IT に drain を挟む）。

## 12. 変更履歴

| 日付 | 内容 |
|---|---|
| 2026-10-10 | 初版（陣立て書 改訂第2版・マスター裁可 Q1〜Q8）。P1 試練と同時に作成 |
