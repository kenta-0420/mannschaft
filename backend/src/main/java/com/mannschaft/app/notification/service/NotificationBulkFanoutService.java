package com.mannschaft.app.notification.service;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 通知 fan-out 抜本改修 P1: 受信者チャンクを<b>バルク INSERT ＋ チャンクコミット</b>で作成し、
 * 専用プールで一括配信する書き込みファサード。
 *
 * <h2>なぜ JdbcTemplate 多値 INSERT か</h2>
 * <p>{@code notifications.id} は {@code IDENTITY}（AUTO_INCREMENT）採番であり、Hibernate は IDENTITY 戦略の
 * エンティティに対して JDBC バッチ INSERT を無効化する（挿入直後に生成キーを得るため 1 行ずつ発行せざるを得ない）。
 * その結果 {@code saveAll} でも受信者数ぶんの INSERT 文が発行され、50 万人配信で INSERT 文数が線形に膨らむ。
 * これを断つため、本サービスは JPA を迂回して {@link JdbcTemplate} で <b>1 文＝複数行</b>の多値 INSERT を発行する
 * （生成キーは後続で未使用のため取得しない）。発行文数は受信者数でなく<b>チャンク数</b>に比例する（AC-9）。</p>
 *
 * <h2>チャンクコミット（AC-7 の同期ブロック解消の要）</h2>
 * <p>1 チャンク＝1 トランザクション（{@link TransactionTemplate} の {@code REQUIRES_NEW}）で確定する。
 * 50 万行を単一の長時間トランザクションに束ねると、コミットまでロック・undo ログ・レプリケーション遅延が
 * 積み上がり、村行事作成 API を長時間ブロックする。チャンクごとに独立コミットすることで、呼び出し側の
 * ストリーム（キーセットページング）と噛み合い、メモリもロック保持時間も有界になる。</p>
 *
 * <h2>per-row 意味論の保持（AC-4）</h2>
 * <p>多値 INSERT でも {@code is_read=0} / {@code snoozed_until=NULL} / {@code scope_type} / 受信者ごと一意の
 * {@code user_id} など per-row 既定を現行（JPA {@code save}）と同一に充填する。{@code organization_id} も
 * 列に含めて充填する（シャード布石 4-B・AC-12。現行の {@code createNotificationPreAuthorized} 相当で
 * 未指定時は NULL）。</p>
 */
@Slf4j
@Service
public class NotificationBulkFanoutService {

    /**
     * notifications への多値 INSERT の列並び。{@code id} は AUTO_INCREMENT のため列に含めない
     * （現行挙動と同じく DB 採番）。{@code read_at} / {@code channels_sent} / {@code snoozed_until} は
     * 現行同様 NULL（列に含めない＝既定 NULL）。SQL 文字列に {@code insert into notifications} を含めることで
     * データソース層の計測（AC-9）が JPA/JdbcTemplate を問わず同一物差しで数えられる。
     */
    private static final String INSERT_COLUMNS =
            "user_id, organization_id, notification_type, priority, title, body, "
            + "source_type, source_id, scope_type, scope_id, action_url, actor_id, is_read, created_at";
    private static final String ROW_PLACEHOLDERS = "(?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final int COLS_PER_ROW = 14;

    private final JdbcTemplate jdbcTemplate;
    private final NotificationDispatchService dispatchService;
    private final TransactionTemplate chunkTxTemplate;

    public NotificationBulkFanoutService(JdbcTemplate jdbcTemplate,
                                         NotificationDispatchService dispatchService,
                                         PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.dispatchService = dispatchService;
        // 1 チャンク＝独立トランザクション。呼び出し側の tx 文脈に依存せず必ず自前でコミットする。
        this.chunkTxTemplate = new TransactionTemplate(transactionManager);
        this.chunkTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 受信者チャンク（呼び出し側でチャンクサイズに刻み済み・null 除去済み）を、
     * 1 トランザクションでバルク INSERT し、専用プールで一括配信する。
     *
     * @param recipients       受信者 user_id チャンク（非 null・非空・null 要素を含まないこと）
     * @param notificationType 通知種別
     * @param priority         優先度
     * @param title            タイトル
     * @param body             本文
     * @param sourceType       ソース種別
     * @param sourceId         ソースID（NULL 可）
     * @param scopeType        通知スコープ種別
     * @param scopeId          通知スコープID（NULL 可）
     * @param actionUrl        アクションURL
     * @param actorId          実行者ID（NULL 可）
     * @param organizationId   組織ID（NULL 可・テナント絞り込み布石）
     */
    public void insertAndDispatchChunk(List<Long> recipients,
                                       String notificationType, NotificationPriority priority,
                                       String title, String body,
                                       String sourceType, Long sourceId,
                                       NotificationScopeType scopeType, Long scopeId,
                                       String actionUrl, Long actorId, Long organizationId) {
        if (recipients == null || recipients.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        NotificationPriority effectivePriority = priority == null ? NotificationPriority.NORMAL : priority;

        // 配信に渡す通知（id は未採番＝null。P1 では生成キー未使用）。INSERT の値もこの並びから生成する。
        List<NotificationEntity> entities = new ArrayList<>(recipients.size());
        for (Long userId : recipients) {
            entities.add(NotificationEntity.builder()
                    .userId(userId)
                    .organizationId(organizationId)
                    .notificationType(notificationType)
                    .priority(effectivePriority)
                    .title(title)
                    .body(body)
                    .sourceType(sourceType)
                    .sourceId(sourceId)
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .actionUrl(actionUrl)
                    .actorId(actorId)
                    .isRead(false)
                    .createdAt(now)
                    .build());
        }

        // --- チャンク単位バルク INSERT（1 文で複数行・1 トランザクション）---
        chunkTxTemplate.executeWithoutResult(status -> bulkInsert(entities));

        // --- 専用プールで一括配信（@Async・N+1 消滅済みチャンク配信）---
        dispatchService.dispatchBatch(entities);
    }

    /** 多値 INSERT を 1 文で発行する（発行文数はチャンク数に比例＝受信者数に線形でない・AC-9）。 */
    private void bulkInsert(List<NotificationEntity> entities) {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO notifications (" + INSERT_COLUMNS + ") VALUES ");
        Object[] args = new Object[entities.size() * COLS_PER_ROW];
        int a = 0;
        for (int i = 0; i < entities.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append(ROW_PLACEHOLDERS);
            NotificationEntity e = entities.get(i);
            args[a++] = e.getUserId();
            args[a++] = e.getOrganizationId();
            args[a++] = e.getNotificationType();
            args[a++] = e.getPriority() == null ? null : e.getPriority().name();
            args[a++] = e.getTitle();
            args[a++] = e.getBody();
            args[a++] = e.getSourceType();
            args[a++] = e.getSourceId();
            args[a++] = e.getScopeType() == null ? null : e.getScopeType().name();
            args[a++] = e.getScopeId();
            args[a++] = e.getActionUrl();
            args[a++] = e.getActorId();
            args[a++] = Boolean.FALSE;          // is_read: per-row 既定（AC-4）
            args[a++] = e.getCreatedAt();        // created_at: @PrePersist を迂回するため明示充填
        }
        jdbcTemplate.update(sql.toString(), args);
    }
}
