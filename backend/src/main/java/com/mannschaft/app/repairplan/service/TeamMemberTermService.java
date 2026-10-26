package com.mannschaft.app.repairplan.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.repairplan.RepairPlanErrorCode;
import com.mannschaft.app.repairplan.dto.CreateTermRequest;
import com.mannschaft.app.repairplan.dto.TermDto;
import com.mannschaft.app.repairplan.entity.TeamMemberTerm;
import com.mannschaft.app.repairplan.repository.TeamMemberTermRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 理事任期サービス（F08.8 Phase 5）。
 *
 * <p>申し送りパック生成の基盤となる理事任期 CRUD を提供する。
 * 任期データは履歴として永続するため論理削除を持たず、
 * is_active フラグで現役・退任を区別する。</p>
 *
 * <h2>ドメイン境界</h2>
 * <p>user ドメインの表示名取得は user_id → クロスドメイン参照になるため、
 * TermDto の {@code userDisplayName} は現在は user_id の文字列表現を返す。
 * 将来フェーズで UserQueryService.getDisplayName() 経由で解決予定。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TeamMemberTermService {

    private final TeamMemberTermRepository termRepository;
    private final AccessControlService accessControlService;

    // =========================================================================
    // 任期作成
    // =========================================================================

    /**
     * 理事任期を作成する（ADMIN/DEPUTY_ADMIN 以上）。
     *
     * @param teamId         チーム ID
     * @param organizationId テナント組織 ID
     * @param req            作成リクエスト
     * @param requesterId    リクエスト者 ID
     * @return 作成した任期の DTO
     */
    @Transactional
    public TermDto createTerm(Long teamId, Long organizationId, CreateTermRequest req, Long requesterId) {
        accessControlService.checkAdminOrAbove(requesterId, teamId, "TEAM");

        TeamMemberTerm term = TeamMemberTerm.builder()
                .organizationId(organizationId)
                .scopeType("TEAM")
                .scopeId(teamId)
                .userId(req.userId())
                .roleLabel(req.roleName() != null ? req.roleName() : "理事")
                .termStart(req.termStart())
                .termEnd(req.termEnd())
                .isActive(true)
                .build();

        term = termRepository.save(term);
        log.info("理事任期作成: id={}, teamId={}, userId={}", term.getId(), teamId, req.userId());

        return toDto(term);
    }

    // =========================================================================
    // 任期一覧取得
    // =========================================================================

    /**
     * チーム単位の任期一覧を返す（メンバーシップ必須）。
     */
    public List<TermDto> listTerms(Long teamId, Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");

        return termRepository.findByScopeTypeAndScopeIdAndIsActiveTrueOrderByTermEndAsc("TEAM", teamId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    // =========================================================================
    // 任期取得
    // =========================================================================

    /**
     * 任期を 1 件取得する（メンバーシップ必須）。
     */
    public TermDto getTerm(UUID termId, Long teamId, Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");

        TeamMemberTerm term = findTermOrThrow(termId, teamId);
        return toDto(term);
    }

    // =========================================================================
    // 任期削除（is_active = false）
    // =========================================================================

    /**
     * 任期を非アクティブ化する（論理削除相当: is_active = false）。
     *
     * <p>任期データは履歴として永続するため物理削除は行わない。
     * is_active = false にすることで「退任済み」として扱う。</p>
     *
     * @param termId         任期 ID
     * @param teamId         チーム ID
     * @param organizationId テナント組織 ID
     * @param requesterId    リクエスト者 ID
     */
    @Transactional
    public void deleteTerm(UUID termId, Long teamId, Long organizationId, Long requesterId) {
        accessControlService.checkAdminOrAbove(requesterId, teamId, "TEAM");

        TeamMemberTerm term = findTermOrThrow(termId, teamId);
        term.setIsActive(false);
        termRepository.save(term);

        log.info("理事任期非アクティブ化: id={}, teamId={}, requesterId={}", termId, teamId, requesterId);
    }

    // =========================================================================
    // バッチ用クエリ
    // =========================================================================

    /**
     * 指定チームのアクティブな任期一覧を返す（バッチ用）。
     */
    public List<TeamMemberTerm> findActiveTerms(Long teamId) {
        return termRepository.findByScopeTypeAndScopeIdAndIsActiveTrueOrderByTermEndAsc("TEAM", teamId);
    }

    /**
     * 全チームで指定日数以内に任期終了を迎えるアクティブ任期を返す（リマインドバッチ用）。
     *
     * @param days 何日以内（例: 30 なら 30 日以内に term_end を迎える）
     * @return 対象任期リスト
     */
    public List<TeamMemberTerm> findTermsEndingWithinDays(int days) {
        LocalDate today = LocalDate.now();
        LocalDate deadline = today.plusDays(days);
        return termRepository.findByIsActiveTrueAndTermEndBetween(today, deadline);
    }

    // =========================================================================
    // 内部ヘルパー
    // =========================================================================

    private TeamMemberTerm findTermOrThrow(UUID termId, Long teamId) {
        return termRepository.findById(termId)
                .filter(t -> t.getScopeId().equals(teamId) && "TEAM".equals(t.getScopeType()))
                .orElseThrow(() -> new BusinessException(RepairPlanErrorCode.TERM_NOT_FOUND));
    }

    private TermDto toDto(TeamMemberTerm term) {
        // userDisplayName はクロスドメイン依存を避けるため userId の文字列表現を使用
        // TODO: 将来フェーズで UserQueryService.getDisplayName(term.getUserId()) で解決
        String userDisplayName = "userId=" + term.getUserId();

        return new TermDto(
                term.getId(),
                term.getScopeId(),
                term.getScopeType(),
                term.getOrganizationId(),
                term.getUserId(),
                userDisplayName,
                term.getTermStart(),
                term.getTermEnd(),
                term.getRoleLabel(),
                Boolean.TRUE.equals(term.getIsActive())
        );
    }
}
