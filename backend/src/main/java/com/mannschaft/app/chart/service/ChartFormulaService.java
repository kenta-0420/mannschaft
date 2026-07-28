package com.mannschaft.app.chart.service;

import com.mannschaft.app.chart.ChartErrorCode;
import com.mannschaft.app.chart.ChartMapper;
import com.mannschaft.app.chart.PatchTestResult;
import com.mannschaft.app.chart.dto.ChartFormulaResponse;
import com.mannschaft.app.chart.dto.CreateFormulaRequest;
import com.mannschaft.app.chart.dto.UpdateFormulaRequest;
import com.mannschaft.app.chart.entity.ChartFormulaEntity;
import com.mannschaft.app.chart.entity.ChartRecordEntity;
import com.mannschaft.app.chart.repository.ChartFormulaRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 薬剤レシピサービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChartFormulaService {

    private static final int MAX_FORMULAS_PER_CHART = 20;

    private final ChartFormulaRepository formulaRepository;
    private final ChartRecordRepository recordRepository;
    private final ChartMapper chartMapper;
    private final AccessControlService accessControlService;

    /** F00.5 メンバーシップ・ロール判定のスコープ種別（チーム）。 */
    private static final String SCOPE_TEAM = "TEAM";

    /**
     * 薬剤レシピ一覧を取得する。
     */
    public List<ChartFormulaResponse> listFormulas(Long teamId, Long chartId, Long actorUserId) {
        ChartRecordEntity record = recordRepository.findByIdAndTeamId(chartId, teamId)
                .orElseThrow(() -> new BusinessException(ChartErrorCode.CHART_NOT_FOUND));
        // BOLA厳禁: entity 由来 teamId で認可する。
        accessControlService.checkMembership(actorUserId, record.getTeamId(), SCOPE_TEAM);

        List<ChartFormulaEntity> formulas = formulaRepository.findByChartRecordIdOrderBySortOrder(chartId);
        return chartMapper.toFormulaResponseList(formulas);
    }

    /**
     * 薬剤レシピを追加する。
     */
    @Transactional
    public ChartFormulaResponse createFormula(Long teamId, Long chartId, Long actorUserId, CreateFormulaRequest request) {
        ChartRecordEntity record = recordRepository.findByIdAndTeamId(chartId, teamId)
                .orElseThrow(() -> new BusinessException(ChartErrorCode.CHART_NOT_FOUND));
        // BOLA厳禁: entity 由来 teamId で認可する。
        accessControlService.checkAdminOrAbove(actorUserId, record.getTeamId(), SCOPE_TEAM);

        long currentCount = formulaRepository.countByChartRecordId(chartId);
        if (currentCount >= MAX_FORMULAS_PER_CHART) {
            throw new BusinessException(ChartErrorCode.FORMULA_LIMIT_EXCEEDED);
        }

        // パッチテスト結果のバリデーション
        if (request.getPatchTestResult() != null) {
            PatchTestResult.valueOf(request.getPatchTestResult());
        }

        ChartFormulaEntity entity = ChartFormulaEntity.builder()
                .chartRecordId(chartId)
                .productName(request.getProductName())
                .ratio(request.getRatio())
                .processingTimeMinutes(request.getProcessingTimeMinutes())
                .temperature(request.getTemperature())
                .patchTestDate(request.getPatchTestDate())
                .patchTestResult(request.getPatchTestResult())
                .note(request.getNote())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .build();

        ChartFormulaEntity saved = formulaRepository.save(entity);
        log.info("薬剤レシピ追加: chartId={}, formulaId={}", chartId, saved.getId());
        return chartMapper.toFormulaResponse(saved);
    }

    /**
     * 薬剤レシピを更新する。
     */
    @Transactional
    public ChartFormulaResponse updateFormula(Long teamId, Long formulaId, Long actorUserId, UpdateFormulaRequest request) {
        ChartFormulaEntity entity = formulaRepository.findById(formulaId)
                .orElseThrow(() -> new BusinessException(ChartErrorCode.FORMULA_NOT_FOUND));

        // カルテのチーム所属確認
        ChartRecordEntity record = recordRepository.findByIdAndTeamId(entity.getChartRecordId(), teamId)
                .orElseThrow(() -> new BusinessException(ChartErrorCode.CHART_NOT_FOUND));
        // BOLA厳禁: entity 由来 teamId で認可する。
        accessControlService.checkAdminOrAbove(actorUserId, record.getTeamId(), SCOPE_TEAM);

        if (request.getPatchTestResult() != null) {
            PatchTestResult.valueOf(request.getPatchTestResult());
        }

        entity.update(
                request.getProductName(),
                request.getRatio(),
                request.getProcessingTimeMinutes(),
                request.getTemperature(),
                request.getPatchTestDate(),
                request.getPatchTestResult(),
                request.getNote(),
                request.getSortOrder() != null ? request.getSortOrder() : entity.getSortOrder()
        );

        ChartFormulaEntity saved = formulaRepository.save(entity);
        log.info("薬剤レシピ更新: formulaId={}", formulaId);
        return chartMapper.toFormulaResponse(saved);
    }

    /**
     * 薬剤レシピを削除する。
     */
    @Transactional
    public void deleteFormula(Long teamId, Long formulaId, Long actorUserId) {
        ChartFormulaEntity entity = formulaRepository.findById(formulaId)
                .orElseThrow(() -> new BusinessException(ChartErrorCode.FORMULA_NOT_FOUND));

        ChartRecordEntity record = recordRepository.findByIdAndTeamId(entity.getChartRecordId(), teamId)
                .orElseThrow(() -> new BusinessException(ChartErrorCode.CHART_NOT_FOUND));
        // BOLA厳禁: entity 由来 teamId で認可する。
        accessControlService.checkAdminOrAbove(actorUserId, record.getTeamId(), SCOPE_TEAM);

        formulaRepository.delete(entity);
        log.info("薬剤レシピ削除: formulaId={}", formulaId);
    }
}
