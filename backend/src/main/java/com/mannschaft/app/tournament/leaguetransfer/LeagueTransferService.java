package com.mannschaft.app.tournament.leaguetransfer;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.service.OrganizationHierarchyService;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.tournament.ParticipantStatus;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entity.TournamentStandingEntity;
import com.mannschaft.app.tournament.leaguetransfer.dto.LeagueTransferResponse;
import com.mannschaft.app.tournament.leaguetransfer.dto.PromoteRequest;
import com.mannschaft.app.tournament.leaguetransfer.dto.RelegateRequest;
import com.mannschaft.app.tournament.leaguetransfer.dto.TransferCandidateResponse;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStandingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * リーグ移籍サービス（F08.7.1 / 03）。組織をまたぐ昇降格を「プッシュ＋承認」の対称モデルで担う。
 *
 * <h2>責務（§2.1）</h2>
 * <ul>
 *   <li>同一大会内の部間昇降格は本サービスの対象外（既存 {@code PromotionService} が担当）。</li>
 *   <li>組織またぎの昇降格（最上位部の昇格枠 / 最下位部の降格枠）のみを担う。{@code getPromotionPreview} は
 *       境界部の枠を返さない（O-2・§3.3）ため、最上位/最下位ディビジョンの {@code promotion_slots} /
 *       {@code relegation_slots} ＋ {@code tournament_standings} から独自に境界枠を判定する。</li>
 * </ul>
 *
 * <h2>認可（§7）</h2>
 * <ul>
 *   <li>送り出し（promote/relegate）: 手放す側 org ADMIN（昇格=下位 org / 降格=上位 org）／ SYSTEM_ADMIN。</li>
 *   <li>承認・拒否（approve/decline）: 受け入れ側 org ADMIN ／ SYSTEM_ADMIN。</li>
 *   <li>取消（cancel）: 手放す側 org ADMIN ／ SYSTEM_ADMIN（DISPATCHED のときのみ）。</li>
 *   <li>チーム側閲覧（listTeamTransfers）: 当該チーム MEMBER 以上。</li>
 *   <li>親子関係を {@link OrganizationHierarchyService} で必須検証し、無関係 org へは送れない／操作できない。</li>
 *   <li>存在しない・他 org のスコープは一律 404（IDOR 対策）。</li>
 * </ul>
 *
 * <p><strong>越境（原則5）TODO:</strong> 本サービスは tournament ドメインから organization ドメインの
 * {@link OrganizationHierarchyService} / {@link OrganizationRepository}、team ドメインの
 * {@link TeamOrgMembershipRepository} を ID 参照（FK なし・原則1）で直接呼ぶ。組織階層の真偽判定・
 * チーム所属の解決は読み取り主体で結合度が低いため当面は直接呼び出しとし、将来は移籍イベント駆動化を検討する。</p>
 *
 * <p>通知（受け入れ側 org / チーム受信箱）は既存 F04.3 プッシュ通知 / F04.1 タイムラインを再利用する想定だが、
 * 本サービスでは越境発火を最小化するため、DISPATCHED 起票の事実を {@code @Slf4j} に記録するに留める。
 * 通知配信の連結は別 Phase（イベントリスナー）で実装する（症状を隠さず TODO として明示）。</p>
 *
 * <p>設計書: docs/features/F08.7.1_tournament_extensions/03_league_pyramid_and_transfer.md</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LeagueTransferService {

    private final LeagueTransferRepository transferRepository;
    private final TournamentRepository tournamentRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final TournamentStandingRepository standingRepository;
    private final TournamentParticipantRepository participantRepository;
    private final AccessControlService accessControlService;
    // --- 越境（原則5 TODO・上記クラスコメント参照） ---
    private final OrganizationHierarchyService organizationHierarchyService;
    private final OrganizationRepository organizationRepository;
    private final TeamOrgMembershipRepository teamOrgMembershipRepository;

    // ========================================================================
    // 候補導出（境界部の昇格枠/降格枠・テーブルレス・§3.3 / §6）
    // ========================================================================

    /**
     * 当該大会の境界部（最上位部の昇格枠 / 最下位部の降格枠）チームを {@code tournament_standings} ＋
     * {@code promotion_slots} / {@code relegation_slots} から独自判定し、組織階層で送り先 org を解決して導出する。
     *
     * @param organizationId 主催（手放す側）org ID
     * @param tournamentId   対象大会 ID
     * @param direction      PROMOTION / RELEGATION
     * @param userId         操作ユーザー（手放す側 org ADMIN）
     * @return 候補一覧（送り先 org 解決不能なら resolvedTargetOrganizationId=null で返す）
     */
    public List<TransferCandidateResponse> getTransferCandidates(
            Long organizationId, Long tournamentId, LeagueTransferDirection direction, Long userId) {
        findTournamentInOrgOrThrow(organizationId, tournamentId);
        requireOrganizerAdmin(userId, organizationId);

        List<TournamentDivisionEntity> divisions =
                divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(tournamentId);
        if (divisions.isEmpty()) {
            return List.of();
        }

        List<TransferCandidateResponse> candidates = new ArrayList<>();
        if (direction == LeagueTransferDirection.PROMOTION) {
            // 最上位ディビジョン（level/sortOrder 昇順の先頭）の昇格枠
            TournamentDivisionEntity top = divisions.get(0);
            Long resolvedTarget = resolvePromotionTarget(organizationId, null);
            for (TournamentParticipantEntity p : boundarySlotTeams(top, direction)) {
                candidates.add(new TransferCandidateResponse(
                        p.getTeamId(), top.getId(), top.getName(), direction.name(),
                        rankOf(top.getId(), p.getId()),
                        resolvedTarget));
            }
        } else {
            // 最下位ディビジョン（末尾）の降格枠
            TournamentDivisionEntity bottom = divisions.get(divisions.size() - 1);
            for (TournamentParticipantEntity p : boundarySlotTeams(bottom, direction)) {
                Long resolvedTarget = resolveRelegationTargetOrNull(organizationId, p.getTeamId());
                candidates.add(new TransferCandidateResponse(
                        p.getTeamId(), bottom.getId(), bottom.getName(), direction.name(),
                        rankOf(bottom.getId(), p.getId()),
                        resolvedTarget));
            }
        }
        return candidates;
    }

    // ========================================================================
    // 送り出し（DISPATCHED 起票・§4 / §5）
    // ========================================================================

    /**
     * 昇格送り出し（下位 org ADMIN）。最上位部の昇格枠チームを上位 org へ DISPATCHED 起票する（§4）。
     *
     * @throws BusinessException LEAGUE_TRANSFER_DISPATCH_FORBIDDEN（403）／TOURNAMENT_NOT_FOUND（404）／
     *                           LEAGUE_TRANSFER_TARGET_NOT_RESOLVABLE（祖先 org 不在）／
     *                           LEAGUE_TRANSFER_TEAM_NOT_IN_SLOT（昇格枠外）／
     *                           LEAGUE_TRANSFER_ALREADY_DISPATCHED（二重起票）
     */
    @Transactional
    public List<LeagueTransferResponse> promote(Long organizationId, Long tournamentId, Long userId,
                                                PromoteRequest request) {
        TournamentEntity tournament = findTournamentInOrgOrThrow(organizationId, tournamentId);
        requireOrganizerAdmin(userId, organizationId);

        // 送り先（上位 org）解決＝送り出し元の祖先 org（明示指定があれば祖先であることを検証）
        Long targetOrgId = resolvePromotionTarget(organizationId, request.getTargetOrganizationId());
        if (targetOrgId == null) {
            // 0 件（親 org なし）は症状を握りつぶさず例外化（§5.3）
            throw new BusinessException(TournamentErrorCode.LEAGUE_TRANSFER_TARGET_NOT_RESOLVABLE);
        }

        TournamentDivisionEntity top = topDivisionOrThrow(tournamentId);
        String season = seasonOf(tournament);

        List<LeagueTransferResponse> results = new ArrayList<>();
        for (Long teamId : distinct(request.getTeamIds())) {
            TournamentParticipantEntity participant = requireTeamInBoundarySlot(top, teamId,
                    LeagueTransferDirection.PROMOTION);
            results.add(dispatch(LeagueTransferDirection.PROMOTION, teamId, organizationId, targetOrgId,
                    top.getId(), season, rankOf(top.getId(), participant.getId()), userId, request.getMessage()));
        }
        return results;
    }

    /**
     * 降格送り出し（上位 org ADMIN）。最下位部の降格枠チームを各チームの出身県協会へ DISPATCHED 起票する（§5）。
     *
     * @throws BusinessException LEAGUE_TRANSFER_DISPATCH_FORBIDDEN（403）／TOURNAMENT_NOT_FOUND（404）／
     *                           LEAGUE_TRANSFER_TARGET_NOT_RESOLVABLE（出身県協会 0 件/不明）／
     *                           LEAGUE_TRANSFER_TEAM_NOT_IN_SLOT（降格枠外）／
     *                           LEAGUE_TRANSFER_ALREADY_DISPATCHED（二重起票）
     */
    @Transactional
    public List<LeagueTransferResponse> relegate(Long organizationId, Long tournamentId, Long userId,
                                                 RelegateRequest request) {
        TournamentEntity tournament = findTournamentInOrgOrThrow(organizationId, tournamentId);
        requireOrganizerAdmin(userId, organizationId);

        TournamentDivisionEntity bottom = bottomDivisionOrThrow(tournamentId);
        String season = seasonOf(tournament);

        List<LeagueTransferResponse> results = new ArrayList<>();
        for (Long teamId : distinct(request.getTeamIds())) {
            TournamentParticipantEntity participant = requireTeamInBoundarySlot(bottom, teamId,
                    LeagueTransferDirection.RELEGATION);
            // 出身県協会＝送り出し元 org の子孫 ASSOCIATION（0 件/複数不明は例外化・§5.2）
            Long targetOrgId = resolveRelegationTargetOrThrow(organizationId, teamId);
            results.add(dispatch(LeagueTransferDirection.RELEGATION, teamId, organizationId, targetOrgId,
                    bottom.getId(), season, rankOf(bottom.getId(), participant.getId()), userId, request.getMessage()));
        }
        return results;
    }

    // ========================================================================
    // 受信箱（受け入れ側 org・§6）
    // ========================================================================

    /**
     * 受信箱：自 org が {@code to_organization_id} の DISPATCHED 一覧（受け入れ側 org ADMIN）。
     *
     * @param organizationId 受け入れ側 org ID
     * @param direction      絞り込み（NULL なら両方向）
     * @param userId         操作ユーザー（受け入れ側 org ADMIN）
     */
    public List<LeagueTransferResponse> listInbound(Long organizationId, LeagueTransferDirection direction,
                                                    Long userId) {
        requireOrganizerAdmin(userId, organizationId);
        List<LeagueTransferEntity> rows = (direction == null)
                ? transferRepository.findByToOrganizationIdAndStatusOrderByCreatedAtDesc(
                        organizationId, LeagueTransferStatus.DISPATCHED)
                : transferRepository.findByToOrganizationIdAndDirectionAndStatusOrderByCreatedAtDesc(
                        organizationId, direction, LeagueTransferStatus.DISPATCHED);
        return rows.stream().map(LeagueTransferResponse::of).toList();
    }

    // ========================================================================
    // 応答（承認・拒否・取消・§6 / §3.2）
    // ========================================================================

    /**
     * 受け入れ承認＝配属（受け入れ側 org ADMIN）。{@code target_division_id} セット・status=PLACED・
     * 当該ディビジョンに {@code tournament_participant} を REGISTERED で作成する（Y-4・§4-4）。
     *
     * <p>承認 EP の {@code divId → tId → orgId}（受け入れ側 org）の帰属を IDOR チェーン検証する（§6）。</p>
     *
     * @throws BusinessException LEAGUE_TRANSFER_NOT_FOUND（404）／LEAGUE_TRANSFER_RESPOND_FORBIDDEN（403）／
     *                           LEAGUE_TRANSFER_NOT_DISPATCHED（状態違反）／TOURNAMENT_NOT_FOUND/DIVISION_NOT_FOUND（404）
     */
    @Transactional
    public LeagueTransferResponse approve(Long organizationId, Long tournamentId, Long divisionId,
                                          UUID transferId, Long userId) {
        // 受け入れ側 org が divId → tId → orgId のチェーンを満たすこと（IDOR）
        findTournamentInOrgOrThrow(organizationId, tournamentId);
        TournamentDivisionEntity division = verifyDivisionBelongsToTournament(divisionId, tournamentId);

        LeagueTransferEntity transfer = findInboundTransferOrThrow(transferId, organizationId);
        requireRespondAdmin(userId, organizationId);
        requireDispatched(transfer);

        transfer.place(division.getId(), userId);
        transferRepository.save(transfer);

        // tournament_participant を REGISTERED で作成（PLACED は transfer の状態・participant は REGISTERED）
        TournamentParticipantEntity participant = participantRepository
                .findByDivisionIdAndTeamId(division.getId(), transfer.getTeamId())
                .orElse(null);
        if (participant == null) {
            participantRepository.save(TournamentParticipantEntity.builder()
                    .divisionId(division.getId())
                    .teamId(transfer.getTeamId())
                    .status(ParticipantStatus.REGISTERED)
                    .build());
        }

        log.info("リーグ移籍 承認・配属: transferId={}, divisionId={}, teamId={}, respondedBy={}",
                transferId, division.getId(), transfer.getTeamId(), userId);
        return LeagueTransferResponse.of(transfer);
    }

    /**
     * 受け入れ拒否（受け入れ側 org ADMIN）→ status=DECLINED。
     */
    @Transactional
    public LeagueTransferResponse decline(Long organizationId, UUID transferId, Long userId) {
        LeagueTransferEntity transfer = findInboundTransferOrThrow(transferId, organizationId);
        requireRespondAdmin(userId, organizationId);
        requireDispatched(transfer);

        transfer.decline(userId);
        transferRepository.save(transfer);
        log.info("リーグ移籍 受け入れ拒否: transferId={}, respondedBy={}", transferId, userId);
        return LeagueTransferResponse.of(transfer);
    }

    /**
     * 送り出し取消（手放す側 org ADMIN・応答前のみ）→ status=CANCELLED。
     */
    @Transactional
    public LeagueTransferResponse cancel(Long organizationId, UUID transferId, Long userId) {
        // 取消は手放す側 org（from_organization_id）の ADMIN のみ
        LeagueTransferEntity transfer = findOutboundTransferOrThrow(transferId, organizationId);
        requireOrganizerAdmin(userId, organizationId);
        requireDispatched(transfer);

        transfer.cancel(userId);
        transferRepository.save(transfer);
        log.info("リーグ移籍 送り出し取消: transferId={}, cancelledBy={}", transferId, userId);
        return LeagueTransferResponse.of(transfer);
    }

    // ========================================================================
    // チーム側閲覧（当該チーム MEMBER 以上・閲覧のみ・§6 / §7）
    // ========================================================================

    /**
     * チーム側：自チームの送り出し/受入状況を閲覧する（当該チーム MEMBER 以上・読み取り専用）。
     *
     * @throws BusinessException LEAGUE_TRANSFER_VIEW_FORBIDDEN（403・非メンバー）
     */
    public List<LeagueTransferResponse> listTeamTransfers(Long teamId, Long userId) {
        requireTeamMember(userId, teamId);
        return transferRepository.findByTeamIdOrderByCreatedAtDesc(teamId).stream()
                .map(LeagueTransferResponse::of)
                .toList();
    }

    // ========================================================================
    // 内部ヘルパー — 起票・状態・帰属
    // ========================================================================

    private LeagueTransferResponse dispatch(LeagueTransferDirection direction, Long teamId,
                                            Long fromOrgId, Long toOrgId, Long sourceDivisionId,
                                            String season, Integer finalRank, Long userId, String message) {
        // 二重起票抑止（UNIQUE(team_id, season, direction) の事前判定・§7）
        transferRepository.findByTeamIdAndSeasonAndDirection(teamId, season, direction)
                .ifPresent(existing -> {
                    throw new BusinessException(TournamentErrorCode.LEAGUE_TRANSFER_ALREADY_DISPATCHED);
                });

        LeagueTransferEntity transfer = LeagueTransferEntity.builder()
                .direction(direction)
                .teamId(teamId)
                .fromOrganizationId(fromOrgId)
                .toOrganizationId(toOrgId)
                .sourceDivisionId(sourceDivisionId)
                .season(season)
                .finalRank(finalRank)
                .status(LeagueTransferStatus.DISPATCHED)
                .initiatedBy(userId)
                .message(message)
                .build();
        LeagueTransferEntity saved = transferRepository.save(transfer);

        // TODO（通知再利用・原則5）: 受け入れ側 org（to_organization_id）/ チーム受信箱へ
        //   F04.3 プッシュ通知 / F04.1 タイムラインで DISPATCHED を通知する。別 Phase でイベント駆動化。
        log.info("リーグ移籍 送り出し起票: transferId={}, direction={}, teamId={}, fromOrg={}, toOrg={}, season={}",
                saved.getId(), direction, teamId, fromOrgId, toOrgId, season);
        return LeagueTransferResponse.of(saved);
    }

    private void requireDispatched(LeagueTransferEntity transfer) {
        if (!transfer.isDispatched()) {
            throw new BusinessException(TournamentErrorCode.LEAGUE_TRANSFER_NOT_DISPATCHED);
        }
    }

    /** 受信箱側（to_organization_id がスコープ org）。他 org の移籍は 404（IDOR）。 */
    private LeagueTransferEntity findInboundTransferOrThrow(UUID transferId, Long organizationId) {
        LeagueTransferEntity transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.LEAGUE_TRANSFER_NOT_FOUND));
        if (!transfer.getToOrganizationId().equals(organizationId)) {
            throw new BusinessException(TournamentErrorCode.LEAGUE_TRANSFER_NOT_FOUND);
        }
        return transfer;
    }

    /** 送り出し側（from_organization_id がスコープ org）。他 org の移籍は 404（IDOR）。 */
    private LeagueTransferEntity findOutboundTransferOrThrow(UUID transferId, Long organizationId) {
        LeagueTransferEntity transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.LEAGUE_TRANSFER_NOT_FOUND));
        if (!transfer.getFromOrganizationId().equals(organizationId)) {
            throw new BusinessException(TournamentErrorCode.LEAGUE_TRANSFER_NOT_FOUND);
        }
        return transfer;
    }

    // ========================================================================
    // 内部ヘルパー — 境界枠判定（§3.3）
    // ========================================================================

    private TournamentDivisionEntity topDivisionOrThrow(Long tournamentId) {
        List<TournamentDivisionEntity> divisions =
                divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(tournamentId);
        if (divisions.isEmpty()) {
            throw new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND);
        }
        return divisions.get(0);
    }

    private TournamentDivisionEntity bottomDivisionOrThrow(Long tournamentId) {
        List<TournamentDivisionEntity> divisions =
                divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(tournamentId);
        if (divisions.isEmpty()) {
            throw new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND);
        }
        return divisions.get(divisions.size() - 1);
    }

    /**
     * 境界部ディビジョンの昇格枠/降格枠に該当する参加チームを {@code tournament_standings} ＋ slots から判定する。
     *
     * <ul>
     *   <li>昇格枠（最上位部）: rank ≤ {@code promotion_slots}。</li>
     *   <li>降格枠（最下位部）: rank > {@code 総数 - relegation_slots}。</li>
     * </ul>
     */
    private List<TournamentParticipantEntity> boundarySlotTeams(TournamentDivisionEntity division,
                                                                LeagueTransferDirection direction) {
        List<TournamentStandingEntity> standings =
                standingRepository.findByDivisionIdOrderByRankAsc(division.getId());
        if (standings.isEmpty()) {
            return List.of();
        }
        List<TournamentParticipantEntity> result = new ArrayList<>();
        int total = standings.size();
        for (TournamentStandingEntity s : standings) {
            boolean inSlot = (direction == LeagueTransferDirection.PROMOTION)
                    ? (division.getPromotionSlots() != null && division.getPromotionSlots() > 0
                            && s.getRank() <= division.getPromotionSlots())
                    : (division.getRelegationSlots() != null && division.getRelegationSlots() > 0
                            && s.getRank() > total - division.getRelegationSlots());
            if (inSlot) {
                participantRepository.findById(s.getParticipantId()).ifPresent(result::add);
            }
        }
        return result;
    }

    /**
     * 指定チームが境界部ディビジョンの枠に該当することを検証し、participant を返す。該当外は 422。
     */
    private TournamentParticipantEntity requireTeamInBoundarySlot(TournamentDivisionEntity division,
                                                                  Long teamId,
                                                                  LeagueTransferDirection direction) {
        return boundarySlotTeams(division, direction).stream()
                .filter(p -> p.getTeamId().equals(teamId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        TournamentErrorCode.LEAGUE_TRANSFER_TEAM_NOT_IN_SLOT));
    }

    private Integer rankOf(Long divisionId, Long participantId) {
        return standingRepository.findByDivisionIdAndParticipantId(divisionId, participantId)
                .map(TournamentStandingEntity::getRank)
                .orElse(null);
    }

    // ========================================================================
    // 内部ヘルパー — 送り先 org 解決（§5.2 / §5.3）
    // ========================================================================

    /**
     * 昇格の送り先（上位 org）を解決する（§5.3）。
     *
     * <ul>
     *   <li>{@code explicitTargetOrgId} 指定時: それが送り出し元の祖先 org であることを検証して採用。
     *       祖先でなければ無関係 org への送り出し＝解決不能（NULL）。</li>
     *   <li>未指定時: 直近の親 org（{@code parent_organization_id}）を既定とする。親 org が無ければ NULL。</li>
     * </ul>
     *
     * @return 解決した上位 org ID（解決不能なら NULL）
     */
    private Long resolvePromotionTarget(Long fromOrgId, Long explicitTargetOrgId) {
        if (explicitTargetOrgId != null) {
            // 明示指定は送り出し元の祖先 org であること（無関係 org への送り出し防止・§7）
            return organizationHierarchyService.isAncestorOf(explicitTargetOrgId, fromOrgId)
                    ? explicitTargetOrgId
                    : null;
        }
        // 既定＝直近の親 org
        return organizationRepository.findParentOrganizationIdById(fromOrgId).orElse(null);
    }

    /**
     * 降格の送り先（出身県協会）を解決する（§5.2）。送り出し元 org の子孫 ASSOCIATION に限定。
     *
     * @return 解決した出身県協会 org ID。0 件/複数不明なら NULL（候補表示用・例外を投げない版）。
     */
    private Long resolveRelegationTargetOrNull(Long fromOrgId, Long teamId) {
        List<Long> candidates = resolveRelegationTargetCandidates(fromOrgId, teamId);
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * 降格の送り先（出身県協会）を解決する（§5.2・起票用）。
     *
     * <p>子孫 ASSOCIATION が 1 件に定まらない（0 件 or 複数）場合は症状を握りつぶさず例外化する（§5.2・根治）。</p>
     *
     * @throws BusinessException LEAGUE_TRANSFER_TARGET_NOT_RESOLVABLE
     */
    private Long resolveRelegationTargetOrThrow(Long fromOrgId, Long teamId) {
        List<Long> candidates = resolveRelegationTargetCandidates(fromOrgId, teamId);
        if (candidates.size() != 1) {
            throw new BusinessException(TournamentErrorCode.LEAGUE_TRANSFER_TARGET_NOT_RESOLVABLE);
        }
        return candidates.get(0);
    }

    /**
     * team の ACTIVE 所属組織のうち、送り出し元（上位）org の子孫であり、かつ ASSOCIATION 種別の org を列挙する。
     */
    private List<Long> resolveRelegationTargetCandidates(Long fromOrgId, Long teamId) {
        List<TeamOrgMembershipEntity> memberships = teamOrgMembershipRepository
                .findByTeamIdAndStatus(teamId, TeamOrgMembershipEntity.Status.ACTIVE);
        List<Long> result = new ArrayList<>();
        for (TeamOrgMembershipEntity m : memberships) {
            Long candidateOrgId = m.getOrganizationId();
            if (candidateOrgId == null || candidateOrgId.equals(fromOrgId)) {
                continue;
            }
            // 送り出し元（上位）org の子孫であること
            if (!organizationHierarchyService.isDescendantOf(candidateOrgId, fromOrgId)) {
                continue;
            }
            // 子孫 ASSOCIATION に限定（協会・連盟のみ送り先とする・§5.2）
            Optional<OrganizationEntity> org = organizationRepository.findById(candidateOrgId);
            if (org.isPresent() && org.get().getOrgType() == OrganizationEntity.OrgType.ASSOCIATION) {
                result.add(candidateOrgId);
            }
        }
        return result;
    }

    // ========================================================================
    // 内部ヘルパー — 認可・帰属
    // ========================================================================

    private TournamentEntity findTournamentInOrgOrThrow(Long organizationId, Long tournamentId) {
        TournamentEntity tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));
        // 他組織の大会は存在を隠して 404（IDOR 対策）
        if (!tournament.getOrganizationId().equals(organizationId)) {
            throw new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
        return tournament;
    }

    private TournamentDivisionEntity verifyDivisionBelongsToTournament(Long divisionId, Long tournamentId) {
        TournamentDivisionEntity division = divisionRepository.findById(divisionId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND));
        if (!division.getTournamentId().equals(tournamentId)) {
            throw new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND);
        }
        return division;
    }

    /** 主催（手放す/受け入れ）org の ADMIN または SYSTEM_ADMIN を要求する。違反は 403。 */
    private void requireOrganizerAdmin(Long userId, Long organizationId) {
        if (userId != null && accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (userId != null && accessControlService.isAdmin(userId, organizationId, "ORGANIZATION")) {
            return;
        }
        throw new BusinessException(TournamentErrorCode.LEAGUE_TRANSFER_DISPATCH_FORBIDDEN);
    }

    /** 受け入れ側（承認・拒否）org の ADMIN または SYSTEM_ADMIN を要求する。違反は 403。 */
    private void requireRespondAdmin(Long userId, Long organizationId) {
        if (userId != null && accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (userId != null && accessControlService.isAdmin(userId, organizationId, "ORGANIZATION")) {
            return;
        }
        throw new BusinessException(TournamentErrorCode.LEAGUE_TRANSFER_RESPOND_FORBIDDEN);
    }

    /** 当該チームの MEMBER 以上を要求する（チーム側閲覧・§7）。違反は 403。 */
    private void requireTeamMember(Long userId, Long teamId) {
        if (userId != null && accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (userId == null || !accessControlService.hasRoleOrAbove(userId, teamId, "TEAM", "MEMBER")) {
            throw new BusinessException(TournamentErrorCode.LEAGUE_TRANSFER_VIEW_FORBIDDEN);
        }
    }

    private List<Long> distinct(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().distinct().toList();
    }

    private String seasonOf(TournamentEntity tournament) {
        // season は二重起票キーの一部。未設定の大会では大会 ID を代替キーにして衝突を避ける。
        return tournament.getSeason() != null && !tournament.getSeason().isBlank()
                ? tournament.getSeason()
                : "T#" + tournament.getId();
    }
}
