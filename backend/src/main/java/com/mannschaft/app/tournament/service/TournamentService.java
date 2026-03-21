package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.LeagueRoundType;
import com.mannschaft.app.tournament.ParticipantStatus;
import com.mannschaft.app.tournament.StatAggregationType;
import com.mannschaft.app.tournament.StatDataType;
import com.mannschaft.app.tournament.TiebreakerCriteria;
import com.mannschaft.app.tournament.TiebreakerDirection;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.TournamentFormat;
import com.mannschaft.app.tournament.TournamentMapper;
import com.mannschaft.app.tournament.TournamentStatus;
import com.mannschaft.app.tournament.TournamentVisibility;
import com.mannschaft.app.tournament.dto.CreateTournamentRequest;
import com.mannschaft.app.tournament.dto.StatDefResponse;
import com.mannschaft.app.tournament.dto.TiebreakerResponse;
import com.mannschaft.app.tournament.dto.TournamentResponse;
import com.mannschaft.app.tournament.dto.UpdateTournamentRequest;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entity.TournamentStatDefEntity;
import com.mannschaft.app.tournament.entity.TournamentTemplateEntity;
import com.mannschaft.app.tournament.entity.TournamentTemplateStatDefEntity;
import com.mannschaft.app.tournament.entity.TournamentTemplateTiebreakerEntity;
import com.mannschaft.app.tournament.entity.TournamentTiebreakerEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStatDefRepository;
import com.mannschaft.app.tournament.repository.TournamentTemplateRepository;
import com.mannschaft.app.tournament.repository.TournamentTemplateStatDefRepository;
import com.mannschaft.app.tournament.repository.TournamentTemplateTiebreakerRepository;
import com.mannschaft.app.tournament.repository.TournamentTiebreakerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 大会・リーグ管理サービス。CRUD・ステータス管理・シーズン継続を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TournamentService {

    private final TournamentRepository tournamentRepository;
    private final TournamentTiebreakerRepository tiebreakerRepository;
    private final TournamentStatDefRepository statDefRepository;
    private final TournamentTemplateRepository templateRepository;
    private final TournamentTemplateTiebreakerRepository templateTiebreakerRepository;
    private final TournamentTemplateStatDefRepository templateStatDefRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final TournamentParticipantRepository participantRepository;
    private final TournamentMapper mapper;

    /**
     * 大会一覧を取得する。
     */
    public Page<TournamentResponse> listTournaments(Long orgId, String status, Pageable pageable) {
        if (status != null) {
            return tournamentRepository.findByOrganizationIdAndStatusOrderByCreatedAtDesc(
                    orgId, TournamentStatus.valueOf(status), pageable)
                    .map(mapper::toTournamentSummaryResponse);
        }
        return tournamentRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId, pageable)
                .map(mapper::toTournamentSummaryResponse);
    }

    /**
     * 公開大会一覧を取得する。
     */
    public Page<TournamentResponse> listPublicTournaments(Long orgId, Pageable pageable) {
        return tournamentRepository.findByOrganizationIdAndVisibilityAndStatusNotOrderByCreatedAtDesc(
                orgId, TournamentVisibility.PUBLIC, TournamentStatus.DRAFT, pageable)
                .map(mapper::toTournamentSummaryResponse);
    }

    /**
     * 大会詳細を取得する。
     */
    public TournamentResponse getTournament(Long tournamentId) {
        TournamentEntity tournament = findTournamentOrThrow(tournamentId);
        List<TiebreakerResponse> tiebreakers = tiebreakerRepository
                .findByTournamentIdOrderByPriorityAsc(tournamentId)
                .stream().map(mapper::toTiebreakerResponse).toList();
        List<StatDefResponse> statDefs = statDefRepository
                .findByTournamentIdOrderBySortOrderAsc(tournamentId)
                .stream().map(mapper::toStatDefResponse).toList();
        return mapper.toTournamentResponse(tournament, tiebreakers, statDefs);
    }

    /**
     * 公開大会詳細を取得する。visibility = PUBLIC のみ返却。
     */
    public TournamentResponse getPublicTournament(Long orgId, Long tournamentId) {
        TournamentEntity tournament = findTournamentOrThrow(tournamentId);
        if (!tournament.getOrganizationId().equals(orgId)
                || tournament.getVisibility() != TournamentVisibility.PUBLIC) {
            throw new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
        List<TiebreakerResponse> tiebreakers = tiebreakerRepository
                .findByTournamentIdOrderByPriorityAsc(tournamentId)
                .stream().map(mapper::toTiebreakerResponse).toList();
        List<StatDefResponse> statDefs = statDefRepository
                .findByTournamentIdOrderBySortOrderAsc(tournamentId)
                .stream().map(mapper::toStatDefResponse).toList();
        return mapper.toTournamentResponse(tournament, tiebreakers, statDefs);
    }

    /**
     * 大会を作成する。
     */
    @Transactional
    public TournamentResponse createTournament(Long orgId, Long userId, CreateTournamentRequest request) {
        TournamentFormat format = TournamentFormat.valueOf(request.getFormat());

        TournamentEntity.TournamentEntityBuilder builder = TournamentEntity.builder()
                .organizationId(orgId)
                .templateId(request.getTemplateId())
                .name(request.getName())
                .description(request.getDescription())
                .format(format)
                .season(request.getSeason())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .createdBy(userId);

        // テンプレートがある場合は初期値をコピー
        if (request.getTemplateId() != null) {
            TournamentTemplateEntity template = templateRepository.findById(request.getTemplateId())
                    .orElseThrow(() -> new BusinessException(TournamentErrorCode.TEMPLATE_NOT_FOUND));
            builder.winPoints(template.getWinPoints())
                    .drawPoints(template.getDrawPoints())
                    .lossPoints(template.getLossPoints())
                    .hasDraw(template.getHasDraw())
                    .hasSets(template.getHasSets())
                    .setsToWin(template.getSetsToWin())
                    .hasExtraTime(template.getHasExtraTime())
                    .hasPenalties(template.getHasPenalties())
                    .scoreUnitLabel(template.getScoreUnitLabel())
                    .bonusPointRules(template.getBonusPointRules());
        }

        // リクエストの値で上書き
        if (request.getWinPoints() != null) builder.winPoints(request.getWinPoints());
        if (request.getDrawPoints() != null) builder.drawPoints(request.getDrawPoints());
        if (request.getLossPoints() != null) builder.lossPoints(request.getLossPoints());
        if (request.getHasDraw() != null) builder.hasDraw(request.getHasDraw());
        if (request.getHasSets() != null) builder.hasSets(request.getHasSets());
        if (request.getSetsToWin() != null) builder.setsToWin(request.getSetsToWin());
        if (request.getHasExtraTime() != null) builder.hasExtraTime(request.getHasExtraTime());
        if (request.getHasPenalties() != null) builder.hasPenalties(request.getHasPenalties());
        if (request.getScoreUnitLabel() != null) builder.scoreUnitLabel(request.getScoreUnitLabel());
        if (request.getBonusPointRules() != null) builder.bonusPointRules(request.getBonusPointRules());
        if (request.getLeagueRoundType() != null)
            builder.leagueRoundType(LeagueRoundType.valueOf(request.getLeagueRoundType()));
        if (request.getKnockoutLegs() != null) builder.knockoutLegs(request.getKnockoutLegs());
        if (request.getVisibility() != null)
            builder.visibility(TournamentVisibility.valueOf(request.getVisibility()));

        TournamentEntity tournament = tournamentRepository.save(builder.build());
        Long tournamentId = tournament.getId();

        // テンプレートからタイブレーク・成績項目をディープコピー
        if (request.getTemplateId() != null) {
            copyTiebreakersFromTemplate(tournamentId, request.getTemplateId());
            copyStatDefsFromTemplate(tournamentId, request.getTemplateId());
        }

        // リクエストに明示的にタイブレーク・成績項目が含まれていれば上書き
        if (request.getTiebreakers() != null && !request.getTiebreakers().isEmpty()) {
            tiebreakerRepository.deleteByTournamentId(tournamentId);
            saveTournamentTiebreakers(tournamentId, request.getTiebreakers());
        }
        if (request.getStatDefs() != null && !request.getStatDefs().isEmpty()) {
            statDefRepository.deleteByTournamentId(tournamentId);
            saveTournamentStatDefs(tournamentId, request.getStatDefs());
        }

        return getTournament(tournamentId);
    }

    /**
     * 大会を更新する。
     */
    @Transactional
    public TournamentResponse updateTournament(Long tournamentId, UpdateTournamentRequest request) {
        TournamentEntity tournament = findTournamentOrThrow(tournamentId);
        tournament.update(
                request.getName() != null ? request.getName() : tournament.getName(),
                request.getDescription() != null ? request.getDescription() : tournament.getDescription(),
                request.getFormat() != null ? TournamentFormat.valueOf(request.getFormat()) : tournament.getFormat(),
                request.getSeason() != null ? request.getSeason() : tournament.getSeason(),
                request.getStartDate() != null ? request.getStartDate() : tournament.getStartDate(),
                request.getEndDate() != null ? request.getEndDate() : tournament.getEndDate(),
                request.getWinPoints() != null ? request.getWinPoints() : tournament.getWinPoints(),
                request.getDrawPoints() != null ? request.getDrawPoints() : tournament.getDrawPoints(),
                request.getLossPoints() != null ? request.getLossPoints() : tournament.getLossPoints(),
                request.getHasDraw() != null ? request.getHasDraw() : tournament.getHasDraw(),
                request.getHasSets() != null ? request.getHasSets() : tournament.getHasSets(),
                request.getSetsToWin(),
                request.getHasExtraTime() != null ? request.getHasExtraTime() : tournament.getHasExtraTime(),
                request.getHasPenalties() != null ? request.getHasPenalties() : tournament.getHasPenalties(),
                request.getScoreUnitLabel() != null ? request.getScoreUnitLabel() : tournament.getScoreUnitLabel(),
                request.getBonusPointRules(),
                request.getLeagueRoundType() != null ? LeagueRoundType.valueOf(request.getLeagueRoundType()) : tournament.getLeagueRoundType(),
                request.getKnockoutLegs() != null ? request.getKnockoutLegs() : tournament.getKnockoutLegs(),
                request.getVisibility() != null ? TournamentVisibility.valueOf(request.getVisibility()) : tournament.getVisibility());
        tournamentRepository.save(tournament);

        if (request.getTiebreakers() != null) {
            tiebreakerRepository.deleteByTournamentId(tournamentId);
            saveTournamentTiebreakers(tournamentId, request.getTiebreakers());
        }
        if (request.getStatDefs() != null) {
            statDefRepository.deleteByTournamentId(tournamentId);
            saveTournamentStatDefs(tournamentId, request.getStatDefs());
        }

        return getTournament(tournamentId);
    }

    /**
     * 大会を論理削除する。
     */
    @Transactional
    public void deleteTournament(Long tournamentId) {
        TournamentEntity tournament = findTournamentOrThrow(tournamentId);
        tournament.softDelete();
        tournamentRepository.save(tournament);
    }

    /**
     * 大会ステータスを変更する。
     */
    @Transactional
    public TournamentResponse changeStatus(Long tournamentId, TournamentStatus newStatus) {
        TournamentEntity tournament = findTournamentOrThrow(tournamentId);
        // OPEN → IN_PROGRESS の場合、全参加チームを ACTIVE に変更
        if (tournament.getStatus() == TournamentStatus.OPEN && newStatus == TournamentStatus.IN_PROGRESS) {
            List<TournamentDivisionEntity> divisions =
                    divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(tournamentId);
            for (TournamentDivisionEntity div : divisions) {
                List<TournamentParticipantEntity> participants =
                        participantRepository.findByDivisionIdAndStatus(div.getId(), ParticipantStatus.REGISTERED);
                participants.forEach(p -> p.changeStatus(ParticipantStatus.ACTIVE));
                participantRepository.saveAll(participants);
            }
        }
        tournament.changeStatus(newStatus);
        tournamentRepository.save(tournament);
        return getTournament(tournamentId);
    }

    /**
     * 前シーズンから継続して大会を作成する。
     */
    @Transactional
    public TournamentResponse continueTournament(Long orgId, Long userId, Long previousTournamentId) {
        TournamentEntity previous = findTournamentOrThrow(previousTournamentId);
        if (previous.getStatus() != TournamentStatus.COMPLETED &&
            previous.getStatus() != TournamentStatus.ARCHIVED) {
            throw new BusinessException(TournamentErrorCode.INVALID_TOURNAMENT_STATUS);
        }

        // 大会のルール値をコピー
        TournamentEntity newTournament = TournamentEntity.builder()
                .organizationId(orgId)
                .templateId(previous.getTemplateId())
                .previousTournamentId(previousTournamentId)
                .name(previous.getName())
                .description(previous.getDescription())
                .format(previous.getFormat())
                .winPoints(previous.getWinPoints())
                .drawPoints(previous.getDrawPoints())
                .lossPoints(previous.getLossPoints())
                .hasDraw(previous.getHasDraw())
                .hasSets(previous.getHasSets())
                .setsToWin(previous.getSetsToWin())
                .hasExtraTime(previous.getHasExtraTime())
                .hasPenalties(previous.getHasPenalties())
                .scoreUnitLabel(previous.getScoreUnitLabel())
                .bonusPointRules(previous.getBonusPointRules())
                .leagueRoundType(previous.getLeagueRoundType())
                .knockoutLegs(previous.getKnockoutLegs())
                .visibility(previous.getVisibility())
                .createdBy(userId)
                .build();
        newTournament = tournamentRepository.save(newTournament);
        Long newTournamentId = newTournament.getId();

        // タイブレーク・成績項目をコピー
        tiebreakerRepository.findByTournamentIdOrderByPriorityAsc(previousTournamentId)
                .forEach(tb -> tiebreakerRepository.save(TournamentTiebreakerEntity.builder()
                        .tournamentId(newTournamentId)
                        .priority(tb.getPriority())
                        .criteria(tb.getCriteria())
                        .direction(tb.getDirection())
                        .build()));
        statDefRepository.findByTournamentIdOrderBySortOrderAsc(previousTournamentId)
                .forEach(sd -> statDefRepository.save(TournamentStatDefEntity.builder()
                        .tournamentId(newTournamentId)
                        .name(sd.getName())
                        .statKey(sd.getStatKey())
                        .unit(sd.getUnit())
                        .dataType(sd.getDataType())
                        .aggregationType(sd.getAggregationType())
                        .isRankingTarget(sd.getIsRankingTarget())
                        .rankingLabel(sd.getRankingLabel())
                        .sortOrder(sd.getSortOrder())
                        .build()));

        // ディビジョン構成をコピー
        List<TournamentDivisionEntity> prevDivisions =
                divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(previousTournamentId);
        for (TournamentDivisionEntity prevDiv : prevDivisions) {
            divisionRepository.save(TournamentDivisionEntity.builder()
                    .tournamentId(newTournamentId)
                    .name(prevDiv.getName())
                    .level(prevDiv.getLevel())
                    .promotionSlots(prevDiv.getPromotionSlots())
                    .relegationSlots(prevDiv.getRelegationSlots())
                    .playoffPromotionSlots(prevDiv.getPlayoffPromotionSlots())
                    .maxParticipants(prevDiv.getMaxParticipants())
                    .sortOrder(prevDiv.getSortOrder())
                    .build());
        }

        return getTournament(newTournamentId);
    }

    TournamentEntity findTournamentOrThrow(Long tournamentId) {
        return tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));
    }

    private void copyTiebreakersFromTemplate(Long tournamentId, Long templateId) {
        templateTiebreakerRepository.findByTemplateIdOrderByPriorityAsc(templateId)
                .forEach(ttb -> tiebreakerRepository.save(
                        TournamentTiebreakerEntity.builder()
                                .tournamentId(tournamentId)
                                .priority(ttb.getPriority())
                                .criteria(ttb.getCriteria())
                                .direction(ttb.getDirection())
                                .build()));
    }

    private void copyStatDefsFromTemplate(Long tournamentId, Long templateId) {
        templateStatDefRepository.findByTemplateIdOrderBySortOrderAsc(templateId)
                .forEach(tsd -> statDefRepository.save(
                        TournamentStatDefEntity.builder()
                                .tournamentId(tournamentId)
                                .name(tsd.getName())
                                .statKey(tsd.getStatKey())
                                .unit(tsd.getUnit())
                                .dataType(tsd.getDataType())
                                .aggregationType(tsd.getAggregationType())
                                .isRankingTarget(tsd.getIsRankingTarget())
                                .rankingLabel(tsd.getRankingLabel())
                                .sortOrder(tsd.getSortOrder())
                                .build()));
    }

    private void saveTournamentTiebreakers(Long tournamentId,
                                           List<com.mannschaft.app.tournament.dto.TiebreakerRequest> requests) {
        requests.forEach(req -> tiebreakerRepository.save(
                TournamentTiebreakerEntity.builder()
                        .tournamentId(tournamentId)
                        .priority(req.getPriority())
                        .criteria(TiebreakerCriteria.valueOf(req.getCriteria()))
                        .direction(req.getDirection() != null
                                ? TiebreakerDirection.valueOf(req.getDirection())
                                : TiebreakerDirection.DESC)
                        .build()));
    }

    private void saveTournamentStatDefs(Long tournamentId,
                                        List<com.mannschaft.app.tournament.dto.StatDefRequest> requests) {
        requests.forEach(req -> statDefRepository.save(
                TournamentStatDefEntity.builder()
                        .tournamentId(tournamentId)
                        .name(req.getName())
                        .statKey(req.getStatKey())
                        .unit(req.getUnit())
                        .dataType(req.getDataType() != null
                                ? StatDataType.valueOf(req.getDataType())
                                : StatDataType.INTEGER)
                        .aggregationType(req.getAggregationType() != null
                                ? StatAggregationType.valueOf(req.getAggregationType())
                                : StatAggregationType.SUM)
                        .isRankingTarget(req.getIsRankingTarget() != null ? req.getIsRankingTarget() : true)
                        .rankingLabel(req.getRankingLabel())
                        .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                        .build()));
    }
}
