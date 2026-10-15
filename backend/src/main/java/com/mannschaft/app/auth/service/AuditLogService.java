package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuditEventCategory;
import com.mannschaft.app.auth.dto.AuditLogResponse;
import com.mannschaft.app.auth.entity.AuditLogEntity;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.PagedResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 監査ログサービス（ファサード）。
 *
 * <p>書き込みは非同期 fire-and-forget でこのクラス内に保持。
 * 参照系（SYSTEM_ADMIN / 本人 / チーム / 組織 / ソース別）は
 * {@link AuditLogQueryService} に委譲する。public シグネチャは従来通り維持。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final AuditLogQueryService auditLogQueryService;

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
        // 自己呼び出しのためプロキシを経由しない＝非同期スレッド上でそのまま実行される。
        recordSync(eventType, userId, targetUserId, teamId, organizationId,
                ipAddress, userAgent, sessionHash, metadata);
    }

    /**
     * 監査ログを<b>呼び出しスレッドで同期に</b>記録する。失敗してもメイン処理を止めない。
     *
     * <p>{@link #record} は {@code @Async} であり「操作は成功したのに監査行がまだ無い」時間窓が開く。
     * 閲覧・参照系の監査のように、応答を返した時点で監査が残っていることを要件とする呼び出し元
     * （F20.1 課金履歴の {@code BILLING_INVOICE_VIEWED} 等）は本メソッドを使う。</p>
     *
     * <p>他ドメインから監査を書く経路は本 Service に限る。{@code AuditLogRepository} /
     * {@code AuditLogEntity} を直接触るとドメイン越境となり
     * {@code CrossDomainRepositoryDependencyArchTest}（D-5）・
     * {@code CrossDomainEntityImportArchTest}（D-1）が拒否する。</p>
     *
     * <p>引数の意味は {@link #record} と同一。</p>
     */
    public void recordSync(String eventType, Long userId, Long targetUserId,
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
    // 参照系（AuditLogQueryService への委譲）
    //
    // public シグネチャは従来通り維持し、内部実装のみ移譲する。
    // Controller / 他サービスからの呼び出しは変更不要。
    // ─────────────────────────────────────────────

    /** {@link AuditLogQueryService#getAdminLogs} に委譲。 */
    public PagedResponse<AuditLogResponse> getAdminLogs(
            Long requestUserId,
            Long filterUserId, Long filterTargetId, Long filterTeamId, Long filterOrgId,
            List<String> eventTypes, List<AuditEventCategory> eventCategories, String sessionHash,
            LocalDateTime from, LocalDateTime to,
            int page, int size) {
        return auditLogQueryService.getAdminLogs(
                requestUserId, filterUserId, filterTargetId, filterTeamId, filterOrgId,
                eventTypes, eventCategories, sessionHash, from, to, page, size);
    }

    /** {@link AuditLogQueryService#getMyLogs} に委譲。 */
    public CursorPagedResponse<AuditLogResponse> getMyLogs(
            Long userId, List<String> eventTypes,
            List<AuditEventCategory> eventCategories,
            LocalDateTime from, LocalDateTime to,
            String cursor, int limit) {
        return auditLogQueryService.getMyLogs(
                userId, eventTypes, eventCategories, from, to, cursor, limit);
    }

    /** {@link AuditLogQueryService#getTeamAuditLogs} に委譲。 */
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
        return auditLogQueryService.getTeamAuditLogs(
                requestUserId, teamId, filterUserId,
                eventTypes, eventCategories, from, to, cursor, limit);
    }

    /** {@link AuditLogQueryService#getOrganizationAuditLogs} に委譲。 */
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
        return auditLogQueryService.getOrganizationAuditLogs(
                requestUserId, orgId, filterUserId,
                eventTypes, eventCategories, from, to, cursor, limit);
    }

    /** {@link AuditLogQueryService#findBySourceAndSourceId} に委譲。 */
    public List<AuditLogResponse> findBySourceAndSourceId(String source, Long sourceId, int limit) {
        return auditLogQueryService.findBySourceAndSourceId(source, sourceId, limit);
    }
}
