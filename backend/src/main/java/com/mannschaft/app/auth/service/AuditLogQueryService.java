package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuditEventCategory;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.AuditLogErrorCode;
import com.mannschaft.app.auth.dto.AuditLogResponse;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.PagedResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 監査ログ参照サービス（読み込み専用）。
 *
 * <p>SYSTEM_ADMIN 向け全ログ参照（オフセット）、本人向けカーソル参照、
 * チーム/組織 ADMIN 向けスコープ付きカーソル参照、ソース別参照を提供する。
 * 書き込み（非同期記録）は {@link AuditLogService} 側に置く。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditLogQueryService {

    private final AccessControlService accessControlService;
    private final JdbcTemplate jdbcTemplate;

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
     * @param eventCategories 絞り込みイベントカテゴリリスト（null可）。種別リストに OR でマージされる
     * @param sessionHash    セッションハッシュ完全一致（null可）
     * @param from           開始日時（null可）
     * @param to             終了日時（null可）
     * @param page           ページ番号（0始まり）
     * @param size           ページサイズ（最大100）
     */
    public PagedResponse<AuditLogResponse> getAdminLogs(
            Long requestUserId,
            Long filterUserId, Long filterTargetId, Long filterTeamId, Long filterOrgId,
            List<String> eventTypes, List<AuditEventCategory> eventCategories, String sessionHash,
            LocalDateTime from, LocalDateTime to,
            int page, int size) {

        accessControlService.checkSystemAdmin(requestUserId);

        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(AuditLogErrorCode.INVALID_DATE_RANGE);
        }

        int safeSize = Math.min(size, 100);
        int offset = page * safeSize;

        // カテゴリから対応するイベント種別を解決して eventTypes にマージ
        List<String> mergedEventTypes = resolveEventTypes(eventTypes, eventCategories);

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
        if (mergedEventTypes != null && !mergedEventTypes.isEmpty()) {
            String placeholders = "?,".repeat(mergedEventTypes.size());
            where.append(" AND event_type IN (")
                 .append(placeholders, 0, placeholders.length() - 1)
                 .append(")");
            params.addAll(mergedEventTypes);
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
     * @param userId          ログインユーザーID
     * @param eventTypes      絞り込みイベント種別リスト（null可）
     * @param eventCategories 絞り込みイベントカテゴリリスト（null可）。種別リストに OR でマージされる
     * @param from            開始日時（null可）
     * @param to              終了日時（null可）
     * @param cursor          カーソル（前ページ末尾の id 文字列。null で先頭から）
     * @param limit           取得件数（最大50）
     * @return カーソルページネーション付きレスポンス
     */
    public CursorPagedResponse<AuditLogResponse> getMyLogs(
            Long userId, List<String> eventTypes,
            List<AuditEventCategory> eventCategories,
            LocalDateTime from, LocalDateTime to,
            String cursor, int limit) {

        int safeLimit = Math.min(limit, 50);
        Long cursorId = (cursor != null && !cursor.isBlank()) ? Long.parseLong(cursor) : null;
        List<String> mergedEventTypes = resolveEventTypes(eventTypes, eventCategories);

        StringBuilder where = new StringBuilder(" WHERE user_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(userId);

        if (cursorId != null) {
            where.append(" AND id < ?");
            params.add(cursorId);
        }
        if (mergedEventTypes != null && !mergedEventTypes.isEmpty()) {
            String placeholders = "?,".repeat(mergedEventTypes.size());
            where.append(" AND event_type IN (")
                 .append(placeholders, 0, placeholders.length() - 1)
                 .append(")");
            params.addAll(mergedEventTypes);
        }
        if (from != null) {
            where.append(" AND created_at >= ?");
            params.add(from);
        }
        if (to != null) {
            where.append(" AND created_at <= ?");
            params.add(to);
        }

        // limit+1 件取得して hasNext を判定する
        String sql = "SELECT id, user_id, target_user_id, team_id, organization_id,"
                + " event_type, ip_address, user_agent, session_hash, metadata, created_at"
                + " FROM audit_logs" + where
                + " ORDER BY id DESC"
                + " LIMIT ?";
        params.add(safeLimit + 1);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
        boolean hasNext = rows.size() > safeLimit;
        List<AuditLogResponse> data = rows.stream()
                .limit(safeLimit)
                .map(this::mapRow)
                .toList();

        String nextCursor = null;
        if (hasNext && !data.isEmpty()) {
            nextCursor = String.valueOf(data.get(data.size() - 1).getId());
        }

        return CursorPagedResponse.of(
                data,
                new CursorPagedResponse.CursorMeta(nextCursor, hasNext, safeLimit));
    }

    // ─────────────────────────────────────────────
    // チームADMIN 向けスコープ付き監査ログ参照（カーソルページング）
    // ─────────────────────────────────────────────

    /**
     * 指定チームの監査ログ一覧をカーソルベースで取得する（チームADMIN以上）。
     * 機密メタデータ（メールアドレス等）はマスクして返す。
     *
     * @param requestUserId   リクエストユーザーID（チームADMINチェック用）
     * @param teamId          対象チームID
     * @param filterUserId    絞り込みユーザーID（null可）
     * @param eventTypes      絞り込みイベント種別リスト（null可）
     * @param eventCategories 絞り込みイベントカテゴリリスト（null可）。種別リストに OR でマージされる
     * @param from            開始日時（null可）
     * @param to              終了日時（null可）
     * @param cursor          カーソル（前ページ末尾の id 文字列。null で先頭から）
     * @param limit           取得件数（最大100）
     * @return カーソルページネーション付きレスポンス
     */
    public CursorPagedResponse<AuditLogResponse> getTeamAuditLogs(
            Long requestUserId,
            Long teamId,
            Long filterUserId,
            List<String> eventTypes,
            List<AuditEventCategory> eventCategories,
            LocalDateTime from,
            LocalDateTime to,
            String cursor,
            int limit) {

        accessControlService.checkAdminOrAbove(requestUserId, teamId, "TEAM");

        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(AuditLogErrorCode.INVALID_DATE_RANGE);
        }

        int safeLimit = Math.min(limit, 100);
        Long cursorId = (cursor != null && !cursor.isBlank()) ? Long.parseLong(cursor) : null;
        List<String> mergedEventTypes = resolveEventTypes(eventTypes, eventCategories);

        StringBuilder where = new StringBuilder(" WHERE team_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(teamId);

        if (cursorId != null) {
            where.append(" AND id < ?");
            params.add(cursorId);
        }
        if (filterUserId != null) {
            where.append(" AND user_id = ?");
            params.add(filterUserId);
        }
        if (mergedEventTypes != null && !mergedEventTypes.isEmpty()) {
            String placeholders = "?,".repeat(mergedEventTypes.size());
            where.append(" AND event_type IN (")
                 .append(placeholders, 0, placeholders.length() - 1)
                 .append(")");
            params.addAll(mergedEventTypes);
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
        params.add(safeLimit + 1);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
        boolean hasNext = rows.size() > safeLimit;
        List<AuditLogResponse> data = rows.stream()
                .limit(safeLimit)
                .map(this::mapRow)
                .map(this::maskSensitiveMetadata)
                .toList();

        String nextCursor = null;
        if (hasNext && !data.isEmpty()) {
            nextCursor = String.valueOf(data.get(data.size() - 1).getId());
        }

        return CursorPagedResponse.of(
                data,
                new CursorPagedResponse.CursorMeta(nextCursor, hasNext, safeLimit));
    }

    // ─────────────────────────────────────────────
    // 組織ADMIN 向けスコープ付き監査ログ参照（カーソルページング）
    // ─────────────────────────────────────────────

    /**
     * 指定組織の監査ログ一覧をカーソルベースで取得する（組織ADMIN以上）。
     * 機密メタデータ（メールアドレス等）はマスクして返す。
     *
     * @param requestUserId   リクエストユーザーID（組織ADMINチェック用）
     * @param orgId           対象組織ID
     * @param filterUserId    絞り込みユーザーID（null可）
     * @param eventTypes      絞り込みイベント種別リスト（null可）
     * @param eventCategories 絞り込みイベントカテゴリリスト（null可）。種別リストに OR でマージされる
     * @param from            開始日時（null可）
     * @param to              終了日時（null可）
     * @param cursor          カーソル（前ページ末尾の id 文字列。null で先頭から）
     * @param limit           取得件数（最大100）
     * @return カーソルページネーション付きレスポンス
     */
    public CursorPagedResponse<AuditLogResponse> getOrganizationAuditLogs(
            Long requestUserId,
            Long orgId,
            Long filterUserId,
            List<String> eventTypes,
            List<AuditEventCategory> eventCategories,
            LocalDateTime from,
            LocalDateTime to,
            String cursor,
            int limit) {

        accessControlService.checkAdminOrAbove(requestUserId, orgId, "ORGANIZATION");

        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(AuditLogErrorCode.INVALID_DATE_RANGE);
        }

        int safeLimit = Math.min(limit, 100);
        Long cursorId = (cursor != null && !cursor.isBlank()) ? Long.parseLong(cursor) : null;
        List<String> mergedEventTypes = resolveEventTypes(eventTypes, eventCategories);

        StringBuilder where = new StringBuilder(" WHERE organization_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(orgId);

        if (cursorId != null) {
            where.append(" AND id < ?");
            params.add(cursorId);
        }
        if (filterUserId != null) {
            where.append(" AND user_id = ?");
            params.add(filterUserId);
        }
        if (mergedEventTypes != null && !mergedEventTypes.isEmpty()) {
            String placeholders = "?,".repeat(mergedEventTypes.size());
            where.append(" AND event_type IN (")
                 .append(placeholders, 0, placeholders.length() - 1)
                 .append(")");
            params.addAll(mergedEventTypes);
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
        params.add(safeLimit + 1);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
        boolean hasNext = rows.size() > safeLimit;
        List<AuditLogResponse> data = rows.stream()
                .limit(safeLimit)
                .map(this::mapRow)
                .map(this::maskSensitiveMetadata)
                .toList();

        String nextCursor = null;
        if (hasNext && !data.isEmpty()) {
            nextCursor = String.valueOf(data.get(data.size() - 1).getId());
        }

        return CursorPagedResponse.of(
                data,
                new CursorPagedResponse.CursorMeta(nextCursor, hasNext, safeLimit));
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

    /**
     * イベント種別リストとカテゴリリストをマージして統合イベント種別リストを返す。
     *
     * <p>eventCategories に指定されたカテゴリに属する AuditEventType を列挙し、
     * eventTypes に OR 条件でマージする。両方 null/空の場合は null を返す（フィルタなし）。</p>
     */
    private List<String> resolveEventTypes(List<String> eventTypes,
                                           List<AuditEventCategory> eventCategories) {
        if ((eventCategories == null || eventCategories.isEmpty())) {
            return eventTypes;
        }

        // カテゴリに対応するイベント種別名を列挙
        List<String> fromCategories = Arrays.stream(AuditEventType.values())
                .filter(et -> eventCategories.contains(et.getCategory()))
                .map(AuditEventType::name)
                .collect(Collectors.toList());

        if (fromCategories.isEmpty()) {
            return eventTypes;
        }

        if (eventTypes == null || eventTypes.isEmpty()) {
            return fromCategories;
        }

        // 重複を除いてマージ
        List<String> merged = new ArrayList<>(eventTypes);
        for (String type : fromCategories) {
            if (!merged.contains(type)) {
                merged.add(type);
            }
        }
        return merged;
    }

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

    /**
     * EMAIL_CHANGE 系イベントの metadata から new_email / old_email をマスクして返す。
     * ADMIN スコープ API でも平文メールアドレスが漏れないようにするため。
     */
    private AuditLogResponse maskSensitiveMetadata(AuditLogResponse response) {
        if (response.getMetadata() == null) return response;
        String eventType = response.getEventType();
        if (!"EMAIL_CHANGE_REQUESTED".equals(eventType)
                && !"EMAIL_CHANGED".equals(eventType)) {
            return response;
        }
        // new_email / old_email の値をマスク
        String masked = response.getMetadata()
                .replaceAll("\"new_email\"\\s*:\\s*\"[^\"]*\"", "\"new_email\":\"***\"")
                .replaceAll("\"old_email\"\\s*:\\s*\"[^\"]*\"", "\"old_email\":\"***\"");
        return AuditLogResponse.builder()
                .id(response.getId())
                .userId(response.getUserId())
                .targetUserId(response.getTargetUserId())
                .teamId(response.getTeamId())
                .organizationId(response.getOrganizationId())
                .eventType(response.getEventType())
                .ipAddress(response.getIpAddress())
                .userAgent(response.getUserAgent())
                .sessionHash(response.getSessionHash())
                .metadata(masked)
                .createdAt(response.getCreatedAt())
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
