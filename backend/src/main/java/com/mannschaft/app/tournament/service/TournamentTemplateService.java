package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.StatAggregationType;
import com.mannschaft.app.tournament.StatDataType;
import com.mannschaft.app.tournament.TiebreakerCriteria;
import com.mannschaft.app.tournament.TiebreakerDirection;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.TournamentMapper;
import com.mannschaft.app.tournament.dto.CreateTemplateRequest;
import com.mannschaft.app.tournament.dto.StatDefResponse;
import com.mannschaft.app.tournament.dto.TemplateResponse;
import com.mannschaft.app.tournament.dto.TiebreakerResponse;
import com.mannschaft.app.tournament.dto.UpdateTemplateRequest;
import com.mannschaft.app.tournament.entity.SystemTournamentPresetEntity;
import com.mannschaft.app.tournament.entity.SystemTournamentPresetStatDefEntity;
import com.mannschaft.app.tournament.entity.SystemTournamentPresetTiebreakerEntity;
import com.mannschaft.app.tournament.entity.TournamentTemplateEntity;
import com.mannschaft.app.tournament.entity.TournamentTemplateStatDefEntity;
import com.mannschaft.app.tournament.entity.TournamentTemplateTiebreakerEntity;
import com.mannschaft.app.tournament.repository.SystemTournamentPresetRepository;
import com.mannschaft.app.tournament.repository.SystemTournamentPresetStatDefRepository;
import com.mannschaft.app.tournament.repository.SystemTournamentPresetTiebreakerRepository;
import com.mannschaft.app.tournament.repository.TournamentTemplateRepository;
import com.mannschaft.app.tournament.repository.TournamentTemplateStatDefRepository;
import com.mannschaft.app.tournament.repository.TournamentTemplateTiebreakerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * テンプレート管理サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TournamentTemplateService {

    private final TournamentTemplateRepository templateRepository;
    private final TournamentTemplateTiebreakerRepository tiebreakerRepository;
    private final TournamentTemplateStatDefRepository statDefRepository;
    private final SystemTournamentPresetRepository presetRepository;
    private final SystemTournamentPresetTiebreakerRepository presetTiebreakerRepository;
    private final SystemTournamentPresetStatDefRepository presetStatDefRepository;
    private final TournamentMapper mapper;

    /**
     * テンプレート一覧を取得する。
     */
    public Page<TemplateResponse> listTemplates(Long orgId, Pageable pageable) {
        return templateRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId, pageable)
                .map(entity -> mapper.toTemplateResponse(entity,
                        List.of(), List.of()));
    }

    /**
     * テンプレート詳細を取得する。
     */
    public TemplateResponse getTemplate(Long orgId, Long templateId) {
        TournamentTemplateEntity template = findTemplateOrThrow(templateId);
        List<TiebreakerResponse> tiebreakers = tiebreakerRepository
                .findByTemplateIdOrderByPriorityAsc(templateId)
                .stream().map(mapper::toTiebreakerResponse).toList();
        List<StatDefResponse> statDefs = statDefRepository
                .findByTemplateIdOrderBySortOrderAsc(templateId)
                .stream().map(mapper::toStatDefResponse).toList();
        return mapper.toTemplateResponse(template, tiebreakers, statDefs);
    }

    /**
     * テンプレートを作成する。
     */
    @Transactional
    public TemplateResponse createTemplate(Long orgId, Long userId, CreateTemplateRequest request) {
        TournamentTemplateEntity template = TournamentTemplateEntity.builder()
                .organizationId(orgId)
                .name(request.getName())
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
                .createdBy(userId)
                .build();
        template = templateRepository.save(template);

        saveTemplateTiebreakers(template.getId(), request.getTiebreakers());
        saveTemplateStatDefs(template.getId(), request.getStatDefs());

        return getTemplate(orgId, template.getId());
    }

    /**
     * プリセットから複製してテンプレートを作成する。
     */
    @Transactional
    public TemplateResponse cloneFromPreset(Long orgId, Long userId, Long presetId) {
        SystemTournamentPresetEntity preset = presetRepository.findById(presetId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.PRESET_NOT_FOUND));

        TournamentTemplateEntity template = TournamentTemplateEntity.builder()
                .organizationId(orgId)
                .sourcePresetId(presetId)
                .name(preset.getName())
                .description(preset.getDescription())
                .icon(preset.getIcon())
                .supportedFormats(preset.getSupportedFormats())
                .winPoints(preset.getWinPoints())
                .drawPoints(preset.getDrawPoints())
                .lossPoints(preset.getLossPoints())
                .hasDraw(preset.getHasDraw())
                .hasSets(preset.getHasSets())
                .setsToWin(preset.getSetsToWin())
                .hasExtraTime(preset.getHasExtraTime())
                .hasPenalties(preset.getHasPenalties())
                .scoreUnitLabel(preset.getScoreUnitLabel())
                .bonusPointRules(preset.getBonusPointRules())
                .createdBy(userId)
                .build();
        template = templateRepository.save(template);

        // タイブレークをコピー
        Long templateId = template.getId();
        List<SystemTournamentPresetTiebreakerEntity> presetTiebreakers =
                presetTiebreakerRepository.findByPresetIdOrderByPriorityAsc(presetId);
        presetTiebreakers.forEach(ptb -> tiebreakerRepository.save(
                TournamentTemplateTiebreakerEntity.builder()
                        .templateId(templateId)
                        .priority(ptb.getPriority())
                        .criteria(ptb.getCriteria())
                        .direction(ptb.getDirection())
                        .build()));

        // 成績項目をコピー
        List<SystemTournamentPresetStatDefEntity> presetStatDefs =
                presetStatDefRepository.findByPresetIdOrderBySortOrderAsc(presetId);
        presetStatDefs.forEach(psd -> statDefRepository.save(
                TournamentTemplateStatDefEntity.builder()
                        .templateId(templateId)
                        .name(psd.getName())
                        .statKey(psd.getStatKey())
                        .unit(psd.getUnit())
                        .dataType(psd.getDataType())
                        .aggregationType(psd.getAggregationType())
                        .isRankingTarget(psd.getIsRankingTarget())
                        .rankingLabel(psd.getRankingLabel())
                        .sortOrder(psd.getSortOrder())
                        .build()));

        return getTemplate(orgId, templateId);
    }

    /**
     * テンプレートを更新する。
     */
    @Transactional
    public TemplateResponse updateTemplate(Long orgId, Long templateId, UpdateTemplateRequest request) {
        TournamentTemplateEntity template = findTemplateOrThrow(templateId);
        template.update(
                request.getName() != null ? request.getName() : template.getName(),
                request.getDescription() != null ? request.getDescription() : template.getDescription(),
                request.getIcon() != null ? request.getIcon() : template.getIcon(),
                request.getSupportedFormats() != null ? request.getSupportedFormats() : template.getSupportedFormats(),
                request.getWinPoints() != null ? request.getWinPoints() : template.getWinPoints(),
                request.getDrawPoints() != null ? request.getDrawPoints() : template.getDrawPoints(),
                request.getLossPoints() != null ? request.getLossPoints() : template.getLossPoints(),
                request.getHasDraw() != null ? request.getHasDraw() : template.getHasDraw(),
                request.getHasSets() != null ? request.getHasSets() : template.getHasSets(),
                request.getSetsToWin(),
                request.getHasExtraTime() != null ? request.getHasExtraTime() : template.getHasExtraTime(),
                request.getHasPenalties() != null ? request.getHasPenalties() : template.getHasPenalties(),
                request.getScoreUnitLabel() != null ? request.getScoreUnitLabel() : template.getScoreUnitLabel(),
                request.getBonusPointRules());
        templateRepository.save(template);

        if (request.getTiebreakers() != null) {
            tiebreakerRepository.deleteByTemplateId(templateId);
            saveTemplateTiebreakers(templateId, request.getTiebreakers());
        }
        if (request.getStatDefs() != null) {
            statDefRepository.deleteByTemplateId(templateId);
            saveTemplateStatDefs(templateId, request.getStatDefs());
        }

        return getTemplate(orgId, templateId);
    }

    /**
     * テンプレートを論理削除する。
     */
    @Transactional
    public void deleteTemplate(Long templateId) {
        TournamentTemplateEntity template = findTemplateOrThrow(templateId);
        template.softDelete();
        templateRepository.save(template);
    }

    TournamentTemplateEntity findTemplateOrThrow(Long templateId) {
        return templateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TEMPLATE_NOT_FOUND));
    }

    private void saveTemplateTiebreakers(Long templateId,
                                         List<com.mannschaft.app.tournament.dto.TiebreakerRequest> requests) {
        if (requests == null) return;
        requests.forEach(req -> tiebreakerRepository.save(
                TournamentTemplateTiebreakerEntity.builder()
                        .templateId(templateId)
                        .priority(req.getPriority())
                        .criteria(TiebreakerCriteria.valueOf(req.getCriteria()))
                        .direction(req.getDirection() != null
                                ? TiebreakerDirection.valueOf(req.getDirection())
                                : TiebreakerDirection.DESC)
                        .build()));
    }

    private void saveTemplateStatDefs(Long templateId,
                                      List<com.mannschaft.app.tournament.dto.StatDefRequest> requests) {
        if (requests == null) return;
        requests.forEach(req -> statDefRepository.save(
                TournamentTemplateStatDefEntity.builder()
                        .templateId(templateId)
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
