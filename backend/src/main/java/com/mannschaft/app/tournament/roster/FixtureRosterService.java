package com.mannschaft.app.tournament.roster;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.repository.TeamUniformSetRepository;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentFixtureEntity;
import com.mannschaft.app.tournament.entity.TournamentFixtureRosterEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchdayEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entry.TournamentEntryTemplateEntity;
import com.mannschaft.app.tournament.entry.TournamentEntryTemplateMemberEntity;
import com.mannschaft.app.tournament.entry.TournamentEntryTemplateMemberRepository;
import com.mannschaft.app.tournament.entry.TournamentEntryTemplateRepository;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentFixtureRepository;
import com.mannschaft.app.tournament.repository.TournamentFixtureRosterRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchdayRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.roster.dto.ApplyRosterTemplateRequest;
import com.mannschaft.app.tournament.roster.dto.FixtureRosterResponse;
import com.mannschaft.app.tournament.roster.dto.OrganizerRosterView;
import com.mannschaft.app.tournament.roster.dto.RosterPlayerResponse;
import com.mannschaft.app.tournament.roster.dto.RosterStaffResponse;
import com.mannschaft.app.tournament.roster.dto.SubmitRosterRequest;
import com.mannschaft.app.tournament.roster.dto.UpdateFixtureRosterDeadlineRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 試合メンバー表サービス（F08.7.1/05）。
 *
 * <p>自チーム（チーム代表 ADMIN/DEPUTY）によるメンバー表の作成・提出（エントリーテンプレ流用）と、
 * 主催組織 ADMIN による締切設定・全チーム閲覧を担う。既存 {@code tournament_match_rosters}
 * （管理者向け一括 CRUD は {@code FixtureService}）を活用し、本サービスは自チーム提出フロー・
 * テンプレ適用・締切ロック・項目拡充（協会登録番号・ユニフォーム・ベンチ役員）を担当する。</p>
 *
 * <h2>認可（設計書 §5）</h2>
 * <ul>
 *   <li>自チーム roster の取得（rosters/me GET）: 当該チーム MEMBER 以上（対戦当事者チームのみ）。</li>
 *   <li>自チーム roster の提出/テンプレ適用（PUT / apply-template）: 当該チームの ADMIN/DEPUTY のみ。</li>
 *   <li>全チーム roster 閲覧（rosters GET）/ 締切設定（PATCH）: 主催組織 ADMIN / SYSTEM_ADMIN。</li>
 * </ul>
 *
 * <p>締切後ロック（{@code roster_deadline} 超過）の提出/適用は 409。存在しない match / 大会は 404（IDOR 統一）。
 * 提出は監査ログ（{@link AuditEventType#TOURNAMENT_ROSTER_SUBMITTED}）に残す。</p>
 *
 * <p>設計書: docs/features/F08.7.1_tournament_extensions/05_match_roster.md</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FixtureRosterService {

    private final TournamentRepository tournamentRepository;
    private final TournamentFixtureRepository matchRepository;
    private final TournamentMatchdayRepository matchdayRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final TournamentParticipantRepository participantRepository;
    private final TournamentFixtureRosterRepository rosterRepository;
    private final FixtureRosterStaffRepository staffRepository;
    private final TournamentEntryTemplateRepository templateRepository;
    private final TournamentEntryTemplateMemberRepository templateMemberRepository;
    private final TournamentEntryTemplateStaffRepository templateStaffRepository;
    private final TeamUniformSetRepository uniformSetRepository;
    private final AccessControlService accessControlService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    // ========================================================================
    // 自チーム分の取得・提出・テンプレ適用
    // ========================================================================

    /**
     * 自チーム分の現在のメンバー表を取得する（rosters/me GET・当該チーム MEMBER 以上）。
     */
    public FixtureRosterResponse getMyRoster(Long tournamentId, Long matchId, Long userId) {
        TournamentFixtureEntity match = resolveMatchInTournamentOrThrow(tournamentId, matchId);
        TournamentParticipantEntity participant = resolveMyParticipant(match, userId);
        requireTeamMember(userId, participant.getTeamId());
        return buildResponse(match, participant);
    }

    /**
     * 自チーム分メンバー表を提出する（UPSERT＝全置換・当該チーム ADMIN/DEPUTY のみ・締切後 409）。
     */
    @Transactional
    public FixtureRosterResponse submitMyRoster(Long tournamentId, Long matchId, Long userId,
                                              SubmitRosterRequest request) {
        TournamentFixtureEntity match = resolveMatchInTournamentOrThrow(tournamentId, matchId);
        TournamentParticipantEntity participant = resolveMyParticipant(match, userId);
        requireTeamRepresentative(userId, participant.getTeamId());
        requireNotPastDeadline(match);

        replacePlayers(match.getId(), participant, request, userId);
        replaceStaff(match.getId(), participant.getId(), request);

        recordSubmissionAudit(match, participant, userId);
        return buildResponse(match, participant);
    }

    /**
     * エントリーテンプレを自チーム分メンバー表へ適用する（テンプレ → roster 複製・ADMIN/DEPUTY のみ・締切後 409）。
     */
    @Transactional
    public FixtureRosterResponse applyTemplate(Long tournamentId, Long matchId, Long userId,
                                             ApplyRosterTemplateRequest request) {
        TournamentFixtureEntity match = resolveMatchInTournamentOrThrow(tournamentId, matchId);
        TournamentParticipantEntity participant = resolveMyParticipant(match, userId);
        requireTeamRepresentative(userId, participant.getTeamId());
        requireNotPastDeadline(match);

        // テンプレ取得＋自チーム所有確認（他チームのテンプレ適用は 404／IDOR）
        TournamentEntryTemplateEntity template = templateRepository
                .findByIdAndTeamIdAndDeletedAtIsNull(request.getTemplateId(), participant.getTeamId())
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.ENTRY_TEMPLATE_NOT_FOUND));

        // ユニフォーム既定セット指定時は自チームのものか検証（他チームのセットは 404）
        UUID defaultUniformSetId = resolveOwnedUniformSetOrNull(request.getDefaultUniformSetId(), participant.getTeamId());

        boolean hasExisting = !rosterRepository
                .findByMatchIdAndParticipantId(match.getId(), participant.getId()).isEmpty();
        if (hasExisting && !request.isOverwriteExisting()) {
            // 既存があり上書き指定なしなら現状維持（テンプレ未適用）
            return buildResponse(match, participant);
        }

        // 全置換（上書き or 新規）でテンプレを複製
        rosterRepository.deleteByMatchIdAndParticipantId(match.getId(), participant.getId());
        staffRepository.deleteByMatchIdAndParticipantId(match.getId(), participant.getId());

        List<TournamentEntryTemplateMemberEntity> members =
                templateMemberRepository.findByTemplateIdOrderBySortOrderAsc(template.getId());
        List<TournamentFixtureRosterEntity> newRosters = new ArrayList<>();
        for (TournamentEntryTemplateMemberEntity m : members) {
            newRosters.add(TournamentFixtureRosterEntity.builder()
                    .matchId(match.getId())
                    .participantId(participant.getId())
                    .userId(m.getUserId())
                    .isStarter(true)
                    .jerseyNumber(m.getJerseyNumber())
                    .position(m.getPosition())
                    .registrationNumber(m.getRegistrationNumber())
                    .uniformSetId(defaultUniformSetId)
                    .build());
        }
        if (!newRosters.isEmpty()) {
            rosterRepository.saveAll(newRosters);
        }

        List<TournamentEntryTemplateStaffEntity> templateStaff =
                templateStaffRepository.findByTemplateIdOrderBySortOrderAsc(template.getId());
        List<FixtureRosterStaffEntity> newStaff = new ArrayList<>();
        for (TournamentEntryTemplateStaffEntity s : templateStaff) {
            newStaff.add(FixtureRosterStaffEntity.builder()
                    .matchId(match.getId())
                    .participantId(participant.getId())
                    .role(s.getRole())
                    .name(s.getName())
                    .userId(s.getUserId())
                    .build());
        }
        if (!newStaff.isEmpty()) {
            staffRepository.saveAll(newStaff);
        }

        recordSubmissionAudit(match, participant, userId);
        log.info("メンバー表テンプレ適用: matchId={}, participantId={}, templateId={}, players={}, staff={}",
                match.getId(), participant.getId(), template.getId(), newRosters.size(), newStaff.size());
        return buildResponse(match, participant);
    }

    // ========================================================================
    // 主催組織 ADMIN: 全チーム閲覧・締切設定
    // ========================================================================

    /**
     * 全チーム分の提出状況・内容を閲覧する（主催者ビュー・主催組織 ADMIN / SYSTEM_ADMIN）。
     *
     * <p>全 read 経路で認可を通し、提出内容の漏洩を防ぐ（設計書 §4）。</p>
     */
    public List<OrganizerRosterView> listAllRosters(Long tournamentId, Long matchId, Long userId) {
        TournamentEntity tournament = findTournamentOrThrow(tournamentId);
        TournamentFixtureEntity match = resolveMatchInTournamentOrThrow(tournamentId, matchId);
        requireOrganizerAdmin(userId, tournament.getOrganizationId());

        TournamentMatchdayEntity matchday = matchdayRepository.findById(match.getMatchdayId())
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.MATCH_NOT_FOUND));

        List<Long> participantIds = new ArrayList<>();
        if (match.getHomeParticipantId() != null) participantIds.add(match.getHomeParticipantId());
        if (match.getAwayParticipantId() != null) participantIds.add(match.getAwayParticipantId());

        List<OrganizerRosterView> views = new ArrayList<>();
        for (Long pid : participantIds) {
            TournamentParticipantEntity participant = participantRepository.findById(pid).orElse(null);
            if (participant == null || !participant.getDivisionId().equals(matchday.getDivisionId())) {
                continue;
            }
            List<TournamentFixtureRosterEntity> rosters = rosterRepository
                    .findByMatchIdAndParticipantIdOrderByJerseyNumberAscIdAsc(match.getId(), pid);
            List<FixtureRosterStaffEntity> staff = staffRepository
                    .findByMatchIdAndParticipantIdOrderByCreatedAtAsc(match.getId(), pid);
            Map<Long, String> names = resolveDisplayNames(
                    rosters.stream().map(TournamentFixtureRosterEntity::getUserId).toList());

            List<RosterPlayerResponse> players = rosters.stream().map(r -> toPlayer(r, names)).toList();
            List<RosterStaffResponse> staffResponses = staff.stream().map(this::toStaff).toList();

            views.add(OrganizerRosterView.builder()
                    .participantId(pid)
                    .teamId(participant.getTeamId())
                    .teamDisplayName(participant.getDisplayName())
                    .submitted(!rosters.isEmpty())
                    .playerCount(players.size())
                    .staffCount(staffResponses.size())
                    .players(players)
                    .staff(staffResponses)
                    .build());
        }
        return views;
    }

    /**
     * 試合のメンバー表提出締切を設定する（主催組織 ADMIN / SYSTEM_ADMIN）。
     */
    @Transactional
    public void updateRosterDeadline(Long tournamentId, Long matchId, Long userId,
                                     UpdateFixtureRosterDeadlineRequest request) {
        TournamentEntity tournament = findTournamentOrThrow(tournamentId);
        TournamentFixtureEntity match = resolveMatchInTournamentOrThrow(tournamentId, matchId);
        requireOrganizerAdmin(userId, tournament.getOrganizationId());

        match.setRosterDeadline(request.getRosterDeadline());
        matchRepository.save(match);

        String metadata = String.format(
                "{\"source\":\"TOURNAMENT_MATCH_ROSTER\",\"match_id\":%d,\"roster_deadline\":%s}",
                matchId,
                request.getRosterDeadline() == null ? "null" : "\"" + request.getRosterDeadline() + "\"");
        auditLogService.record(AuditEventType.TOURNAMENT_ROSTER_DEADLINE_UPDATED.name(),
                userId, null, null, tournament.getOrganizationId(), null, null, null, metadata);
        log.info("メンバー表締切設定: matchId={}, deadline={}, by={}", matchId, request.getRosterDeadline(), userId);
    }

    // ========================================================================
    // 内部ヘルパー: 解決・認可・締切
    // ========================================================================

    private TournamentEntity findTournamentOrThrow(Long tournamentId) {
        return tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));
    }

    /**
     * IDOR 検証チェーン: matchId → matchday → division → tId 帰属を確認して match を返す。
     */
    private TournamentFixtureEntity resolveMatchInTournamentOrThrow(Long tournamentId, Long matchId) {
        TournamentFixtureEntity match = matchRepository.findById(matchId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.MATCH_NOT_FOUND));
        TournamentMatchdayEntity matchday = matchdayRepository.findById(match.getMatchdayId())
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.MATCH_NOT_FOUND));
        TournamentDivisionEntity division = divisionRepository.findById(matchday.getDivisionId())
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.MATCH_NOT_FOUND));
        // 他大会の match を渡す IDOR を 404 で弾く
        if (!division.getTournamentId().equals(tournamentId)) {
            throw new BusinessException(TournamentErrorCode.MATCH_NOT_FOUND);
        }
        return match;
    }

    /**
     * 呼び出しユーザーの所属チームが当該試合の対戦当事者（home/away participant）のどちらかであることを解決する。
     * いずれの当事者チームにも所属しなければ 403（ROSTER_TEAM_NOT_IN_MATCH）。
     */
    private TournamentParticipantEntity resolveMyParticipant(TournamentFixtureEntity match, Long userId) {
        if (userId == null) {
            throw new BusinessException(TournamentErrorCode.ROSTER_TEAM_NOT_IN_MATCH);
        }
        for (Long pid : new Long[]{match.getHomeParticipantId(), match.getAwayParticipantId()}) {
            if (pid == null) continue;
            TournamentParticipantEntity participant = participantRepository.findById(pid).orElse(null);
            if (participant != null
                    && accessControlService.isMember(userId, participant.getTeamId(), "TEAM")) {
                return participant;
            }
        }
        throw new BusinessException(TournamentErrorCode.ROSTER_TEAM_NOT_IN_MATCH);
    }

    private void requireTeamMember(Long userId, Long teamId) {
        if (userId == null || !accessControlService.isMember(userId, teamId, "TEAM")) {
            throw new BusinessException(TournamentErrorCode.ROSTER_TEAM_NOT_IN_MATCH);
        }
    }

    private void requireTeamRepresentative(Long userId, Long teamId) {
        if (userId == null || !accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            throw new BusinessException(TournamentErrorCode.ROSTER_EDIT_FORBIDDEN);
        }
    }

    private void requireOrganizerAdmin(Long userId, Long organizationId) {
        if (userId != null && accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (userId != null && accessControlService.isAdmin(userId, organizationId, "ORGANIZATION")) {
            return;
        }
        throw new BusinessException(TournamentErrorCode.ROSTER_MANAGE_FORBIDDEN);
    }

    private void requireNotPastDeadline(TournamentFixtureEntity match) {
        LocalDateTime deadline = match.getRosterDeadline();
        if (deadline != null && LocalDateTime.now().isAfter(deadline)) {
            throw new BusinessException(TournamentErrorCode.ROSTER_DEADLINE_PASSED);
        }
    }

    private UUID resolveOwnedUniformSetOrNull(UUID uniformSetId, Long teamId) {
        if (uniformSetId == null) {
            return null;
        }
        uniformSetRepository.findByIdAndTeamId(uniformSetId, teamId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.UNIFORM_SET_NOT_FOUND));
        return uniformSetId;
    }

    // ========================================================================
    // 内部ヘルパー: 永続化（全置換 UPSERT）
    // ========================================================================

    private void replacePlayers(Long matchId, TournamentParticipantEntity participant,
                                SubmitRosterRequest request, Long userId) {
        rosterRepository.deleteByMatchIdAndParticipantId(matchId, participant.getId());
        if (request.getPlayers() == null || request.getPlayers().isEmpty()) {
            return;
        }
        List<TournamentFixtureRosterEntity> rosters = new ArrayList<>();
        for (SubmitRosterRequest.PlayerEntry p : request.getPlayers()) {
            UUID uniformSetId = resolveOwnedUniformSetOrNull(p.getUniformSetId(), participant.getTeamId());
            rosters.add(TournamentFixtureRosterEntity.builder()
                    .matchId(matchId)
                    .participantId(participant.getId())
                    .userId(p.getUserId())
                    .isStarter(p.getIsStarter() != null ? p.getIsStarter() : true)
                    .jerseyNumber(p.getJerseyNumber())
                    .position(p.getPosition())
                    .registrationNumber(p.getRegistrationNumber())
                    .uniformSetId(uniformSetId)
                    .build());
        }
        rosterRepository.saveAll(rosters);
    }

    private void replaceStaff(Long matchId, Long participantId, SubmitRosterRequest request) {
        staffRepository.deleteByMatchIdAndParticipantId(matchId, participantId);
        if (request.getStaff() == null || request.getStaff().isEmpty()) {
            return;
        }
        List<FixtureRosterStaffEntity> staff = new ArrayList<>();
        for (SubmitRosterRequest.StaffEntry s : request.getStaff()) {
            staff.add(FixtureRosterStaffEntity.builder()
                    .matchId(matchId)
                    .participantId(participantId)
                    .role(s.getRole())
                    .name(s.getName())
                    .userId(s.getUserId())
                    .build());
        }
        staffRepository.saveAll(staff);
    }

    private void recordSubmissionAudit(TournamentFixtureEntity match, TournamentParticipantEntity participant,
                                       Long userId) {
        String metadata = String.format(
                "{\"source\":\"TOURNAMENT_MATCH_ROSTER\",\"match_id\":%d,\"participant_id\":%d,\"team_id\":%d}",
                match.getId(), participant.getId(), participant.getTeamId());
        auditLogService.record(AuditEventType.TOURNAMENT_ROSTER_SUBMITTED.name(),
                userId, null, participant.getTeamId(), null, null, null, null, metadata);
    }

    // ========================================================================
    // 内部ヘルパー: レスポンス組み立て
    // ========================================================================

    private FixtureRosterResponse buildResponse(TournamentFixtureEntity match,
                                              TournamentParticipantEntity participant) {
        List<TournamentFixtureRosterEntity> rosters = rosterRepository
                .findByMatchIdAndParticipantIdOrderByJerseyNumberAscIdAsc(match.getId(), participant.getId());
        List<FixtureRosterStaffEntity> staff = staffRepository
                .findByMatchIdAndParticipantIdOrderByCreatedAtAsc(match.getId(), participant.getId());
        Map<Long, String> names = resolveDisplayNames(
                rosters.stream().map(TournamentFixtureRosterEntity::getUserId).toList());

        boolean locked = match.getRosterDeadline() != null
                && LocalDateTime.now().isAfter(match.getRosterDeadline());

        return FixtureRosterResponse.builder()
                .matchId(match.getId())
                .participantId(participant.getId())
                .teamId(participant.getTeamId())
                .rosterDeadline(match.getRosterDeadline())
                .locked(locked)
                .players(rosters.stream().map(r -> toPlayer(r, names)).toList())
                .staff(staff.stream().map(this::toStaff).toList())
                .build();
    }

    private RosterPlayerResponse toPlayer(TournamentFixtureRosterEntity r, Map<Long, String> names) {
        return RosterPlayerResponse.builder()
                .id(r.getId())
                .userId(r.getUserId())
                .displayName(names.getOrDefault(r.getUserId(), "userId=" + r.getUserId()))
                .isStarter(r.getIsStarter())
                .jerseyNumber(r.getJerseyNumber())
                .position(r.getPosition())
                .registrationNumber(r.getRegistrationNumber())
                .uniformSetId(r.getUniformSetId())
                .build();
    }

    private RosterStaffResponse toStaff(FixtureRosterStaffEntity s) {
        return RosterStaffResponse.builder()
                .id(s.getId())
                .role(s.getRole())
                .name(s.getName())
                .userId(s.getUserId())
                .build();
    }

    private Map<Long, String> resolveDisplayNames(List<Long> userIds) {
        Map<Long, String> result = new HashMap<>();
        for (Long userId : userIds) {
            if (userId == null || result.containsKey(userId)) {
                continue;
            }
            String displayName = userRepository.findMemberSummaryById(userId)
                    .map(UserRepository.MemberSummary::getDisplayName)
                    .orElse("userId=" + userId);
            result.put(userId, displayName);
        }
        return result;
    }
}
