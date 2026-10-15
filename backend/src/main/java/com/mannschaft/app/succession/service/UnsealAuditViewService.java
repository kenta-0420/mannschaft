package com.mannschaft.app.succession.service;

import com.mannschaft.app.succession.entity.UnsealAuditViewEntity;
import com.mannschaft.app.succession.entity.UnsealRequestEntity;
import com.mannschaft.app.succession.repository.UnsealAuditViewRepository;
import com.mannschaft.app.succession.repository.UnsealRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 開封中閲覧監査ログ記録サービス（F09.15 S2-A）。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §7.4
 *
 * <p>UNSEALED 状態の事前登録コンテンツへのアクセスを append-only テーブル
 * {@code unseal_audit_views} に記録する。
 *
 * <p>呼び出し規約:
 * <ul>
 *   <li>コントローラーは {@link com.mannschaft.app.succession.guard.UnsealedAccessGuard#checkViewAccess}
 *       が成功した後に {@link #recordView} を呼び出すこと</li>
 *   <li>本サービスは append-only のため UPDATE / DELETE メソッドを提供しない</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnsealAuditViewService {

    /** IP アドレスの最大長（IPv6 対応: 45 文字）。 */
    private static final int MAX_IP_LENGTH = 45;

    /** User-Agent の最大長。 */
    private static final int MAX_USER_AGENT_LENGTH = 500;

    private final UnsealAuditViewRepository auditViewRepo;
    private final UnsealRequestRepository unsealRequestRepo;

    /**
     * 開封中閲覧を記録する（append-only）。
     *
     * <p>このメソッドはコントローラー側から
     * 「閲覧前に {@link com.mannschaft.app.succession.guard.UnsealedAccessGuard#checkViewAccess}
     * が成功した後」に呼び出すこと。
     *
     * <p>直近の有効な unseal_request_id（unsealCompletedAt 非 NULL かつ reSealedAt が NULL の最新レコード）
     * を自動取得して記録する。
     *
     * @param organizationId    テナント ID
     * @param preRegistrationId 対象事前登録 ID
     * @param viewerUserId      閲覧者ユーザー ID
     * @param ipAddress         閲覧元 IP（null 可。45 文字超は切り詰め）
     * @param userAgent         User-Agent（null 可。500 文字超は切り詰め）
     * @param requestId         MDC リクエスト ID（追跡用・null 可）
     */
    @Transactional
    public void recordView(Long organizationId, UUID preRegistrationId, Long viewerUserId,
                            String ipAddress, String userAgent, String requestId) {
        // 直近の有効な unseal_request_id を取得（reSealedAt が NULL = まだ再封されていない）
        UUID unsealRequestId = unsealRequestRepo
                .findByPreRegistrationIdAndDeletedAtIsNullOrderByCreatedAtDesc(preRegistrationId)
                .stream()
                .filter(r -> r.getUnsealCompletedAt() != null && r.getReSealedAt() == null)
                .findFirst()
                .map(UnsealRequestEntity::getId)
                .orElse(null);

        UnsealAuditViewEntity view = UnsealAuditViewEntity.builder()
                .organizationId(organizationId)
                .unsealRequestId(unsealRequestId)
                .viewerUserId(viewerUserId)
                .viewedAt(LocalDateTime.now())
                .ipAddress(truncate(ipAddress, MAX_IP_LENGTH))
                .userAgent(truncate(userAgent, MAX_USER_AGENT_LENGTH))
                .requestId(requestId)
                .build();
        auditViewRepo.save(view);

        log.info("封緘開封閲覧記録: organizationId={}, preRegId={}, viewer={}",
                organizationId, preRegistrationId, viewerUserId);
    }

    /**
     * 指定申請の閲覧履歴一覧を取得する（ADMIN 用監査ダッシュボード向け）。
     *
     * <p>呼び出し側（Controller）で ADMIN 権限チェックを行うこと。
     *
     * @param organizationId  テナント ID（未使用だが将来のシャード対応に備えて保持）
     * @param unsealRequestId 解除申請 ID
     * @return 閲覧日時降順の履歴一覧
     */
    @Transactional(readOnly = true)
    public List<UnsealAuditViewEntity> listByUnsealRequest(Long organizationId,
                                                             UUID unsealRequestId) {
        return auditViewRepo.findByUnsealRequestIdAndDeletedAtIsNullOrderByViewedAtDesc(unsealRequestId);
    }

    // ─────────────────────────────────────────────
    // 内部ヘルパー（private）
    // ─────────────────────────────────────────────

    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
