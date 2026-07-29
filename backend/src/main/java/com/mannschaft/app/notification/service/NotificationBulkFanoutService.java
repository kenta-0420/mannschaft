package com.mannschaft.app.notification.service;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通知 fan-out 抜本改修 P1: 受信者チャンクを<b>バルク INSERT ＋ チャンクコミット</b>で作成し、
 * 専用プールで一括配信する書き込みファサード。
 *
 * <h2>なぜ JdbcTemplate 多値 INSERT か</h2>
 * <p>{@code notifications.id} は {@code IDENTITY}（AUTO_INCREMENT）採番であり、Hibernate は IDENTITY 戦略の
 * エンティティに対して JDBC バッチ INSERT を無効化する（挿入直後に生成キーを得るため 1 行ずつ発行せざるを得ない）。
 * その結果 {@code saveAll} でも受信者数ぶんの INSERT 文が発行され、50 万人配信で INSERT 文数が線形に膨らむ。
 * これを断つため、本サービスは JPA を迂回して {@link JdbcTemplate} で <b>1 文＝複数行</b>の多値 INSERT を発行する。
 * 発行文数は受信者数でなく<b>チャンク数</b>に比例する（AC-9）。多値 INSERT でも auto_increment の採番 id を
 * {@link GeneratedKeyHolder} で取得して配信エンティティへ戻し、リアルタイム配信ペイロードに id を載せる。</p>
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

        // INSERT 値の素材となる通知（id は未採番＝null）。バルク INSERT 後に DB 採番 id を戻す。
        List<NotificationEntity> seeds = new ArrayList<>(recipients.size());
        for (Long userId : recipients) {
            seeds.add(NotificationEntity.builder()
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

        // --- チャンク単位バルク INSERT（1 文で複数行・1 トランザクション）＋生成キー取得 ---
        List<Long> generatedIds = chunkTxTemplate.execute(status -> bulkInsertReturningIds(seeds));

        // --- 採番 id を配信エンティティへ戻す（リアルタイム配信ペイロードに DB 採番 id を載せる）---
        // WebSocket/Push クライアントは id が数値であることを前提にフレームを取り込むため、
        // id 欠落のまま配信すると DB 行は正しく作られていてもリアルタイム配信だけが無効化される。
        List<NotificationEntity> toDispatch = attachGeneratedIds(seeds, generatedIds);

        // --- 専用プールで一括配信（@Async・N+1 消滅済みチャンク配信）---
        dispatchService.dispatchBatch(toDispatch);
    }

    /**
     * 多値 INSERT を 1 文で発行し、auto_increment で採番された id を<b>挿入順</b>で返す（AC-9 のバルク性は不変）。
     *
     * <p>MySQL Connector/J は多値 INSERT でも {@code getGeneratedKeys()} で全行ぶんの採番 id を挿入順に返す。
     * {@link GeneratedKeyHolder#getKeyList()} でそれを受け取り、各行に対応させる。</p>
     */
    private List<Long> bulkInsertReturningIds(List<NotificationEntity> entities) {
        StringBuilder sqlBuilder = new StringBuilder(
                "INSERT INTO notifications (" + INSERT_COLUMNS + ") VALUES ");
        Object[] args = new Object[entities.size() * COLS_PER_ROW];
        int a = 0;
        for (int i = 0; i < entities.size(); i++) {
            if (i > 0) {
                sqlBuilder.append(',');
            }
            sqlBuilder.append(ROW_PLACEHOLDERS);
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
        final String sql = sqlBuilder.toString();

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);

        List<Map<String, Object>> keyRows = keyHolder.getKeyList();
        List<Long> ids = new ArrayList<>(keyRows.size());
        for (Map<String, Object> row : keyRows) {
            Object value = row.get("GENERATED_KEY");
            if (value == null && !row.isEmpty()) {
                // ドライバによりキー列名が異なる場合に備え、単一列の値をそのまま採る。
                value = row.values().iterator().next();
            }
            ids.add(value == null ? null : ((Number) value).longValue());
        }
        return ids;
    }

    /**
     * 採番 id を配信エンティティへ戻す。id と行が 1:1 対応する場合は {@code toBuilder().id(...)} で id を載せた
     * 新インスタンスを返す。想定外に採番数が挿入行数と一致しない場合は、配信を止めるより id なしで配信を優先し
     * （欠落より配信）、その旨を警告として可視化する。
     */
    private List<NotificationEntity> attachGeneratedIds(List<NotificationEntity> seeds, List<Long> generatedIds) {
        if (generatedIds == null || generatedIds.size() != seeds.size()) {
            log.warn("バルク INSERT の採番 id 数が挿入行数と一致しない: keys={}, rows={}。id なしで配信する",
                    generatedIds == null ? "null" : generatedIds.size(), seeds.size());
            return seeds;
        }
        List<NotificationEntity> withId = new ArrayList<>(seeds.size());
        for (int i = 0; i < seeds.size(); i++) {
            Long id = generatedIds.get(i);
            withId.add(id == null ? seeds.get(i) : seeds.get(i).toBuilder().id(id).build());
        }
        return withId;
    }
}
