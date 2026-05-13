package com.mannschaft.app.succession.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.succession.SuccessionErrorCode;
import com.mannschaft.app.succession.entity.SuccessionPreRegistrationEntity;
import com.mannschaft.app.succession.entity.UnsealRequestEntity;
import com.mannschaft.app.succession.repository.SuccessionPreRegistrationRepository;
import com.mannschaft.app.succession.repository.UnsealRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 封緘解除二者承認ワークフローサービス（F09.15 S2-A）。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §7.2
 *
 * <p>処理フロー:
 * <ol>
 *   <li>申請者が {@link #requestUnseal} で解除申請を起票 → sealStatus を UNSEAL_REQUESTED に遷移</li>
 *   <li>一次承認者（申請者と別人）が {@link #approve} で一次承認</li>
 *   <li>二次承認者（申請者・一次承認者と別人）が {@link #secondApprove} で最終承認
 *       → sealStatus を UNSEALED に遷移し、72h 後に自動再封予定を設定</li>
 *   <li>申請のキャンセルは申請者本人または ADMIN が {@link #cancel} で行う
 *       → sealStatus を SEALED に戻す</li>
 * </ol>
 *
 * <p>権限要件: MANAGE_SUCCESSION_UNSEAL 権限 または ADMIN/DEPUTY_ADMIN ロール。
 *
 * <p>テナント分離: 全メソッドで {@code organizationId} による絞り込みを維持する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UnsealRequestService {

    /** 72h 自動再封（二次承認完了から開封継続できる時間）。 */
    static final long UNSEAL_DURATION_HOURS = 72L;

    private final SuccessionPreRegistrationRepository preRegRepo;
    private final UnsealRequestRepository unsealRequestRepo;
    private final AccessControlService accessControlService;
    private final RoleService roleService;

    // ─────────────────────────────────────────────
    // 申請
    // ─────────────────────────────────────────────

    /**
     * 封緘解除申請を起票する（UC-B1）。
     *
     * <p>事前登録が SEALED 状態であることを確認してから申請レコードを作成し、
     * sealStatus を UNSEAL_REQUESTED に遷移させる。
     *
     * @param organizationId    テナント ID
     * @param viewerUserId      申請者ユーザー ID
     * @param preRegistrationId 対象事前登録 ID
     * @param reason            解除理由
     * @return 作成された申請レコードの ID
     */
    @Transactional
    public UUID requestUnseal(Long organizationId, Long viewerUserId,
                               UUID preRegistrationId, String reason) {
        checkUnsealPermission(viewerUserId, organizationId);

        SuccessionPreRegistrationEntity preReg = preRegRepo
                .findByIdAndOrganizationIdAndDeletedAtIsNull(preRegistrationId, organizationId)
                .orElseThrow(() -> new BusinessException(SuccessionErrorCode.PRE_REGISTRATION_NOT_FOUND));

        if (!"SEALED".equals(preReg.getSealStatus())) {
            throw new BusinessException(SuccessionErrorCode.PRE_REGISTRATION_NOT_SEALED);
        }

        UnsealRequestEntity req = UnsealRequestEntity.builder()
                .organizationId(organizationId)
                .dwellingUnitId(preReg.getDwellingUnitId())
                .residentRegistryId(preReg.getResidentRegistryId())
                .preRegistrationId(preRegistrationId)
                .requestedBy(viewerUserId)
                .requestReason(reason)
                .build();
        UnsealRequestEntity saved = unsealRequestRepo.save(req);

        preReg.setSealStatus("UNSEAL_REQUESTED");
        preRegRepo.save(preReg);

        log.info("封緘解除申請: organizationId={}, preRegId={}, requestedBy={}, reqId={}",
                organizationId, preRegistrationId, viewerUserId, saved.getId());
        return saved.getId();
    }

    // ─────────────────────────────────────────────
    // 一次承認
    // ─────────────────────────────────────────────

    /**
     * 一次承認を行う（UC-B2）。
     *
     * <p>申請者本人は承認者になれない（APPROVER_CONFLICT）。
     *
     * @param organizationId  テナント ID
     * @param viewerUserId    一次承認者ユーザー ID
     * @param unsealRequestId 解除申請 ID
     */
    @Transactional
    public void approve(Long organizationId, Long viewerUserId, UUID unsealRequestId) {
        checkUnsealPermission(viewerUserId, organizationId);

        UnsealRequestEntity req = findRequest(unsealRequestId, organizationId);

        if (req.getRequestedBy().equals(viewerUserId)) {
            throw new BusinessException(SuccessionErrorCode.APPROVER_CONFLICT);
        }

        req.setFirstApproverUserId(viewerUserId);
        req.setFirstApprovedAt(LocalDateTime.now());
        unsealRequestRepo.save(req);

        log.info("一次承認完了: reqId={}, approver={}", unsealRequestId, viewerUserId);
    }

    // ─────────────────────────────────────────────
    // 二次承認
    // ─────────────────────────────────────────────

    /**
     * 二次承認を行い、封緘を UNSEALED に遷移させる（UC-B3）。
     *
     * <p>一次承認未完了・申請者または一次承認者との重複はエラー。
     * 二次承認完了後、事前登録の sealStatus を UNSEALED に遷移し、
     * NOW + {@link #UNSEAL_DURATION_HOURS} を autoResealAt にセットする。
     *
     * @param organizationId  テナント ID
     * @param viewerUserId    二次承認者ユーザー ID
     * @param unsealRequestId 解除申請 ID
     */
    @Transactional
    public void secondApprove(Long organizationId, Long viewerUserId, UUID unsealRequestId) {
        checkUnsealPermission(viewerUserId, organizationId);

        UnsealRequestEntity req = findRequest(unsealRequestId, organizationId);

        if (req.getFirstApproverUserId() == null) {
            throw new BusinessException(SuccessionErrorCode.FIRST_APPROVER_REQUIRED);
        }
        if (req.getRequestedBy().equals(viewerUserId)
                || req.getFirstApproverUserId().equals(viewerUserId)) {
            throw new BusinessException(SuccessionErrorCode.APPROVER_CONFLICT);
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime autoResealAt = now.plusHours(UNSEAL_DURATION_HOURS);

        req.setSecondApproverUserId(viewerUserId);
        req.setSecondApprovedAt(now);
        req.setUnsealCompletedAt(now);
        req.setAutoResealAt(autoResealAt);
        unsealRequestRepo.save(req);

        SuccessionPreRegistrationEntity preReg = preRegRepo
                .findByIdAndOrganizationIdAndDeletedAtIsNull(req.getPreRegistrationId(), organizationId)
                .orElseThrow(() -> new BusinessException(SuccessionErrorCode.PRE_REGISTRATION_NOT_FOUND));
        preReg.setSealStatus("UNSEALED");
        preReg.setAutoResealAt(autoResealAt);
        preRegRepo.save(preReg);

        log.info("二次承認完了（開封）: reqId={}, approver={}, autoResealAt={}",
                unsealRequestId, viewerUserId, autoResealAt);
    }

    // ─────────────────────────────────────────────
    // キャンセル
    // ─────────────────────────────────────────────

    /**
     * 封緘解除申請をキャンセルする（申請者本人 または ADMIN のみ）。
     *
     * <p>キャンセル後、事前登録の sealStatus を SEALED に戻す。
     *
     * @param organizationId  テナント ID
     * @param viewerUserId    操作者ユーザー ID
     * @param unsealRequestId 解除申請 ID
     */
    @Transactional
    public void cancel(Long organizationId, Long viewerUserId, UUID unsealRequestId) {
        UnsealRequestEntity req = findRequest(unsealRequestId, organizationId);

        boolean isSelf = req.getRequestedBy().equals(viewerUserId);
        boolean isAdmin = accessControlService.isAdminOrAbove(viewerUserId, organizationId, "ORGANIZATION");
        if (!isSelf && !isAdmin) {
            throw new BusinessException(SuccessionErrorCode.UNSEAL_ACCESS_DENIED);
        }

        req.setRejectedAt(LocalDateTime.now());
        req.setRejectedBy(viewerUserId);
        unsealRequestRepo.save(req);

        SuccessionPreRegistrationEntity preReg = preRegRepo
                .findByIdAndOrganizationIdAndDeletedAtIsNull(req.getPreRegistrationId(), organizationId)
                .orElseThrow(() -> new BusinessException(SuccessionErrorCode.PRE_REGISTRATION_NOT_FOUND));
        preReg.setSealStatus("SEALED");
        preRegRepo.save(preReg);

        log.info("封緘解除申請キャンセル: reqId={}, by={}", unsealRequestId, viewerUserId);
    }

    // ─────────────────────────────────────────────
    // 取得
    // ─────────────────────────────────────────────

    /**
     * 組織スコープの申請一覧を取得する（ADMIN のみ）。
     *
     * @param organizationId テナント ID
     * @param viewerUserId   閲覧者ユーザー ID
     * @return 論理削除されていない申請一覧
     */
    public List<UnsealRequestEntity> listByOrganization(Long organizationId, Long viewerUserId) {
        accessControlService.checkAdminOrAbove(viewerUserId, organizationId, "ORGANIZATION");
        return unsealRequestRepo.findByOrganizationIdAndDeletedAtIsNull(organizationId);
    }

    /**
     * 申請詳細を取得する（申請者・承認者・ADMIN のみ）。
     *
     * @param organizationId  テナント ID
     * @param viewerUserId    閲覧者ユーザー ID
     * @param unsealRequestId 解除申請 ID
     * @return 申請エンティティ
     */
    public UnsealRequestEntity getById(Long organizationId, Long viewerUserId, UUID unsealRequestId) {
        UnsealRequestEntity req = findRequest(unsealRequestId, organizationId);
        if (!canReadRequest(req, viewerUserId, organizationId)) {
            throw new BusinessException(SuccessionErrorCode.UNSEAL_ACCESS_DENIED);
        }
        return req;
    }

    // ─────────────────────────────────────────────
    // 内部ヘルパー（private）
    // ─────────────────────────────────────────────

    private UnsealRequestEntity findRequest(UUID id, Long orgId) {
        return unsealRequestRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(id, orgId)
                .orElseThrow(() -> new BusinessException(SuccessionErrorCode.UNSEAL_REQUEST_NOT_FOUND));
    }

    private boolean canReadRequest(UnsealRequestEntity req, Long userId, Long orgId) {
        if (accessControlService.isAdminOrAbove(userId, orgId, "ORGANIZATION")) return true;
        if (req.getRequestedBy().equals(userId)) return true;
        if (userId.equals(req.getFirstApproverUserId())) return true;
        if (userId.equals(req.getSecondApproverUserId())) return true;
        return false;
    }

    /**
     * MANAGE_SUCCESSION_UNSEAL 権限 または ADMIN/DEPUTY_ADMIN であることを検証する。
     * 違反時は {@link SuccessionErrorCode#UNSEAL_ACCESS_DENIED} をスロー。
     */
    private void checkUnsealPermission(Long userId, Long orgId) {
        boolean hasPermission = accessControlService.isAdminOrAbove(userId, orgId, "ORGANIZATION")
                || roleService.hasPermission(userId, orgId, "ORGANIZATION", "MANAGE_SUCCESSION_UNSEAL");
        if (!hasPermission) {
            throw new BusinessException(SuccessionErrorCode.UNSEAL_ACCESS_DENIED);
        }
    }
}
