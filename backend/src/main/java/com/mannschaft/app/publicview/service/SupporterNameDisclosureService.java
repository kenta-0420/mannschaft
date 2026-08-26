package com.mannschaft.app.publicview.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.dto.NameDisclosureChangeLogResponse;
import com.mannschaft.app.publicview.dto.SupporterNameDisclosurePatchRequest;
import com.mannschaft.app.publicview.dto.SupporterNameDisclosureResponse;
import com.mannschaft.app.publicview.entity.OrganizationNameDisclosureChangeLogEntity;
import com.mannschaft.app.publicview.entity.TeamNameDisclosureChangeLogEntity;
import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.publicview.event.SupporterNameDisclosureChangedEvent;
import com.mannschaft.app.publicview.repository.OrganizationNameDisclosureChangeLogRepository;
import com.mannschaft.app.publicview.repository.TeamNameDisclosureChangeLogRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F19.1 Phase 2: supporter_name_disclosure 切替サービス。
 *
 * <p>ADMIN / SYSTEM_ADMIN が teams / organizations の投稿者識別モードを切り替える際の
 * ビジネスロジックを担当する。</p>
 *
 * <p>{@code confirmed=false} のリクエストは {@link PublicViewErrorCode#NAME_DISCLOSURE_CONFIRM_REQUIRED}
 * (400) で拒否する（設計書 §6.2）。</p>
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6 / §7.7</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupporterNameDisclosureService {

    // TODO: publicview ドメインが team / organization ドメインの Repository を直接参照。
    //   将来はイベント駆動化を検討（SupporterNameDisclosureChangedEvent 既存参照）。
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;
    private final TeamNameDisclosureChangeLogRepository teamChangeLogRepository;
    private final OrganizationNameDisclosureChangeLogRepository orgChangeLogRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AccessControlService accessControlService;

    /**
     * チームの supporter_name_disclosure を更新する。
     *
     * <p>{@code request.confirmed()} が {@code false} の場合は
     * {@link PublicViewErrorCode#NAME_DISCLOSURE_CONFIRM_REQUIRED} をスローする。</p>
     *
     * @param teamId          切替対象のチーム ID
     * @param operatorUserId  操作者のユーザー ID
     * @param request         切替リクエスト
     * @return 切替後の状態
     */
    @Transactional
    public SupporterNameDisclosureResponse patchTeamDisclosure(
            Long teamId,
            Long operatorUserId,
            SupporterNameDisclosurePatchRequest request) {

        // per-scope 認可（SYSTEM_ADMIN 短絡 or 当該チームの ADMIN/DEPUTY_ADMIN）
        checkScopeAdminAccess(operatorUserId, teamId, "TEAM");

        if (!request.confirmed()) {
            throw new BusinessException(PublicViewErrorCode.NAME_DISCLOSURE_CONFIRM_REQUIRED);
        }

        TeamEntity team = teamRepository.findById(teamId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.NAME_DISCLOSURE_NOT_FOUND));

        NameDisclosureMode oldMode = team.getSupporterNameDisclosure();
        NameDisclosureMode newMode = request.mode();

        // 同値更新の場合はログを記録せずそのまま返す
        if (oldMode == newMode) {
            log.debug("supporter_name_disclosure は変更なし: teamId={}, mode={}", teamId, oldMode);
            return new SupporterNameDisclosureResponse(oldMode, null);
        }

        // DB 更新（直接ミューテート・PR #1643 と同型）。
        // toBuilder().build()→save は継承フィールド id を引き継がず INSERT 化し、
        // slug 一意制約違反で 500 になるため使わない。
        team.updateSupporterNameDisclosure(newMode);
        teamRepository.save(team);

        // change_log INSERT
        LocalDateTime changedAt = LocalDateTime.now();
        TeamNameDisclosureChangeLogEntity changeLog = TeamNameDisclosureChangeLogEntity.builder()
                .teamId(teamId)
                .changedBy(operatorUserId)
                .oldMode(oldMode)
                .newMode(newMode)
                .confirmed(true)
                .changedAt(changedAt)
                .build();
        teamChangeLogRepository.save(changeLog);

        // イベント発行（リスナーは Phase 5 以降で追加予定）
        eventPublisher.publishEvent(new SupporterNameDisclosureChangedEvent(
                teamId, null, oldMode, newMode, operatorUserId));

        return new SupporterNameDisclosureResponse(newMode, changedAt);
    }

    /**
     * 組織の supporter_name_disclosure を更新する。
     *
     * @param organizationId  切替対象の組織 ID
     * @param operatorUserId  操作者のユーザー ID
     * @param request         切替リクエスト
     * @return 切替後の状態
     */
    @Transactional
    public SupporterNameDisclosureResponse patchOrganizationDisclosure(
            Long organizationId,
            Long operatorUserId,
            SupporterNameDisclosurePatchRequest request) {

        // per-scope 認可（SYSTEM_ADMIN 短絡 or 当該組織の ADMIN/DEPUTY_ADMIN）
        checkScopeAdminAccess(operatorUserId, organizationId, "ORGANIZATION");

        if (!request.confirmed()) {
            throw new BusinessException(PublicViewErrorCode.NAME_DISCLOSURE_CONFIRM_REQUIRED);
        }

        OrganizationEntity org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.NAME_DISCLOSURE_NOT_FOUND));

        NameDisclosureMode oldMode = org.getSupporterNameDisclosure();
        NameDisclosureMode newMode = request.mode();

        // 同値更新の場合はログを記録せずそのまま返す
        if (oldMode == newMode) {
            log.debug("supporter_name_disclosure は変更なし: organizationId={}, mode={}", organizationId, oldMode);
            return new SupporterNameDisclosureResponse(oldMode, null);
        }

        // DB 更新（直接ミューテート・PR #1643 と同型）。
        // toBuilder().build()→save は継承フィールド id を引き継がず INSERT 化し、
        // slug 一意制約違反で 500 になるため使わない。
        org.updateSupporterNameDisclosure(newMode);
        organizationRepository.save(org);

        // change_log INSERT
        LocalDateTime changedAt = LocalDateTime.now();
        OrganizationNameDisclosureChangeLogEntity logEntity = OrganizationNameDisclosureChangeLogEntity.builder()
                .organizationId(organizationId)
                .changedBy(operatorUserId)
                .oldMode(oldMode)
                .newMode(newMode)
                .confirmed(true)
                .changedAt(changedAt)
                .build();
        orgChangeLogRepository.save(logEntity);

        // イベント発行（リスナーは Phase 5 以降で追加予定）
        eventPublisher.publishEvent(new SupporterNameDisclosureChangedEvent(
                null, organizationId, oldMode, newMode, operatorUserId));

        return new SupporterNameDisclosureResponse(newMode, changedAt);
    }

    /**
     * チームの変更履歴を取得する（降順）。
     *
     * <p>設計書 §7.7「過去 1 年の切替履歴」に使用する。</p>
     *
     * @param teamId         チーム ID
     * @param operatorUserId 操作者のユーザー ID（per-scope 認可用）
     * @return 変更履歴リスト（変更日時降順）
     */
    @Transactional(readOnly = true)
    public List<NameDisclosureChangeLogResponse> getTeamChangeHistory(Long teamId, Long operatorUserId) {
        // per-scope 認可（SYSTEM_ADMIN 短絡 or 当該チームの ADMIN/DEPUTY_ADMIN）
        checkScopeAdminAccess(operatorUserId, teamId, "TEAM");

        return teamChangeLogRepository.findByTeamIdOrderByChangedAtDesc(teamId).stream()
                .map(e -> new NameDisclosureChangeLogResponse(
                        e.getId(),
                        e.getOldMode(),
                        e.getNewMode(),
                        e.isConfirmed(),
                        e.getChangedBy(),
                        e.getChangedAt()))
                .toList();
    }

    /**
     * 組織の変更履歴を取得する（降順）。
     *
     * @param organizationId 組織 ID
     * @param operatorUserId 操作者のユーザー ID（per-scope 認可用）
     * @return 変更履歴リスト（変更日時降順）
     */
    @Transactional(readOnly = true)
    public List<NameDisclosureChangeLogResponse> getOrganizationChangeHistory(Long organizationId, Long operatorUserId) {
        // per-scope 認可（SYSTEM_ADMIN 短絡 or 当該組織の ADMIN/DEPUTY_ADMIN）
        checkScopeAdminAccess(operatorUserId, organizationId, "ORGANIZATION");

        return orgChangeLogRepository.findByOrganizationIdOrderByChangedAtDesc(organizationId).stream()
                .map(e -> new NameDisclosureChangeLogResponse(
                        e.getId(),
                        e.getOldMode(),
                        e.getNewMode(),
                        e.isConfirmed(),
                        e.getChangedBy(),
                        e.getChangedAt()))
                .toList();
    }

    /**
     * 投稿者識別モード切替・履歴閲覧に対する per-scope 認可を強制する（認可根治 Phase 3-a）。
     *
     * <p>Controller の {@code @PreAuthorize} に加え、Service 層でも明示的に認可する。
     * SYSTEM_ADMIN は短絡的に許可し、それ以外は当該スコープの
     * ADMIN/DEPUTY_ADMIN でなければ {@code COMMON_002}（403）をスローする。</p>
     *
     * @param userId    操作ユーザー ID
     * @param scopeId   スコープ ID（チーム ID または組織 ID）
     * @param scopeType スコープ種別（"TEAM" / "ORGANIZATION"）
     */
    private void checkScopeAdminAccess(Long userId, Long scopeId, String scopeType) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);
    }
}
