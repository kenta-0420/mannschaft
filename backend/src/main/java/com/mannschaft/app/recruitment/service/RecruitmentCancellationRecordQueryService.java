package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.RecruitmentCancellationRecordSummaryResponse;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * F03.11.1 募集キャンセル料の記録一覧（設計書 §12・免除 UI のための一覧）。
 *
 * <p><b>裁定（マスター 2026-08-13）</b>: 一覧の対象は<b>受取先側</b>（精算管理者・受取先本人）と
 * {@code SYSTEM_ADMIN} に限る。<b>債務者（キャンセル料を負っている本人）向けの一覧は本波では作らない</b>
 * ——「自分がなぜ申込できないのか分からない」という導線の課題は実在するが、それは免除の操作を
 * 可能にする本任務とは目的が異なる別の画面の話であり、混ぜると両方が中途半端になる。</p>
 *
 * <p><b>ドメイン境界（裁定 2）</b>: 受取先の絞り込みは {@code recruitment_listings} 自身が持つ
 * {@code payeeKind}/{@code payeeUserId}/{@code scopeId} だけで行い、payment ドメイン（escrow）は
 * 一切読まない。<b>ただしこれは認可の権威を移すという意味ではない</b>——一覧は「絞り込まれた閲覧」に
 * 過ぎず、免除の実行時には {@link RecruitmentCancellationFeeWaiveService} が payment ドメインの
 * {@code ConnectChargeService#isPayeeSettlementManager} で必ず再検証する（二段構え。詳細は
 * {@link com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository#findVisibleToPayee}
 * の Javadoc）。</p>
 */
@Service
@RequiredArgsConstructor
public class RecruitmentCancellationRecordQueryService {

    /** 免除可能な状態のみを既定で返す（§10.1 の対象状態表）。 */
    private static final List<CancellationPaymentStatus> DEFAULT_STATUSES = List.of(
            CancellationPaymentStatus.PENDING,
            CancellationPaymentStatus.FAILED,
            CancellationPaymentStatus.UNCOLLECTIBLE);

    /** JPQL の {@code IN ()} 空リストを避けるための番人値（存在しない ID）。 */
    private static final Long SENTINEL_ID = -1L;

    /** {@code ConnectChargeService}/{@code ConnectAccountService} の定義（"MANAGE_RECRUITMENTS"）と同一。 */
    private static final String PERMISSION_MANAGE_PAYMENT = "MANAGE_RECRUITMENTS";

    private final RecruitmentCancellationRecordRepository cancellationRecordRepository;
    private final AccessControlService accessControlService;

    /**
     * キャンセル料記録の一覧を取得する（受取先側の管理者・本人・{@code SYSTEM_ADMIN} 向け）。
     *
     * @param actorUserId 操作者ユーザー ID
     * @param statuses    絞り込む決済ステータス（未指定なら免除可能な既定 3 状態）
     * @param pageable    ページング（必須。全件返しは行わない）
     * @return 操作者が受取先である記録のみを含むページ（{@code SYSTEM_ADMIN} は全件）
     */
    @Transactional(readOnly = true)
    public Page<RecruitmentCancellationRecordSummaryResponse> list(
            Long actorUserId, Collection<CancellationPaymentStatus> statuses, Pageable pageable) {

        List<CancellationPaymentStatus> effectiveStatuses =
                (statuses == null || statuses.isEmpty()) ? DEFAULT_STATUSES : List.copyOf(statuses);

        // SYSTEM_ADMIN は全件見える（AccessControlService への直接呼び出し＝認可番人シグナル）。
        if (accessControlService.isSystemAdmin(actorUserId)) {
            return cancellationRecordRepository.findAllForSystemAdmin(effectiveStatuses, pageable);
        }

        Set<Long> teamScopeIds = resolveManagedTeamScopeIds(actorUserId);
        Set<Long> orgScopeIds = resolveManagedOrgScopeIds(actorUserId);

        return cancellationRecordRepository.findVisibleToPayee(
                actorUserId,
                teamScopeIds.isEmpty() ? Set.of(SENTINEL_ID) : teamScopeIds,
                orgScopeIds.isEmpty() ? Set.of(SENTINEL_ID) : orgScopeIds,
                effectiveStatuses,
                pageable);
    }

    /**
     * 操作者が支払い管理権限（{@value #PERMISSION_MANAGE_PAYMENT}）を持つ TEAM の scopeId 集合を返す。
     *
     * <p>候補（操作者自身の所属 TEAM。件数は操作者の所属数に依存し記録数には依存しない）を
     * {@link AccessControlService#findAffiliatedScopeIds} で絞ってから権限を検査するため、
     * 記録件数分のクエリは発生しない。</p>
     */
    private Set<Long> resolveManagedTeamScopeIds(Long actorUserId) {
        Set<Long> candidates = accessControlService.findAffiliatedScopeIds(
                actorUserId, RecruitmentScopeType.TEAM.name());
        Set<Long> managed = new LinkedHashSet<>();
        for (Long teamId : candidates) {
            if (accessControlService.hasPermission(
                    actorUserId, teamId, RecruitmentScopeType.TEAM.name(), PERMISSION_MANAGE_PAYMENT)) {
                managed.add(teamId);
            }
        }
        return managed;
    }

    /**
     * 操作者が管理者または支払い管理権限を持つ ORG の scopeId 集合を返す
     * （{@code ConnectChargeService#isPayeeSettlementManager} の ORG 分岐と同一の判定
     * {@link AccessControlService#checkAdminOrHasPermission} を流用）。
     */
    private Set<Long> resolveManagedOrgScopeIds(Long actorUserId) {
        Set<Long> candidates = accessControlService.findAffiliatedScopeIds(
                actorUserId, RecruitmentScopeType.ORGANIZATION.name());
        Set<Long> managed = new LinkedHashSet<>();
        for (Long orgId : candidates) {
            try {
                accessControlService.checkAdminOrHasPermission(
                        actorUserId, orgId, RecruitmentScopeType.ORGANIZATION.name(), PERMISSION_MANAGE_PAYMENT);
                managed.add(orgId);
            } catch (BusinessException e) {
                // 権限が無いだけであり異常ではない（判定であって認可の実行ではない）。
            }
        }
        return managed;
    }
}
