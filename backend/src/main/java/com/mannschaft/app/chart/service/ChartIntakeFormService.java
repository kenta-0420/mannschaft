package com.mannschaft.app.chart.service;

import com.mannschaft.app.chart.ChartErrorCode;
import com.mannschaft.app.chart.ChartMapper;
import com.mannschaft.app.chart.dto.IntakeFormResponse;
import com.mannschaft.app.chart.dto.UpdateIntakeFormRequest;
import com.mannschaft.app.chart.entity.ChartIntakeFormEntity;
import com.mannschaft.app.chart.entity.ChartRecordEntity;
import com.mannschaft.app.chart.repository.ChartIntakeFormRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 問診票サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChartIntakeFormService {

    private final ChartIntakeFormRepository intakeFormRepository;
    private final ChartRecordRepository recordRepository;
    private final ChartMapper chartMapper;
    private final AccessControlService accessControlService;

    /** F00.5 メンバーシップ・ロール判定のスコープ種別（チーム）。 */
    private static final String SCOPE_TEAM = "TEAM";

    /**
     * カルテの問診票一覧を取得する。
     */
    public List<IntakeFormResponse> getIntakeForms(Long teamId, Long chartId, Long actorUserId) {
        ChartRecordEntity record = recordRepository.findByIdAndTeamId(chartId, teamId)
                .orElseThrow(() -> new BusinessException(ChartErrorCode.CHART_NOT_FOUND));
        // BOLA厳禁: entity 由来 teamId で認可する。
        accessControlService.checkMembership(actorUserId, record.getTeamId(), SCOPE_TEAM);

        List<ChartIntakeFormEntity> forms = intakeFormRepository.findByChartRecordId(chartId);
        return chartMapper.toIntakeFormResponseList(forms);
    }

    /**
     * 問診票を更新（upsert）する。
     */
    @Transactional
    public IntakeFormResponse updateIntakeForm(Long teamId, Long chartId, Long actorUserId,
                                                UpdateIntakeFormRequest request) {
        ChartRecordEntity record = recordRepository.findByIdAndTeamId(chartId, teamId)
                .orElseThrow(() -> new BusinessException(ChartErrorCode.CHART_NOT_FOUND));
        // BOLA厳禁: entity 由来 teamId で認可する。
        accessControlService.checkAdminOrAbove(actorUserId, record.getTeamId(), SCOPE_TEAM);

        Optional<ChartIntakeFormEntity> existing = intakeFormRepository.findByChartRecordIdAndFormType(
                chartId, request.getFormType());

        ChartIntakeFormEntity entity;
        if (existing.isPresent()) {
            entity = existing.get();
            entity.updateContent(request.getContent());
            if (request.getElectronicSealId() != null) {
                entity.sign(request.getElectronicSealId());
            }
        } else {
            entity = ChartIntakeFormEntity.builder()
                    .chartRecordId(chartId)
                    .formType(request.getFormType())
                    .content(request.getContent())
                    .electronicSealId(request.getElectronicSealId())
                    .isInitial(request.getIsInitial() != null ? request.getIsInitial() : true)
                    .build();
            if (request.getElectronicSealId() != null) {
                entity.sign(request.getElectronicSealId());
            }
        }

        ChartIntakeFormEntity saved = intakeFormRepository.save(entity);
        log.info("問診票更新: chartId={}, formType={}", chartId, request.getFormType());
        return chartMapper.toIntakeFormResponse(saved);
    }
}
