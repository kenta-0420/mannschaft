package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.dto.AuditLogResponse;
import com.mannschaft.app.auth.entity.AuditLogEntity;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.PagedResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 監査ログサービス。書き込みは非同期 fire-and-forget、参照は SYSTEM_ADMIN 専用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final AccessControlService accessControlService;
    private final JdbcTemplate jdbcTemplate;

    // ─────────────────────────────────────────────
    // 書き込み（非同期・fire-and-forget）
    // ─────────────────────────────────────────────

    /**
     * 監査ログを非同期で記録する。失敗してもメイン処理を止めない。
     *
     * @param eventType      イベント種別
     * @param userId         操作ユーザーID（null可: バッチ処理）
     * @param targetUserId   対象ユーザーID（null可）
     * @param teamId         チームコンテキスト（null可）
     * @param organizationId 組織コンテキスト（null可）
     * @param ipAddress      操作元IPアドレス（null可）
     * @param userAgent      操作元UserAgent（null可）
     * @param sessionHash    SHA-256(refresh_token_jti)（null可）
     * @param metadata       イベント固有補足情報JSON文字列（null可）
     */
    @Async
    public void record(String eventType, Long userId, Long targetUserId,
                       Long teamId, Long organizationId,
                       String ipAddress, String userAgent, String sessionHash,
                       String metadata) {
        try {
            AuditLogEntity entity = AuditLogEntity.builder()
                    .eventType(eventType)
                    .userId(userId)
                    .targetUserId(targetUserId)
                    .teamId(teamId)
                    .organizationId(organizationId)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .sessionHash(sessionHash)
                    .metadata(metadata)
                    .build();
            auditLogRepository.save(entity);
        } catch (Exception e) {
            log.error("監査ログ書き込み失敗: eventType={}, userId={}", eventType, userId, e);
        }
    }

    // ─────────────────────────────────────────────
    // SYSTEM_ADMIN 向け全ログ参照（オフセットページング）
    // ─────────────────────────────────────────────

    /**
     * 監査ログ一覧を取得する（SYSTEM_ADMIN のみ）。
     *
     * @param requestUserId  リクエストユーザーID（SYSTEM_ADMIN チェック用）
     * @param filterUserId   絞り込みユーザーID（null可）
     * @param filterTargetId 絞り込み対象ユーザーID（null可）
     * @param filterTeamId   絞り込みチームID（null可）
     * @param filterOrgId    絞り込み組織ID（null可）
     * @param eventTypes     絞り込みイベント種別リスト（null可）
     * @param sessionHash    セッションハッシュ完全一致（null可）
     * @param from           開始日時（null可）
     * @param to             終了日時（null可）
     * @param page           ページ番号（0始まり）
     * @param size           ページサイズ（最大100）
     */
    @Transactional(readOnly = true)
    public PagedResponse<AuditLogResponse> getAdminLogs(
            Long requestUserId,
            Long filterUserId, Long filterTargetId, Long filterTeamId, Long filterOrgId,
            List<String> eventTypes, String sessionHash,
            LocalDateTime from, LocalDateTime to,
            int page, int size) {

        accessControlService.checkSystemAdmin(requestUserId);

        int safeSize = Math.min(size, 100);
        int offset = page * safeSize;

        // 動的 WHERE 句の構築
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (filterUserId != null) {
            where.append(" AND user_id = ?");
            params.add(filterUserId);
        }
        if (filterTargetId != null) {
            where.append(" AND target_user_id = ?");
            params.add(filterTargetId);
        }
        if (filterTeamId != null) {
            where.append(" AND team_id = ?");
            params.add(filterTeamId);
        }
        if (filterOrgId != null) {
            where.append(" AND organization_id = ?");
            params.add(filterOrgId);
        }
        if (eventTypes != null && !eventTypes.isEmpty()) {
            String placeholders = "?,".repeat(eventTypes.size());
            where.append(" AND event_type IN (")
                 .append(placeholders, 0, placeholders.length() - 1)
                 .append(")");
            params.addAll(eventTypes);
        }
        if (sessionHash != null && !sessionHash.isBlank()) {
            where.append(" AND session_hash = ?");
            params.add(sessionHash);
        }
        if (from != null) {
            where.append(" AND created_at >= ?");
            params.add(from);
        }
        if (to != null) {
            where.append(" AND created_at <= ?");
            params.add(to);
        }

        String countSql = "SELECT COUNT(*) FROM audit_logs" + where;
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        long totalElements = total != null ? total : 0;
        int totalPages = (int) Math.ceil((double) totalElements / safeSize);

        String dataSql = "SELECT id, user_id, target_user_id, team_id, organization_id,"
                + " event_type, ip_address, user_agent, session_hash, metadata, created_at"
                + " FROM audit_logs" + where
                + " ORDER BY created_at DESC, id DESC"
                + " LIMIT ? OFFSET ?";

        List<Object> dataParams = new ArrayList<>(params);
        dataParams.add(safeSize);
        dataParams.add(offset);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataSql, dataParams.toArray());
        List<AuditLogResponse> data = rows.stream().map(this::mapRow).toList();

        return PagedResponse.of(data, new PagedResponse.PageMeta(totalElements, page, safeSize, totalPages));
    }

    // ─────────────────────────────────────────────
    // 本人向けログ参照（カーソルページング）
    // ─────────────────────────────────────────────

    /**
     * 自分の監査ログ一覧をカーソルベースで取得する。
     *
     * @param userId     ログインユーザーID
     * @param eventTypes 絞り込みイベント種別リスト（null可）
     * @param from       開始日時（null可）
     * @param to         終了日時（null可）
     * @param cursor     カーソル（前ページ末尾の id 文字列。null で先頭から）
     * @param limit      取得件数（最大50）
     */
    @Transactional(readOnly = true)
    public List<AuditLogResponse> getMyLogs(
            Long userId, List<String> eventTypes,
            LocalDateTime from, LocalDateTime to,
            String cursor, int limit) {

        int safeLimit = Math.min(limit, 50);
        Long cursorId = (cursor != null && !cursor.isBlank()) ? Long.parseLong(cursor) : null;

        StringBuilder where = new StringBuilder(" WHERE user_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(userId);

        if (cursorId != null) {
            where.append(" AND id < ?");
            params.add(cursorId);
        }
        if (eventTypes != null && !eventTypes.isEmpty()) {
            String placeholders = "?,".repeat(eventTypes.size());
            where.append(" AND event_type IN (")
                 .append(placeholders, 0, placeholders.length() - 1)
                 .append(")");
            params.addAll(eventTypes);
        }
        if (from != null) {
            where.append(" AND created_at >= ?");
            params.add(from);
        }
        if (to != null) {
            where.append(" AND created_at <= ?");
            params.add(to);
        }

        String sql = "SELECT id, user_id, target_user_id, team_id, organization_id,"
                + " event_type, ip_address, user_agent, session_hash, metadata, created_at"
                + " FROM audit_logs" + where
                + " ORDER BY id DESC"
                + " LIMIT ?";
        params.add(safeLimit);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
        return rows.stream().map(this::mapRow).toList();
    }

    // ─────────────────────────────────────────────
    // ソース別ログ参照（Phase 4-α: 行動メモ監査折りたたみUI用）
    // ─────────────────────────────────────────────

    /**
     * metadata の JSON に {@code "source":"<source>"} かつ {@code "source_id":<sourceId>} を含む
     * 監査ログを最新 {@code limit} 件返す。
     *
     * <p>呼び出し元（ActionMemoService）でアクセス権チェック済みの前提。</p>
     *
     * @param source   ソース種別文字列（例: "ACTION_MEMO"）
     * @param sourceId ソース ID（メモ ID 等）
     * @param limit    最大取得件数（最大 50）
     */
    @Transactional(readOnly = true)
    public List<AuditLogResponse> findBySourceAndSourceId(String source, Long sourceId, int limit) {
        int safeLimit = Math.min(limit, 50);
        String sql = "SELECT id, user_id, target_user_id, team_id, organization_id,"
                + " event_type, ip_address, user_agent, session_hash, metadata, created_at"
                + " FROM audit_logs"
                + " WHERE JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.source')) = ?"
                + "   AND JSON_EXTRACT(metadata, '$.source_id') = ?"
                + " ORDER BY id DESC"
                + " LIMIT ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, source, sourceId, safeLimit);
        return rows.stream().map(this::mapRow).toList();
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private AuditLogResponse mapRow(Map<String, Object> row) {
        return AuditLogResponse.builder()
                .id(toLong(row.get("id")))
                .userId(toLong(row.get("user_id")))
                .targetUserId(toLong(row.get("target_user_id")))
                .teamId(toLong(row.get("team_id")))
                .organizationId(toLong(row.get("organization_id")))
                .eventType((String) row.get("event_type"))
                .ipAddress((String) row.get("ip_address"))
                .userAgent((String) row.get("user_agent"))
                .sessionHash((String) row.get("session_hash"))
                .metadata((String) row.get("metadata"))
                .createdAt(toLocalDateTime(row.get("created_at")))
                .build();
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        return ((Number) value).longValue();
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime ldt) return ldt;
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return null;
    }
}
