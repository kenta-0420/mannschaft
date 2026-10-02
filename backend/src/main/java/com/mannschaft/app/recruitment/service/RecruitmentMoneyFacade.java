package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.CancellationPolicyResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentParticipantResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentUserPenaltyResponse;
import com.mannschaft.app.recruitment.dto.UpdateCancellationPolicyRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * recruitment の金銭・制裁 6 EP の <b>認可ファサード</b>（トランザクションの外）。
 *
 * <p>CMP-260923-0954 W4。認可（{@code AccessControlService}・受取先判定）を {@code @Transactional} の外へ出す。
 * 本クラスは {@code @Transactional} を<b>付けない</b>（クラスにもメソッドにも）。流れは共通で
 * 「tx 本体 Service の readOnly な scope 解決（自ドメインのみ）→ 認可 → tx 本体（中で対象→親→スコープをたどり直す）」。
 * Controller は本クラスだけを呼ぶ。名前を {@code *AccessService}・{@code *AccessGate} にしないのは、
 * 呼んだだけで認可シグナル扱いになり本クラス内の認可漏れを AuthzControllerGuard が見逃すのを避けるため。</p>
 *
 * <p>{@code ScopeConcealingAccessGate} の共通形（{@code requireAdminOrConceal} 等）は<b>使わない</b>。
 * Gate は冒頭で SYSTEM_ADMIN を通すが、本クラスの対象 EP は是正前から SYSTEM_ADMIN を通していない
 * （lift・confirm・policy は 403 のまま、waive は是正前から許可。マスター裁可 2026-09-30）。
 * 許可経路では是正前と同じ判定クエリだけを発行し（管理者系は {@code isAdminOrAbove} 1 本、免除は受取先判定 1 本）、
 * 追加の判定（SYSTEM_ADMIN・在籍）は拒否経路でだけ行う。</p>
 *
 * <h3>EP × 主体の応答表（status は HTTP、コードは error.code）</h3>
 * <pre>
 * 主体＼EP                    waive              lift             confirm          policy GET/PATCH/archive
 * 部外者・他チームADMIN        404 COMMON_005     404 R310         404 R001         404 R001
 * 同スコープ一般メンバー       403 COMMON_002     403 COMMON_002   403 COMMON_002   403 COMMON_002
 * スコープ管理者               403(受取側でない)  許可             許可             許可
 * user_roles のみの ADMIN      403 COMMON_002     許可             許可             許可
 * 債務者本人（非在籍）         403 COMMON_002     404 R310         404 R001         404 R001
 * 受取側の精算管理者           許可               -                -                -
 * 非メンバー/メンバー SYSADMIN 許可（是正前から）  403 COMMON_002   403 COMMON_002   403 COMMON_002
 * 対象不在・親だけ不在         404 COMMON_005     404 R310         404 R001         404 R001
 * </pre>
 * <p>状態判定（解除済み・CONFIRMED・PAID・テンプレートでない等）はすべて認可の後（tx 本体）。
 * 拒否・不在では DB は 1 列も変わらない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecruitmentMoneyFacade {

    private final RecruitmentCancellationFeeWaiveService waiveService;
    private final RecruitmentPenaltyService penaltyService;
    private final RecruitmentListingService listingService;
    private final RecruitmentCancellationPolicyService policyService;
    private final AccessControlService accessControlService;
    private final ConnectChargeService connectChargeService;

    // ===========================================
    // キャンセル料の免除
    // ===========================================

    /**
     * キャンセル料を免除する。許可は「受取先側の精算管理者」または SYSTEM_ADMIN のみ。
     *
     * <p>拒否時、操作者が記録の存在を知り得る者（債務者本人・募集スコープの在籍者・user_roles の ADMIN/DEPUTY_ADMIN）
     * なら 403（{@code COMMON_002}）、それ以外は不在と同一の 404（{@code COMMON_005}）。</p>
     *
     * @param recordId    記録 ID
     * @param actorUserId 操作者
     * @param reason      免除理由
     */
    public void waive(Long recordId, Long actorUserId, String reason) {
        // 本文の形式検査は存在判定より前・ID 非依存（是正前と同じ順序）。
        RecruitmentCancellationFeeWaiveService.validateReason(reason);
        RecruitmentCancellationFeeWaiveService.WaiveTarget target = waiveService.resolveWaiveTarget(recordId);

        // 受取先の判定は payment ドメインへ委ね、recruitment から escrow を直接読まない（§3.4・§10.2）。
        boolean payeeSide = connectChargeService.isPayeeSettlementManager(
                EscrowSourceKind.RECRUITMENT, target.listingId(), target.participantId(), actorUserId);
        if (!payeeSide && !accessControlService.isSystemAdmin(actorUserId)) {
            log.warn("F03.11.1 免除の権限が無い呼び出しを拒否: recordId={}, actorUserId={}", recordId, actorUserId);
            throw waiveDenial(target, actorUserId);
        }
        waiveService.waive(recordId, actorUserId, reason, payeeSide);
    }

    /** 免除の拒否。存在を知り得る者だけ 403、それ以外は不在と同一の 404（拒否経路でだけ追加の判定を行う）。 */
    private BusinessException waiveDenial(
            RecruitmentCancellationFeeWaiveService.WaiveTarget target, Long actorUserId) {
        if (actorUserId != null && actorUserId.equals(target.debtorUserId())) {
            return new BusinessException(CommonErrorCode.COMMON_002);
        }
        Optional<RecruitmentCancellationFeeWaiveService.ListingScope> scope =
                waiveService.resolveListingScope(target.listingId());
        if (scope.isEmpty()) {
            // 親（募集）が論理削除済み: スコープを解けない者には存在を明かさない。
            return new BusinessException(CommonErrorCode.COMMON_005);
        }
        String accessScope = accessScopeName(scope.get().scopeType());
        if (accessScope != null
                && (accessControlService.isMember(actorUserId, scope.get().scopeId(), accessScope)
                || accessControlService.isAdminOrAbove(actorUserId, scope.get().scopeId(), accessScope))) {
            return new BusinessException(CommonErrorCode.COMMON_002);
        }
        return new BusinessException(CommonErrorCode.COMMON_005);
    }

    // ===========================================
    // ペナルティの手動解除
    // ===========================================

    /**
     * ペナルティを手動解除する。許可は発動元設定のスコープの ADMIN/DEPUTY_ADMIN（user_roles 判定。SYSTEM_ADMIN は不可）。
     *
     * @param pathScopeType パスの scopeType
     * @param pathScopeId   パスの scopeId
     * @param penaltyId     ペナルティ ID
     * @param adminUserId   操作者
     * @return 解除後のペナルティ
     */
    public RecruitmentUserPenaltyResponse liftPenalty(
            String pathScopeType, Long pathScopeId, Long penaltyId, Long adminUserId) {
        RecruitmentPenaltyService.LiftScope scope =
                penaltyService.resolveLiftScope(pathScopeType, pathScopeId, penaltyId);
        requireScopeAdmin(adminUserId, scope.scopeType(), scope.scopeId(), RecruitmentErrorCode.PENALTY_NOT_FOUND);
        return toPenaltyResponse(penaltyService.liftPenalty(penaltyId, adminUserId));
    }

    /** Entity を Service の公開 API に出さないため、Facade 内で DTO へ変換する（D-1 API 境界）。 */
    private static RecruitmentUserPenaltyResponse toPenaltyResponse(RecruitmentUserPenaltyEntity entity) {
        return new RecruitmentUserPenaltyResponse(
                entity.getId(),
                entity.getUserId(),
                entity.getScopeType() != null ? entity.getScopeType().name() : null,
                entity.getScopeId(),
                entity.getPenaltyType(),
                entity.getStartedAt() != null ? entity.getStartedAt().toString() : null,
                entity.getExpiresAt() != null ? entity.getExpiresAt().toString() : null,
                entity.getLiftedAt() != null ? entity.getLiftedAt().toString() : null,
                entity.getLiftReason() != null ? entity.getLiftReason().name() : null,
                entity.isActive(),
                entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null
        );
    }

    // ===========================================
    // 申込確定
    // ===========================================

    /**
     * 参加者の申込を確定する。許可は募集スコープ（TEAM/ORGANIZATION）の ADMIN/DEPUTY_ADMIN（SYSTEM_ADMIN は不可）。
     *
     * <p>FOR UPDATE は認可の後（tx 本体）だけ。部外者の要求は他テナントの行をロックしない。</p>
     *
     * @param listingId     パスの募集 ID
     * @param participantId 参加者 ID
     * @param adminId       操作者
     * @return 確定後の参加者
     */
    public RecruitmentParticipantResponse confirmApplication(Long listingId, Long participantId, Long adminId) {
        RecruitmentListingService.ConfirmScope scope = listingService.resolveConfirmScope(listingId, participantId);
        requireScopeAdmin(adminId, scope.scopeType(), scope.scopeId(), RecruitmentErrorCode.LISTING_NOT_FOUND);
        return listingService.confirmApplication(participantId, adminId);
    }

    // ===========================================
    // キャンセルポリシー
    // ===========================================

    /**
     * ポリシー詳細を返す。許可はポリシースコープの ADMIN/DEPUTY_ADMIN（SYSTEM_ADMIN は不可）。
     *
     * @param policyId ポリシー ID
     * @param userId   操作者
     * @return ポリシー詳細
     */
    public CancellationPolicyResponse getPolicy(Long policyId, Long userId) {
        RecruitmentCancellationPolicyService.PolicyScope scope = policyService.resolvePolicyScope(policyId);
        requireScopeAdmin(userId, scope.scopeType(), scope.scopeId(), RecruitmentErrorCode.LISTING_NOT_FOUND);
        return policyService.getPolicy(policyId);
    }

    /**
     * ポリシーを編集する（テンプレートのみ）。許可は {@link #getPolicy} と同じ。
     *
     * @param policyId ポリシー ID
     * @param userId   操作者
     * @param request  更新内容
     * @return 更新後のポリシー
     */
    public CancellationPolicyResponse updatePolicy(
            Long policyId, Long userId, UpdateCancellationPolicyRequest request) {
        RecruitmentCancellationPolicyService.PolicyScope scope = policyService.resolvePolicyScope(policyId);
        requireScopeAdmin(userId, scope.scopeType(), scope.scopeId(), RecruitmentErrorCode.LISTING_NOT_FOUND);
        return policyService.updatePolicy(policyId, request);
    }

    /**
     * ポリシーを論理削除する。許可は {@link #getPolicy} と同じ。
     *
     * @param policyId ポリシー ID
     * @param userId   操作者
     */
    public void archivePolicy(Long policyId, Long userId) {
        RecruitmentCancellationPolicyService.PolicyScope scope = policyService.resolvePolicyScope(policyId);
        requireScopeAdmin(userId, scope.scopeType(), scope.scopeId(), RecruitmentErrorCode.LISTING_NOT_FOUND);
        policyService.archivePolicy(policyId);
    }

    // ===========================================
    // 共通
    // ===========================================

    /**
     * スコープの ADMIN/DEPUTY_ADMIN（user_roles）を要求する。是正前の {@code checkAdminOrAbove} と同じ判定
     * （{@code isAdminOrAbove} 1 本。SYSTEM_ADMIN は通さない）で、許可経路では追加のクエリを発行しない。
     *
     * <p>拒否時だけ追加の判定を行う: SYSTEM_ADMIN または在籍者は存在を知り得るので 403（{@code COMMON_002}）、
     * それ以外（越境）は対象リソースの不在コードで 404。スコープが TEAM/ORGANIZATION でない
     * （PERSONAL・GLOBAL）場合は在籍判定の列挙値に無いので、管理者判定も在籍判定もせず不在扱いにする
     * （{@code ScopeType.valueOf} の例外で 500 にしない）。</p>
     */
    private void requireScopeAdmin(
            Long userId, RecruitmentScopeType scopeType, Long scopeId, ErrorCode notFoundCode) {
        String accessScope = accessScopeName(scopeType);
        if (accessScope != null && accessControlService.isAdminOrAbove(userId, scopeId, accessScope)) {
            return;
        }
        if (accessControlService.isSystemAdmin(userId)
                || (accessScope != null && accessControlService.isMember(userId, scopeId, accessScope))) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        throw new BusinessException(notFoundCode);
    }

    /** AccessControlService に渡せるスコープ名。TEAM/ORGANIZATION 以外は null。 */
    private static String accessScopeName(RecruitmentScopeType scopeType) {
        if (scopeType == RecruitmentScopeType.TEAM || scopeType == RecruitmentScopeType.ORGANIZATION) {
            return scopeType.name();
        }
        return null;
    }
}
