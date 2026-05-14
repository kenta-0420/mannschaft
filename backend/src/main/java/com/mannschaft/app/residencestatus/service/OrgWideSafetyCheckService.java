package com.mannschaft.app.residencestatus.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.residencestatus.ResidenceStatusErrorCode;
import com.mannschaft.app.residencestatus.dto.OrgWideSafetyCheckDto;
import com.mannschaft.app.residencestatus.entity.OrgWideSafetyCheck;
import com.mannschaft.app.residencestatus.repository.OrgWideSafetyCheckRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F09.16 S3-C 管理組合横展開安否確認サービス。
 *
 * <p>理事長（ADMIN）が組織全体に安否確認を発動する際のメタ情報を管理する。
 * 実際の安否確認ロジック（回答収集等）は F03.6 ドメインに委ねる。</p>
 *
 * <p>{@code @Transactional} は residencestatus ドメイン内に閉じている（CLAUDE.md 原則 5）。
 * F03.6 との連携は将来 OrgWideSafetyCheckTriggeredEvent で分離予定。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrgWideSafetyCheckService {

    private final OrgWideSafetyCheckRepository safetyCheckRepo;
    private final AccessControlService accessControlService;

    /**
     * v1 の仮 safetyCheckId（F03.6 未連携）。
     *
     * <p>F03.6 safety_checks との連携は未実装のため、仮の 0L をセットする。
     * Entity の safetyCheckId カラムは NOT NULL のため null は使用不可。</p>
     */
    private static final long PLACEHOLDER_SAFETY_CHECK_ID = 0L;

    // ─────────────────────────────────────────────
    // 横展開安否確認の発動
    // ─────────────────────────────────────────────

    /**
     * 管理組合横展開安否確認を発動する（ADMIN のみ）。
     *
     * <p>TODO: @Transactional 越境。将来は F03.6 との連携を
     *     {@code OrgWideSafetyCheckTriggeredEvent} で分離予定。
     *     現 v1 では safetyCheckId=0L（仮）を保存し、F03.6 セッションは別途手動で作成する運用。
     *
     * @param organizationId    テナント ID
     * @param triggeredByUserId 発動者（理事長）ユーザー ID
     * @param triggerReason     発動理由（地震・火災・組合判断等）
     * @return 作成された横展開安否確認 DTO
     */
    @Transactional
    public OrgWideSafetyCheckDto triggerOrgWideSafetyCheck(
            Long organizationId, Long triggeredByUserId, String triggerReason) {

        // 権限確認: ADMIN のみ許可（DEPUTY_ADMIN は不可）
        if (!accessControlService.isAdminOrAbove(triggeredByUserId, organizationId, "ORGANIZATION")) {
            throw new BusinessException(ResidenceStatusErrorCode.DASHBOARD_ACCESS_FORBIDDEN);
        }

        // TODO: F03.6 との連携は将来 OrgWideSafetyCheckTriggeredEvent で実装予定。
        //       現 v1 では safetyCheckId=PLACEHOLDER_SAFETY_CHECK_ID(0L) を保存する。
        //       F03.6 側の safety_check セッション作成は管理者が別途実施する運用とする。
        OrgWideSafetyCheck check = OrgWideSafetyCheck.builder()
                .organizationId(organizationId)
                .safetyCheckId(PLACEHOLDER_SAFETY_CHECK_ID)
                .triggeredBy(triggeredByUserId)
                .triggeredAt(LocalDateTime.now())
                .triggerReason(triggerReason)
                .build();

        OrgWideSafetyCheck saved = safetyCheckRepo.save(check);
        log.info("横展開安否確認発動: organizationId={}, triggeredBy={}, id={}",
                organizationId, triggeredByUserId, saved.getId());

        return toDto(saved);
    }

    // ─────────────────────────────────────────────
    // 未クローズ安否確認の取得
    // ─────────────────────────────────────────────

    /**
     * 組織の未クローズな横展開安否確認一覧を取得する（ADMIN のみ）。
     *
     * @param organizationId テナント ID
     * @param requestUserId  操作ユーザー ID
     * @return 未クローズの横展開安否確認 DTO 一覧
     */
    public List<OrgWideSafetyCheckDto> getActiveChecks(Long organizationId, Long requestUserId) {
        // 権限確認: ADMIN のみ許可
        if (!accessControlService.isAdminOrAbove(requestUserId, organizationId, "ORGANIZATION")) {
            throw new BusinessException(ResidenceStatusErrorCode.DASHBOARD_ACCESS_FORBIDDEN);
        }

        return safetyCheckRepo
                .findByOrganizationIdAndClosedAtIsNullAndDeletedAtIsNull(organizationId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    // ─────────────────────────────────────────────
    // private ヘルパー
    // ─────────────────────────────────────────────

    /**
     * Entity → DTO 変換。
     */
    private OrgWideSafetyCheckDto toDto(OrgWideSafetyCheck e) {
        return OrgWideSafetyCheckDto.builder()
                .id(e.getId())
                .organizationId(e.getOrganizationId())
                .safetyCheckId(e.getSafetyCheckId() == PLACEHOLDER_SAFETY_CHECK_ID
                        ? null : e.getSafetyCheckId())
                .triggeredBy(e.getTriggeredBy())
                .triggeredAt(e.getTriggeredAt())
                .triggerReason(e.getTriggerReason())
                .closedAt(e.getClosedAt())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
