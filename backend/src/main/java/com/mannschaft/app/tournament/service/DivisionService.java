package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.tournament.ParticipantStatus;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.TournamentMapper;
import com.mannschaft.app.tournament.dto.CreateDivisionRequest;
import com.mannschaft.app.tournament.dto.CreateParticipantRequest;
import com.mannschaft.app.tournament.dto.DivisionResponse;
import com.mannschaft.app.tournament.dto.ParticipantResponse;
import com.mannschaft.app.tournament.dto.UpdateDivisionRequest;
import com.mannschaft.app.tournament.dto.UpdateParticipantRequest;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ディビジョン・参加チーム管理サービス。
 *
 * <h2>認可（認可根治戦役 Wave2 トランシェ2C）</h2>
 * <ul>
 *   <li>閲覧（一覧）: 親大会（{@code tId}）の F00 可視性判定に委譲。不可視は 404（IDOR 秘匿）。</li>
 *   <li>変更（作成／更新／削除）: {@code tId} が path {@code orgId} 配下であることを検証した上で、
 *       主催組織 ADMIN/DEPUTY_ADMIN を要求する。他組織の大会 ID を自組織 URL に指定した越境
 *       （BOLA）は 404（存在秘匿）で遮断する。</li>
 *   <li>{@code divId}/{@code pId} は必ず親（{@code tId}/{@code divId}）配下であることを束縛検証する。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DivisionService {

    private final TournamentDivisionRepository divisionRepository;
    private final TournamentParticipantRepository participantRepository;
    private final TournamentRepository tournamentRepository;
    private final TournamentMapper mapper;
    private final AccessControlService accessControlService;
    private final ContentVisibilityChecker contentVisibilityChecker;
    /**
     * F08.7.1 連絡機能: ディビジョン作成時に連絡スペース（掲示板＋チャット）を自動払い出しする。
     * TODO: tournament ドメインから chat/bulletin ドメインを直接呼ぶ越境（原則5）。
     *       将来は DivisionCreatedEvent によるイベント駆動化候補。
     */
    private final TournamentContactSpaceProvisioningService contactSpaceProvisioningService;
    /**
     * F08.7.1 / 04 ファイル置き場: ディビジョン作成時にデフォルトフォルダ（「規約」）を自動付帯する。
     * TODO: tournament ドメインから filesharing ドメインを直接呼ぶ越境（原則5）。
     *       将来は DivisionCreatedEvent によるイベント駆動化候補。
     */
    private final com.mannschaft.app.filesharing.service.SharedFolderService sharedFolderService;

    /** F08.7.1 / 04: ディビジョンスコープのデフォルトフォルダ名。 */
    private static final String DEFAULT_DIVISION_FOLDER = "規約";

    // ===== Division =====

    /**
     * ディビジョン一覧を取得する（閲覧系）。
     * 親大会（tournamentId）の F00 可視性判定に委譲し、不可視は 404（IDOR 秘匿）。
     */
    public List<DivisionResponse> listDivisions(Long tournamentId, Long viewerUserId) {
        verifyTournamentVisible(tournamentId, viewerUserId);
        return divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(tournamentId)
                .stream().map(mapper::toDivisionResponse).toList();
    }

    /**
     * ディビジョンを作成する（変更系）。
     * tId が path orgId 配下であることを検証（BOLA 是正）した上で主催組織 ADMIN/DEPUTY_ADMIN を要求する。
     */
    @Transactional
    public DivisionResponse createDivision(Long orgId, Long tournamentId, Long userId, CreateDivisionRequest request) {
        TournamentEntity tournament = findTournamentInOrgOrThrow(orgId, tournamentId);
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");

        TournamentDivisionEntity division = TournamentDivisionEntity.builder()
                .tournamentId(tournamentId)
                .name(request.getName())
                .level(request.getLevel() != null ? request.getLevel() : 1)
                .promotionSlots(request.getPromotionSlots() != null ? request.getPromotionSlots() : 0)
                .relegationSlots(request.getRelegationSlots() != null ? request.getRelegationSlots() : 0)
                .playoffPromotionSlots(request.getPlayoffPromotionSlots() != null ? request.getPlayoffPromotionSlots() : 0)
                .maxParticipants(request.getMaxParticipants())
                .minEntryCount(request.getMinEntryCount())
                .maxEntryCount(request.getMaxEntryCount())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .build();
        TournamentDivisionEntity saved = divisionRepository.save(division);

        // F08.7.1: ディビジョンの連絡スペース（掲示板＋チャット）を自動付帯（要件④）
        contactSpaceProvisioningService.provisionForDivision(
                saved.getId(), tournament.getName() + " " + saved.getName() + " 連絡");

        // F08.7.1 / 04: ディビジョンのデフォルトフォルダ「規約」を自動付帯（冪等・§4）。
        // クォータ帰属は主催組織（organization_id）に集約（§6）。
        sharedFolderService.provisionDefaultFolder(
                com.mannschaft.app.filesharing.FileScopeType.TOURNAMENT_DIVISION,
                tournament.getOrganizationId(), saved.getId(),
                userId,
                DEFAULT_DIVISION_FOLDER);

        return mapper.toDivisionResponse(saved);
    }

    /**
     * ディビジョンを更新する（変更系）。tId→orgId 束縛・divId→tId 束縛の両方を検証する。
     */
    @Transactional
    public DivisionResponse updateDivision(Long orgId, Long tournamentId, Long divId, Long userId,
                                            UpdateDivisionRequest request) {
        findTournamentInOrgOrThrow(orgId, tournamentId);
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        TournamentDivisionEntity division = findDivisionOrThrow(tournamentId, divId);
        division.update(
                request.getName() != null ? request.getName() : division.getName(),
                request.getLevel() != null ? request.getLevel() : division.getLevel(),
                request.getPromotionSlots() != null ? request.getPromotionSlots() : division.getPromotionSlots(),
                request.getRelegationSlots() != null ? request.getRelegationSlots() : division.getRelegationSlots(),
                request.getPlayoffPromotionSlots() != null ? request.getPlayoffPromotionSlots() : division.getPlayoffPromotionSlots(),
                request.getMaxParticipants(),
                request.getMinEntryCount(),
                request.getMaxEntryCount(),
                request.getSortOrder() != null ? request.getSortOrder() : division.getSortOrder());
        return mapper.toDivisionResponse(divisionRepository.save(division));
    }

    /**
     * ディビジョンを削除する（変更系）。tId→orgId 束縛・divId→tId 束縛の両方を検証する。
     */
    @Transactional
    public void deleteDivision(Long orgId, Long tournamentId, Long divId, Long userId) {
        findTournamentInOrgOrThrow(orgId, tournamentId);
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        TournamentDivisionEntity division = findDivisionOrThrow(tournamentId, divId);
        // F08.7.1 §6.1: 物理削除前に連絡スペースを archive（孤児化防止・クロスドメインCASCADEなし・原則2）
        contactSpaceProvisioningService.archiveForDivision(divId);
        divisionRepository.delete(division);
    }

    // ===== Participant =====

    /**
     * 参加チーム一覧を取得する（閲覧系）。
     * 親大会（tournamentId）の可視性に加え、divId が tournamentId 配下であることを束縛検証する
     * （公開大会の tId を踏み台にした非公開大会 divId の閲覧を遮断・台帳指摘の穴）。
     */
    public List<ParticipantResponse> listParticipants(Long tournamentId, Long divisionId, Long viewerUserId) {
        verifyTournamentVisible(tournamentId, viewerUserId);
        findDivisionOrThrow(tournamentId, divisionId);
        return participantRepository.findByDivisionIdOrderBySeedAsc(divisionId)
                .stream().map(mapper::toParticipantResponse).toList();
    }

    /**
     * チームを参加登録する（変更系）。tId→orgId・divId→tId の両方を束縛検証する。
     */
    @Transactional
    public ParticipantResponse addParticipant(Long orgId, Long tournamentId, Long divisionId, Long userId,
                                               CreateParticipantRequest request) {
        findTournamentInOrgOrThrow(orgId, tournamentId);
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        TournamentDivisionEntity division = findDivisionOrThrow(tournamentId, divisionId);

        // 重複チェック
        participantRepository.findByDivisionIdAndTeamId(divisionId, request.getTeamId())
                .ifPresent(p -> { throw new BusinessException(TournamentErrorCode.DUPLICATE_PARTICIPANT); });

        // 最大参加チーム数チェック
        if (division.getMaxParticipants() != null) {
            long count = participantRepository.countByDivisionId(divisionId);
            if (count >= division.getMaxParticipants()) {
                throw new BusinessException(TournamentErrorCode.MAX_PARTICIPANTS_EXCEEDED);
            }
        }

        TournamentParticipantEntity participant = TournamentParticipantEntity.builder()
                .divisionId(divisionId)
                .teamId(request.getTeamId())
                .seed(request.getSeed())
                .displayName(request.getDisplayName())
                .build();
        return mapper.toParticipantResponse(participantRepository.save(participant));
    }

    /**
     * 参加情報を更新する（変更系）。tId→orgId・divId→tId・pId→divId を全て束縛検証する。
     */
    @Transactional
    public ParticipantResponse updateParticipant(Long orgId, Long tournamentId, Long divisionId, Long pId,
                                                  Long userId, UpdateParticipantRequest request) {
        findTournamentInOrgOrThrow(orgId, tournamentId);
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        findDivisionOrThrow(tournamentId, divisionId);
        TournamentParticipantEntity participant = findParticipantOrThrow(divisionId, pId);

        if (request.getSeed() != null || request.getDisplayName() != null) {
            participant.update(
                    request.getSeed() != null ? request.getSeed() : participant.getSeed(),
                    request.getDisplayName() != null ? request.getDisplayName() : participant.getDisplayName());
        }
        if (request.getStatus() != null) {
            participant.changeStatus(ParticipantStatus.valueOf(request.getStatus()));
        }
        return mapper.toParticipantResponse(participantRepository.save(participant));
    }

    /**
     * チームを参加除外する（変更系）。tId→orgId・divId→tId・pId→divId を全て束縛検証する。
     */
    @Transactional
    public void removeParticipant(Long orgId, Long tournamentId, Long divisionId, Long pId, Long userId) {
        findTournamentInOrgOrThrow(orgId, tournamentId);
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        findDivisionOrThrow(tournamentId, divisionId);
        TournamentParticipantEntity participant = findParticipantOrThrow(divisionId, pId);
        participantRepository.delete(participant);
    }

    // ===== 内部ヘルパー =====

    /**
     * 大会が path orgId 配下であることを検証する（BOLA 対策・IDOR 対策で 404 に統一）。
     */
    private TournamentEntity findTournamentInOrgOrThrow(Long orgId, Long tournamentId) {
        TournamentEntity tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));
        if (!tournament.getOrganizationId().equals(orgId)) {
            throw new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
        return tournament;
    }

    /**
     * 大会 visibility ガード（閲覧系）。認証ユーザー（未認証なら null）が当該 tournament を
     * 閲覧できるか F00 共通可視性 Resolver で判定し、不可視なら 404 を投げる。
     */
    private void verifyTournamentVisible(Long tournamentId, Long viewerUserId) {
        if (!contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, tournamentId, viewerUserId)) {
            throw new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
    }

    TournamentDivisionEntity findDivisionOrThrow(Long tournamentId, Long divId) {
        return divisionRepository.findByIdAndTournamentId(divId, tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND));
    }

    private TournamentParticipantEntity findParticipantOrThrow(Long divisionId, Long pId) {
        return participantRepository.findByIdAndDivisionId(pId, divisionId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.PARTICIPANT_NOT_FOUND));
    }
}
