package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.StatAggregationType;
import com.mannschaft.app.tournament.StatDataType;
import com.mannschaft.app.tournament.TiebreakerCriteria;
import com.mannschaft.app.tournament.TiebreakerDirection;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.TournamentMapper;
import com.mannschaft.app.tournament.dto.CreatePresetRequest;
import com.mannschaft.app.tournament.dto.PresetResponse;
import com.mannschaft.app.tournament.dto.StatDefResponse;
import com.mannschaft.app.tournament.dto.TiebreakerResponse;
import com.mannschaft.app.tournament.dto.UpdatePresetRequest;
import com.mannschaft.app.tournament.entity.SystemTournamentPresetEntity;
import com.mannschaft.app.tournament.entity.SystemTournamentPresetStatDefEntity;
import com.mannschaft.app.tournament.entity.SystemTournamentPresetTiebreakerEntity;
import com.mannschaft.app.tournament.repository.SystemTournamentPresetRepository;
import com.mannschaft.app.tournament.repository.SystemTournamentPresetStatDefRepository;
import com.mannschaft.app.tournament.repository.SystemTournamentPresetTiebreakerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * SYSTEM_ADMIN向けプリセット管理サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SystemPresetService {

    private final SystemTournamentPresetRepository presetRepository;
    private final SystemTournamentPresetTiebreakerRepository tiebreakerRepository;
    private final SystemTournamentPresetStatDefRepository statDefRepository;
    private final TournamentMapper mapper;

    /**
     * プリセット一覧を取得する。
     */
    public Page<PresetResponse> listPresets(Pageable pageable) {
        return presetRepository.findAllByOrderBySortOrderAsc(pageable)
                .map(mapper::toPresetSummaryResponse);
    }

    /**
     * プリセット詳細を取得する（タイブレーク・成績項目含む）。
     */
    public PresetResponse getPreset(Long presetId) {
        SystemTournamentPresetEntity preset = findPresetOrThrow(presetId);
        List<TiebreakerResponse> tiebreakers = tiebreakerRepository.findByPresetIdOrderByPriorityAsc(presetId)
                .stream().map(mapper::toTiebreakerResponse).toList();
        List<StatDefResponse> statDefs = statDefRepository.findByPresetIdOrderBySortOrderAsc(presetId)
                .stream().map(mapper::toStatDefResponse).toList();
        return mapper.toPresetResponse(preset, tiebreakers, statDefs);
    }

    /**
     * プリセットを作成する。
     */
    @Transactional
    public PresetResponse createPreset(CreatePresetRequest request) {
        SystemTournamentPresetEntity preset = SystemTournamentPresetEntity.builder()
                .name(request.getName())
                .sportCategory(request.getSportCategory())
                .description(request.getDescription())
                .icon(request.getIcon())
                .supportedFormats(request.getSupportedFormats())
                .winPoints(request.getWinPoints() != null ? request.getWinPoints() : 3)
                .drawPoints(request.getDrawPoints() != null ? request.getDrawPoints() : 1)
                .lossPoints(request.getLossPoints() != null ? request.getLossPoints() : 0)
                .hasDraw(request.getHasDraw() != null ? request.getHasDraw() : true)
                .hasSets(request.getHasSets() != null ? request.getHasSets() : false)
                .setsToWin(request.getSetsToWin())
                .hasExtraTime(request.getHasExtraTime() != null ? request.getHasExtraTime() : false)
                .hasPenalties(request.getHasPenalties() != null ? request.getHasPenalties() : false)
                .scoreUnitLabel(request.getScoreUnitLabel() != null ? request.getScoreUnitLabel() : "点")
                .bonusPointRules(request.getBonusPointRules())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .build();
        preset = presetRepository.save(preset);

        saveTiebreakers(preset.getId(), request.getTiebreakers());
        saveStatDefs(preset.getId(), request.getStatDefs());

        return getPreset(preset.getId());
    }

    /**
     * プリセットを更新する。
     */
    @Transactional
    public PresetResponse updatePreset(Long presetId, UpdatePresetRequest request) {
        SystemTournamentPresetEntity preset = findPresetOrThrow(presetId);
        preset.update(
                request.getName() != null ? request.getName() : preset.getName(),
                request.getSportCategory() != null ? request.getSportCategory() : preset.getSportCategory(),
                request.getDescription() != null ? request.getDescription() : preset.getDescription(),
                request.getIcon() != null ? request.getIcon() : preset.getIcon(),
                request.getSupportedFormats() != null ? request.getSupportedFormats() : preset.getSupportedFormats(),
                request.getWinPoints() != null ? request.getWinPoints() : preset.getWinPoints(),
                request.getDrawPoints() != null ? request.getDrawPoints() : preset.getDrawPoints(),
                request.getLossPoints() != null ? request.getLossPoints() : preset.getLossPoints(),
                request.getHasDraw() != null ? request.getHasDraw() : preset.getHasDraw(),
                request.getHasSets() != null ? request.getHasSets() : preset.getHasSets(),
                request.getSetsToWin(),
                request.getHasExtraTime() != null ? request.getHasExtraTime() : preset.getHasExtraTime(),
                request.getHasPenalties() != null ? request.getHasPenalties() : preset.getHasPenalties(),
                request.getScoreUnitLabel() != null ? request.getScoreUnitLabel() : preset.getScoreUnitLabel(),
                request.getBonusPointRules(),
                request.getSortOrder() != null ? request.getSortOrder() : preset.getSortOrder());
        presetRepository.save(preset);

        if (request.getTiebreakers() != null) {
            tiebreakerRepository.deleteByPresetId(presetId);
            saveTiebreakers(presetId, request.getTiebreakers());
        }
        if (request.getStatDefs() != null) {
            statDefRepository.deleteByPresetId(presetId);
            saveStatDefs(presetId, request.getStatDefs());
        }

        return getPreset(presetId);
    }

    /**
     * プリセットを論理削除する。
     */
    @Transactional
    public void deletePreset(Long presetId) {
        SystemTournamentPresetEntity preset = findPresetOrThrow(presetId);
        preset.softDelete();
        presetRepository.save(preset);
    }

    SystemTournamentPresetEntity findPresetOrThrow(Long presetId) {
        return presetRepository.findById(presetId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.PRESET_NOT_FOUND));
    }

    private void saveTiebreakers(Long presetId,
                                 List<com.mannschaft.app.tournament.dto.TiebreakerRequest> requests) {
        if (requests == null) return;
        requests.forEach(req -> tiebreakerRepository.save(
                SystemTournamentPresetTiebreakerEntity.builder()
                        .presetId(presetId)
                        .priority(req.getPriority())
                        .criteria(TiebreakerCriteria.valueOf(req.getCriteria()))
                        .direction(req.getDirection() != null
                                ? TiebreakerDirection.valueOf(req.getDirection())
                                : TiebreakerDirection.DESC)
                        .build()));
    }

    private void saveStatDefs(Long presetId,
                              List<com.mannschaft.app.tournament.dto.StatDefRequest> requests) {
        if (requests == null) return;
        requests.forEach(req -> statDefRepository.save(
                SystemTournamentPresetStatDefEntity.builder()
                        .presetId(presetId)
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
