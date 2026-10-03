package com.mannschaft.app.notification.confirmable.support;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.fanout.NotificationFanoutJob;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobStatus;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * CMP-260920-1040 試練B: 確認通知の非同期配信ワーカー系テスト共通フィクスチャ。
 *
 * <p>{@code users} テーブルの NOT NULL 全列を明示的に充填した利用者を、1 回の多値 INSERT で
 * 大量投入する（{@code ConfirmableNotificationScopeContractIT#insertUser} の単発版を、
 * 1,201 人規模のチャンク処理系テスト向けにバルク化したもの）。</p>
 */
public final class ConfirmableFanoutFixture {

    private ConfirmableFanoutFixture() {
    }

    /**
     * {@code count} 人の利用者を一括投入し、生成された ID を昇順で返す。
     *
     * <p>CI是正（CMP-260920-1040）: フィクスチャの投入はテストメソッド本体のトランザクション
     * （通常は無い）とは独立した、それ自体で完結するトランザクションで行う必要があるため、
     * {@link TransactionTemplate}（{@code REQUIRES_NEW}）で明示的に包む
     * （テスト本体を {@code @Transactional} にはしない。並行・順序系の試練はコミットの実在を
     * 前提にしているため）。</p>
     *
     * @param txManager   呼び出し側で {@code @Autowired} した {@link PlatformTransactionManager}
     * @param em          EntityManager
     * @param count       投入人数
     * @param emailPrefix 実行ごとに一意な接頭辞（例: {@code "cfx-" + UUID.randomUUID()}）。
     *                    末尾に {@code -<連番>@example.com} を付けてメールアドレスを作る
     * @return 生成された user.id のリスト（昇順・{@code count} 件）
     */
    @SuppressWarnings("unchecked")
    public static List<Long> insertUsers(
            PlatformTransactionManager txManager, EntityManager em, int count, String emailPrefix) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> {
            StringBuilder sql = new StringBuilder(
                    "INSERT INTO users (email, last_name, first_name, display_name, status, "
                            + "is_searchable, handle_searchable, contact_approval_required, "
                            + "online_visibility, dm_receive_from, encryption_key_version, "
                            + "locale, timezone, reporting_restricted, follow_list_visibility, "
                            + "care_notification_enabled, offline_only, created_at, updated_at) VALUES ");
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append("('").append(emailPrefix).append('-').append(i).append("@example.com', ")
                        .append("'CFX', 'テスト', 'CFXテスト', 'ACTIVE', 1, 1, 1, 'NOBODY', 'ANYONE', 1, ")
                        .append("'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())");
            }
            em.createNativeQuery(sql.toString()).executeUpdate();

            List<Object> rows = em.createNativeQuery(
                            "SELECT id FROM users WHERE email LIKE :prefix ORDER BY id")
                    .setParameter("prefix", emailPrefix + "-%")
                    .getResultList();
            List<Long> ids = new ArrayList<>(rows.size());
            for (Object o : rows) {
                ids.add(((Number) o).longValue());
            }
            return ids;
        });
    }

    /**
     * CI是正（CMP-260920-1040）: JdbcTemplate で日時カラムへ {@link LocalDateTime} を直接束縛
     * するテストのための、壁時計 → DB 格納値への変換ヘルパ。
     *
     * <p>このプロジェクトの JVM 既定タイムゾーンは Asia/Tokyo（JST）だが、Hibernate は
     * {@code hibernate.jdbc.time_zone: UTC} で動く。つまり Hibernate 経由（エンティティ保存・
     * JPQL の {@code :now} バインド）で書き込む {@code created_at} 等の日時カラムには、実際には
     * JST → UTC へ変換した値が入る。テストが {@link org.springframework.jdbc.core.JdbcTemplate}
     * で同じカラムへ直接 {@code LocalDateTime.now()}（JST の壁時計）をそのまま束縛すると、
     * Hibernate 側の読み出し・比較（{@code n.deadlineAt < :now} 等）と9時間ズレる。
     * 必ずこのヘルパを通してから {@code jdbc.update(...)} に渡すこと。</p>
     *
     * @param wallClock JVM 既定タイムゾーン（JST）の壁時計としての {@link LocalDateTime}
     * @return Hibernate が UTC として解釈する値と一致する、UTC 換算後の {@link LocalDateTime}
     */
    public static LocalDateTime toUtcColumnValue(LocalDateTime wallClock) {
        return wallClock.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** 指定した接頭辞で投入した利用者をすべて削除する（{@code @AfterEach} 用）。 */
    public static void deleteUsers(PlatformTransactionManager txManager, EntityManager em, String emailPrefix) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> em.createNativeQuery("DELETE FROM users WHERE email LIKE :prefix")
                .setParameter("prefix", emailPrefix + "-%")
                .executeUpdate());
    }

    /**
     * CMP-260920-1040 是正3: {@code ConfirmableFanoutChunkSink#finish} は §9.2 の関所として
     * {@code notification_fanout_jobs} 行を参照しジョブを DONE にする契約のため、試練が
     * {@code finish} を直接呼ぶ場合は、実在するジョブ行を先に用意してから jobId を渡す。
     *
     * @param jobRepository  ジョブリポジトリ（呼び出し側で {@code @Autowired}）
     * @param jobId          {@code finish} に渡す jobId（呼び出し側が生成した UUID）
     * @param notificationId 確認通知の親行 ID（{@code source_id} として保存する）
     */
    public static void insertFanoutJobRow(
            NotificationFanoutJobRepository jobRepository, UUID jobId, Long notificationId) {
        LocalDateTime now = LocalDateTime.now();
        jobRepository.save(NotificationFanoutJob.builder()
                .id(jobId)
                .sourceEventUuid(UUID.randomUUID())
                .scopeType("CONFIRMABLE_TARGETS")
                .scopeRef(String.valueOf(notificationId))
                .notificationType("CONFIRMABLE_NOTIFICATION_FANOUT")
                .sourceType("CONFIRMABLE_NOTIFICATION")
                .sourceId(notificationId)
                .status(NotificationFanoutJobStatus.RUNNING)
                .cursorSubjectId(0L)
                .insertedCount(0L)
                .retryCount(0)
                .nextAttemptAt(now)
                .priority(NotificationPriority.NORMAL)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }
}
