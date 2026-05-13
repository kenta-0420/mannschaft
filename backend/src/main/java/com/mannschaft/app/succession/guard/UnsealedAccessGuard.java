package com.mannschaft.app.succession.guard;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.succession.SuccessionErrorCode;
import com.mannschaft.app.succession.entity.SuccessionPreRegistrationEntity;
import com.mannschaft.app.succession.entity.UnsealRequestEntity;
import com.mannschaft.app.succession.repository.SuccessionPreRegistrationRepository;
import com.mannschaft.app.succession.repository.UnsealRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * UNSEALED 状態のコンテンツへのアクセスを三層で保護するガードコンポーネント（F09.15 S2-A）。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §7.3
 *
 * <p>三層保護:
 * <ol>
 *   <li>Layer 1: sealStatus が "UNSEALED" であることを検証</li>
 *   <li>Layer 2: auto_reseal_at が現在時刻より未来であることを検証（TTL チェック）</li>
 *   <li>Layer 3: 閲覧者が承認者集合（申請者・一次承認者・二次承認者）∪ ADMIN に含まれることを検証</li>
 * </ol>
 *
 * <p>コントローラー層では、このガードの {@link #checkViewAccess} 成功後に
 * {@link com.mannschaft.app.succession.service.UnsealAuditViewService#recordView} を呼び出すこと。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnsealedAccessGuard {

    private final SuccessionPreRegistrationRepository preRegRepo;
    private final UnsealRequestRepository unsealRequestRepo;
    private final AccessControlService accessControlService;

    /**
     * 指定の事前登録が現在アクセス可能（UNSEALED かつ TTL 内 かつ閲覧権限あり）であることを検証する。
     *
     * <p>失敗時は {@link BusinessException} をスローする。
     *
     * @param preRegistrationId 対象事前登録 ID
     * @param viewerUserId      閲覧者ユーザー ID
     * @param organizationId    テナント ID
     * @throws BusinessException {@link SuccessionErrorCode#PRE_REGISTRATION_NOT_FOUND}
     *                           / {@link SuccessionErrorCode#UNSEAL_EXPIRED_OR_INACTIVE}
     *                           / {@link SuccessionErrorCode#UNSEAL_ACCESS_DENIED}
     */
    public void checkViewAccess(UUID preRegistrationId, Long viewerUserId, Long organizationId) {
        SuccessionPreRegistrationEntity preReg = preRegRepo
                .findByIdAndOrganizationIdAndDeletedAtIsNull(preRegistrationId, organizationId)
                .orElseThrow(() -> new BusinessException(SuccessionErrorCode.PRE_REGISTRATION_NOT_FOUND));

        // Layer 1: seal_status チェック
        if (!"UNSEALED".equals(preReg.getSealStatus())) {
            throw new BusinessException(SuccessionErrorCode.UNSEAL_EXPIRED_OR_INACTIVE);
        }

        // Layer 2: TTL チェック（auto_reseal_at > NOW）
        if (preReg.getAutoResealAt() == null
                || LocalDateTime.now().isAfter(preReg.getAutoResealAt())) {
            throw new BusinessException(SuccessionErrorCode.UNSEAL_EXPIRED_OR_INACTIVE);
        }

        // Layer 3: 閲覧者が承認者集合 ∪ ADMIN に含まれるか
        if (!isViewerAuthorized(preReg, viewerUserId, organizationId)) {
            throw new BusinessException(SuccessionErrorCode.UNSEAL_ACCESS_DENIED);
        }
    }

    /**
     * アクセス可否を boolean で返す（例外をスローしない版）。
     *
     * <p>UI でのボタン表示制御など、例外不要な場合に使用する。
     *
     * @param preRegistrationId 対象事前登録 ID
     * @param viewerUserId      閲覧者ユーザー ID
     * @param organizationId    テナント ID
     * @return アクセス可能な場合 true
     */
    public boolean canView(UUID preRegistrationId, Long viewerUserId, Long organizationId) {
        try {
            checkViewAccess(preRegistrationId, viewerUserId, organizationId);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────
    // 内部ヘルパー（private）
    // ─────────────────────────────────────────────

    /**
     * 閲覧者が承認者集合（申請者・一次承認者・二次承認者）∪ ADMIN に含まれるかを判定する。
     *
     * <p>直近の UNSEALED 解除申請（unsealCompletedAt が非 NULL の最新レコード）のみチェックする。
     */
    private boolean isViewerAuthorized(SuccessionPreRegistrationEntity preReg,
                                        Long viewerUserId, Long organizationId) {
        // ADMIN は常に許可
        if (accessControlService.isAdminOrAbove(viewerUserId, organizationId, "ORGANIZATION")) {
            return true;
        }

        // 直近の有効な解除申請（unsealCompletedAt 非 NULL）の承認者集合に含まれるか
        List<UnsealRequestEntity> requests = unsealRequestRepo
                .findByPreRegistrationIdAndDeletedAtIsNullOrderByCreatedAtDesc(preReg.getId());
        for (UnsealRequestEntity req : requests) {
            if (req.getUnsealCompletedAt() != null) {
                if (viewerUserId.equals(req.getRequestedBy())) return true;
                if (viewerUserId.equals(req.getFirstApproverUserId())) return true;
                if (viewerUserId.equals(req.getSecondApproverUserId())) return true;
                // 最新の UNSEALED 申請のみチェックするため break
                break;
            }
        }
        return false;
    }
}
