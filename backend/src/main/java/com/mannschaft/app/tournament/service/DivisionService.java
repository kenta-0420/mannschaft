package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
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
    /**
     * F08.7.1 連絡機能: ディビジョン作成時に連絡スペース（掲示板＋チャット）を自動払い出しする。
     * TODO: tournament ドメインから chat/bulletin ドメインを直接呼ぶ越境（原則5）。
     *       将来は DivisionCreatedEvent によるイベント駆動化候補。
     */
    private final TournamentContactSpaceProvisioningService contactSpaceProvisioningService;

    // ===== Division =====

    public List<DivisionResponse> listDivisions(Long tournamentId) {
        return divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(tournamentId)
                .stream().map(mapper::toDivisionResponse).toList();
    }

    @Transactional
    public DivisionResponse createDivision(Long tournamentId, CreateDivisionRequest request) {
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
        String tournamentName = tournamentRepository.findById(tournamentId)
                .map(TournamentEntity::getName)
                .orElse("大会");
        contactSpaceProvisioningService.provisionForDivision(
                saved.getId(), tournamentName + " " + saved.getName() + " 連絡");

        return mapper.toDivisionResponse(saved);
    }

    @Transactional
    public DivisionResponse updateDivision(Long tournamentId, Long divId, UpdateDivisionRequest request) {
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

    @Transactional
    public void deleteDivision(Long tournamentId, Long divId) {
        TournamentDivisionEntity division = findDivisionOrThrow(tournamentId, divId);
        // F08.7.1 §6.1: 物理削除前に連絡スペースを archive（孤児化防止・クロスドメインCASCADEなし・原則2）
        contactSpaceProvisioningService.archiveForDivision(divId);
        divisionRepository.delete(division);
    }

    // ===== Participant =====

    public List<ParticipantResponse> listParticipants(Long divisionId) {
        return participantRepository.findByDivisionIdOrderBySeedAsc(divisionId)
                .stream().map(mapper::toParticipantResponse).toList();
    }

    @Transactional
    public ParticipantResponse addParticipant(Long divisionId, CreateParticipantRequest request) {
        // 重複チェック
        participantRepository.findByDivisionIdAndTeamId(divisionId, request.getTeamId())
                .ifPresent(p -> { throw new BusinessException(TournamentErrorCode.DUPLICATE_PARTICIPANT); });

        // 最大参加チーム数チェック
        TournamentDivisionEntity division = divisionRepository.findById(divisionId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND));
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

    @Transactional
    public ParticipantResponse updateParticipant(Long pId, UpdateParticipantRequest request) {
        TournamentParticipantEntity participant = participantRepository.findById(pId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.PARTICIPANT_NOT_FOUND));

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

    @Transactional
    public void removeParticipant(Long pId) {
        TournamentParticipantEntity participant = participantRepository.findById(pId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.PARTICIPANT_NOT_FOUND));
        participantRepository.delete(participant);
    }

    TournamentDivisionEntity findDivisionOrThrow(Long tournamentId, Long divId) {
        return divisionRepository.findByIdAndTournamentId(divId, tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND));
    }
}
