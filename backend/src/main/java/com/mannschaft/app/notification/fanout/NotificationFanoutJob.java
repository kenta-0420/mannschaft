package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * 通知 fan-out 耐久ジョブ（P2）。
 *
 * <p>村行事作成などの「入口」は受信者を展開せず本ジョブ 1 行だけを {@code enqueue} し（O(1)・AC-7）、
 * 裏ワーカー {@link NotificationFanoutWorker} が {@code cursor_subject_id} を進めながら受信者をチャンク配信する。
 * プロセスがクラッシュしても {@code status=PENDING} と {@code cursor_subject_id} から再開でき、欠落も重複も出さない
 * （AC-2）。DDL は {@code V173.__create_notification_fanout_jobs.sql}。</p>
 *
 * <h2>ユニーク制約（冪等・AC-1）</h2>
 * <p>{@code (scope_type, scope_ref, notification_type, source_event_uuid)} を複合ユニークにし、同一 fan-out の
 * 二重 enqueue を DB レベルで拒否する。{@code @Table} にも宣言し、test プロファイル（{@code ddl-auto=create}・
 * Flyway 無効）の Entity 由来スキーマでも同じ制約が効くようにする。</p>
 *
 * <p>主キーは {@link UuidV7Entity}（UUIDv7・原則6）。クロスドメイン FK は持たない（原則1）。</p>
 */
@Entity
@Table(
        name = "notification_fanout_jobs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_fanout_idempotency",
                columnNames = {
                        "scope_type", "scope_ref", "notification_type", "source_event_uuid", "shard_index"
                }),
        indexes = @Index(name = "idx_fanout_status_next", columnList = "status, next_attempt_at"))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class NotificationFanoutJob extends UuidV7Entity {

    @Column(name = "source_event_uuid", nullable = false, columnDefinition = "BINARY(16)")
    private java.util.UUID sourceEventUuid;

    /** 受信者解決の戦略キー（{@link FanoutRecipientSource#scopeType()} と一致）。 */
    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    /**
     * 受信者解決に渡す多型スコープ参照（論理参照・FK なし）。
     * 村＝UUID 文字列 / チーム・組織＝ID 文字列。{@link FanoutRecipientSource#nextPage} 実装が型を復元する。
     */
    @Column(name = "scope_ref", nullable = false, length = 36)
    private String scopeRef;

    @Column(name = "notification_type", nullable = false, length = 50)
    private String notificationType;

    /** テナント（論理参照・FK なし・SYSTEM 通知は NULL）。 */
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", length = 1000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    private com.mannschaft.app.notification.NotificationPriority priority;

    @Column(name = "source_type", length = 50)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "action_url", length = 500)
    private String actionUrl;

    @Column(name = "actor_id")
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NotificationFanoutJobStatus status;

    /** キーセット再開カーソル（処理済み受信者 subject_id 上端）。クラッシュ再開の要（AC-2）。 */
    @Column(name = "cursor_subject_id", nullable = false)
    private long cursorSubjectId;

    /** 生成済み通知行数（可観測性・再開補助）。 */
    @Column(name = "inserted_count", nullable = false)
    private long insertedCount;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    /** 次回実行時刻（enqueue 時＝now。リトライで指数バックオフ）。 */
    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    /**
     * Wave-2（ORG スコープ耐久 fan-out）: SUPPORTER（応援者）を配信対象に含めるか。
     *
     * <p>{@code DEFAULT TRUE}（V175）で既存 VILLAGE 行の後方互換を保つ（旧経路は応援者も含め全員配信のため）。
     * enqueue 経由で {@code NotificationFanoutJobService} からこの列に値が渡され、ジョブに保存される。</p>
     */
    @Builder.Default
    @Column(name = "include_supporters", nullable = false)
    private Boolean includeSupporters = Boolean.TRUE;

    /**
     * CMP-001⑤（ワーカー並列化）: このジョブが属するシャード番号（0始まり）。
     *
     * <p>{@code DEFAULT 0}（V176）で既存行・既存経路（単一ワーカー担当）の後方互換を保つ。
     * enqueue 時のシャード算出ロジックは本フィールド追加時点では未実装（出陣-3 担当）。</p>
     */
    @Builder.Default
    @Column(name = "shard_index", nullable = false)
    private short shardIndex = 0;

    /**
     * CMP-001⑤（ワーカー並列化）: enqueue 時点の総シャード数。
     *
     * <p>{@code DEFAULT 1}（V176）で既存行・既存経路（シャーディング未使用）の後方互換を保つ。</p>
     */
    @Builder.Default
    @Column(name = "shard_count", nullable = false)
    private short shardCount = 1;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
