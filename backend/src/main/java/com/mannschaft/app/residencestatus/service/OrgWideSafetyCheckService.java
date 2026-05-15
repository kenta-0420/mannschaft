package com.mannschaft.app.residencestatus.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.residencestatus.ResidenceStatusErrorCode;
import com.mannschaft.app.residencestatus.dto.OrgWideSafetyCheckDto;
import com.mannschaft.app.residencestatus.entity.OrgWideSafetyCheck;
import com.mannschaft.app.residencestatus.repository.OrgWideSafetyCheckRepository;
import com.mannschaft.app.safetycheck.dto.CreateSafetyCheckRequest;
import com.mannschaft.app.safetycheck.dto.SafetyCheckResponse;
import com.mannschaft.app.safetycheck.entity.SafetyCheckSourceType;
import com.mannschaft.app.safetycheck.service.SafetyCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F09.16 S3-C/S5-A 管理組合横展開安否確認サービス。
 *
 * <p>理事長（ADMIN）が組織全体に安否確認を発動する際のメタ情報を管理する。
 * 実際の安否確認ロジック（回答収集等）は F03.6 ドメインに委ねる。</p>
 *
 * <p>S5-A 以降は F03.6 {@code SafetyCheckService.createSafetyCheck()} を正式に呼び出す。
 * {@code @Transactional} が residencestatus と safetycheck ドメインをまたいでいる点については、
 * 将来 {@code OrgWideSafetyCheckTriggeredEvent} でイベント駆動分離を行う予定（CLAUDE.md 原則 5 参照）。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrgWideSafetyCheckService {

    private final OrgWideSafetyCheckRepository safetyCheckRepo;
    private final AccessControlService accessControlService;
    private final SafetyCheckService safetyCheckService;

    // ─────────────────────────────────────────────
    // 横展開安否確認の発動
    // ─────────────────────────────────────────────

    /**
     * 管理組合横展開安否確認を発動する（ADMIN のみ）。
     *
     * <p>F03.6 {@code SafetyCheckService.createSafetyCheck()} を呼び出して実際の安否確認を作成し、
     * 返却された ID をメタ情報として本テーブルに保存する。
     * {@code source_type = ORG_WIDE} により F03.6 側でも発動源を識別可能。</p>
     *
     * @param organizationId    テナント ID
     * @param triggeredByUserId 発動者（理事長）ユーザー ID
     * @param triggerReason     発動理由（地震・火災・組合判断等）
     * @return 作成された横展開安否確認 DTO
     */
    @Transactional
    // TODO: residencestatusドメインとsafetycheckドメインをまたぐ@Transactional。将来はOrgWideSafetyCheckTriggeredEventで分離予定
    public OrgWideSafetyCheckDto triggerOrgWideSafetyCheck(
            Long organizationId, Long triggeredByUserId, String triggerReason) {

        // 権限確認: ADMIN のみ許可（DEPUTY_ADMIN は不可）
        if (!accessControlService.isAdminOrAbove(triggeredByUserId, organizationId, "ORGANIZATION")) {
            throw new BusinessException(ResidenceStatusErrorCode.DASHBOARD_ACCESS_FORBIDDEN);
        }

        // F03.6 安否確認セッションを作成する（ORGANIZATION スコープ、source_type = ORG_WIDE）
        CreateSafetyCheckRequest safetyCheckReq = new CreateSafetyCheckRequest(
                "居住実態管理 一斉安否確認",
                "管理組合より一斉安否確認を実施しています。ご回答をお願いします。",
                "ORGANIZATION",
                organizationId,
                false,
                null,
                null
        );
        safetyCheckReq.setSourceType(SafetyCheckSourceType.ORG_WIDE);

        SafetyCheckResponse safetyCheckResponse =
                safetyCheckService.createSafetyCheck(safetyCheckReq, triggeredByUserId);
        Long safetyCheckId = safetyCheckResponse.getId();

        log.info("F03.6 安否確認セッション作成: safetyCheckId={}, organizationId={}",
                safetyCheckId, organizationId);

        OrgWideSafetyCheck check = OrgWideSafetyCheck.builder()
                .organizationId(organizationId)
                .safetyCheckId(safetyCheckId)
                .triggeredBy(triggeredByUserId)
                .triggeredAt(LocalDateTime.now())
                .triggerReason(triggerReason)
                .build();

        OrgWideSafetyCheck saved = safetyCheckRepo.save(check);
        log.info("横展開安否確認発動: organizationId={}, triggeredBy={}, id={}, safetyCheckId={}",
                organizationId, triggeredByUserId, saved.getId(), safetyCheckId);

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
                .safetyCheckId(e.getSafetyCheckId())
                .triggeredBy(e.getTriggeredBy())
                .triggeredAt(e.getTriggeredAt())
                .triggerReason(e.getTriggerReason())
                .closedAt(e.getClosedAt())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
