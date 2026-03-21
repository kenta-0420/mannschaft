package com.mannschaft.app.chart.service;

import com.mannschaft.app.chart.ChartErrorCode;
import com.mannschaft.app.chart.ChartMapper;
import com.mannschaft.app.chart.CustomFieldType;
import com.mannschaft.app.chart.SectionType;
import com.mannschaft.app.chart.dto.CreateCustomFieldRequest;
import com.mannschaft.app.chart.dto.CreateRecordTemplateRequest;
import com.mannschaft.app.chart.dto.CustomFieldResponse;
import com.mannschaft.app.chart.dto.ProgressResponse;
import com.mannschaft.app.chart.dto.RecordTemplateResponse;
import com.mannschaft.app.chart.dto.SectionSettingRequest;
import com.mannschaft.app.chart.dto.SectionSettingResponse;
import com.mannschaft.app.chart.dto.UpdateCustomFieldRequest;
import com.mannschaft.app.chart.dto.UpdateRecordTemplateRequest;
import com.mannschaft.app.chart.dto.UpdateSectionSettingsRequest;
import com.mannschaft.app.chart.entity.ChartCustomFieldEntity;
import com.mannschaft.app.chart.entity.ChartCustomValueEntity;
import com.mannschaft.app.chart.entity.ChartRecordEntity;
import com.mannschaft.app.chart.entity.ChartRecordTemplateEntity;
import com.mannschaft.app.chart.entity.ChartSectionSettingEntity;
import com.mannschaft.app.chart.repository.ChartCustomFieldRepository;
import com.mannschaft.app.chart.repository.ChartCustomValueRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.chart.repository.ChartRecordTemplateRepository;
import com.mannschaft.app.chart.repository.ChartSectionSettingRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * カルテ設定サービス。セクション設定・カスタムフィールド・カルテテンプレート・経過グラフを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChartSettingsService {

    private static final int MAX_CUSTOM_FIELDS_PER_TEAM = 5;
    private static final int MAX_RECORD_TEMPLATES_PER_TEAM = 20;

    private final ChartSectionSettingRepository sectionSettingRepository;
    private final ChartCustomFieldRepository customFieldRepository;
    private final ChartCustomValueRepository customValueRepository;
    private final ChartRecordRepository recordRepository;
    private final ChartRecordTemplateRepository recordTemplateRepository;
    private final ChartMapper chartMapper;

    // ========================
    // セクション設定
    // ========================

    /**
     * セクション設定を取得する。
     */
    public List<SectionSettingResponse> getSectionSettings(Long teamId) {
        List<ChartSectionSettingEntity> settings = sectionSettingRepository.findByTeamId(teamId);
        return chartMapper.toSectionSettingResponseList(settings);
    }

    /**
     * セクション設定を一括更新する。
     */
    @Transactional
    public List<SectionSettingResponse> updateSectionSettings(Long teamId, UpdateSectionSettingsRequest request) {
        for (SectionSettingRequest sectionReq : request.getSections()) {
            // enumバリデーション
            SectionType.valueOf(sectionReq.getSectionType());

            ChartSectionSettingEntity entity = sectionSettingRepository
                    .findByTeamIdAndSectionType(teamId, sectionReq.getSectionType())
                    .orElseGet(() -> ChartSectionSettingEntity.builder()
                            .teamId(teamId)
                            .sectionType(sectionReq.getSectionType())
                            .build());

            entity.updateEnabled(sectionReq.getIsEnabled());
            sectionSettingRepository.save(entity);
        }

        log.info("セクション設定更新: teamId={}", teamId);
        return getSectionSettings(teamId);
    }

    // ========================
    // カスタムフィールド
    // ========================

    /**
     * カスタムフィールド一覧を取得する。
     */
    public List<CustomFieldResponse> listCustomFields(Long teamId) {
        List<ChartCustomFieldEntity> fields = customFieldRepository.findByTeamIdOrderBySortOrder(teamId);
        return chartMapper.toCustomFieldResponseList(fields);
    }

    /**
     * カスタムフィールドを作成する。
     */
    @Transactional
    public CustomFieldResponse createCustomField(Long teamId, CreateCustomFieldRequest request) {
        // enumバリデーション
        CustomFieldType.valueOf(request.getFieldType());

        long activeCount = customFieldRepository.countByTeamIdAndIsActiveTrue(teamId);
        if (activeCount >= MAX_CUSTOM_FIELDS_PER_TEAM) {
            throw new BusinessException(ChartErrorCode.CUSTOM_FIELD_LIMIT_EXCEEDED);
        }

        ChartCustomFieldEntity entity = ChartCustomFieldEntity.builder()
                .teamId(teamId)
                .fieldName(request.getFieldName())
                .fieldType(request.getFieldType())
                .options(request.getOptions())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .build();

        ChartCustomFieldEntity saved = customFieldRepository.save(entity);
        log.info("カスタムフィールド作成: teamId={}, fieldId={}", teamId, saved.getId());
        return chartMapper.toCustomFieldResponse(saved);
    }

    /**
     * カスタムフィールドを更新する。
     */
    @Transactional
    public CustomFieldResponse updateCustomField(Long teamId, Long fieldId, UpdateCustomFieldRequest request) {
        ChartCustomFieldEntity entity = customFieldRepository.findByIdAndTeamId(fieldId, teamId)
                .orElseThrow(() -> new BusinessException(ChartErrorCode.CUSTOM_FIELD_NOT_FOUND));

        CustomFieldType.valueOf(request.getFieldType());

        entity.update(request.getFieldName(), request.getFieldType(), request.getOptions(),
                request.getSortOrder() != null ? request.getSortOrder() : entity.getSortOrder());

        ChartCustomFieldEntity saved = customFieldRepository.save(entity);
        log.info("カスタムフィールド更新: fieldId={}", fieldId);
        return chartMapper.toCustomFieldResponse(saved);
    }

    /**
     * カスタムフィールドを無効化する（論理削除）。
     */
    @Transactional
    public void deactivateCustomField(Long teamId, Long fieldId) {
        ChartCustomFieldEntity entity = customFieldRepository.findByIdAndTeamId(fieldId, teamId)
                .orElseThrow(() -> new BusinessException(ChartErrorCode.CUSTOM_FIELD_NOT_FOUND));

        entity.deactivate();
        customFieldRepository.save(entity);
        log.info("カスタムフィールド無効化: fieldId={}", fieldId);
    }

    // ========================
    // 経過グラフ
    // ========================

    /**
     * 経過グラフ用データを取得する。
     */
    public ProgressResponse getProgressData(Long teamId, Long customerUserId,
                                             String fieldIdsParam, LocalDate visitDateFrom, LocalDate visitDateTo) {
        // NUMBER型カスタムフィールドを取得
        List<ChartCustomFieldEntity> numberFields;
        if (fieldIdsParam != null && !fieldIdsParam.isBlank()) {
            List<Long> fieldIds = Arrays.stream(fieldIdsParam.split(","))
                    .map(String::trim)
                    .map(Long::parseLong)
                    .toList();
            numberFields = customFieldRepository.findByTeamIdOrderBySortOrder(teamId).stream()
                    .filter(f -> fieldIds.contains(f.getId()))
                    .toList();
        } else {
            numberFields = customFieldRepository.findByTeamIdAndFieldTypeOrderBySortOrder(teamId, "NUMBER");
        }

        List<ProgressResponse.ProgressFieldInfo> fieldInfos = numberFields.stream()
                .map(f -> new ProgressResponse.ProgressFieldInfo(f.getId(), f.getFieldName(), null))
                .toList();

        // カルテを取得
        List<ChartRecordEntity> records = recordRepository.findForProgress(
                teamId, customerUserId, visitDateFrom, visitDateTo);

        List<Long> recordIds = records.stream().map(ChartRecordEntity::getId).toList();

        // カスタム値を一括取得
        List<ChartCustomValueEntity> allValues = customValueRepository.findByChartRecordIdIn(recordIds);
        Map<Long, List<ChartCustomValueEntity>> valuesByRecord = allValues.stream()
                .collect(Collectors.groupingBy(ChartCustomValueEntity::getChartRecordId));

        // データポイントを構築
        List<ProgressResponse.ProgressDataPoint> dataPoints = records.stream()
                .map(record -> {
                    List<ChartCustomValueEntity> recordValues = valuesByRecord.getOrDefault(
                            record.getId(), List.of());
                    Map<String, String> values = new LinkedHashMap<>();
                    for (ChartCustomFieldEntity field : numberFields) {
                        recordValues.stream()
                                .filter(v -> v.getFieldId().equals(field.getId()))
                                .findFirst()
                                .ifPresent(v -> values.put(String.valueOf(field.getId()), v.getValue()));
                    }
                    return new ProgressResponse.ProgressDataPoint(record.getVisitDate(), values);
                })
                .toList();

        return new ProgressResponse(customerUserId, fieldInfos, dataPoints);
    }

    // ========================
    // カルテテンプレート
    // ========================

    /**
     * カルテテンプレート一覧を取得する。
     */
    public List<RecordTemplateResponse> listRecordTemplates(Long teamId) {
        List<ChartRecordTemplateEntity> templates = recordTemplateRepository.findByTeamIdOrderBySortOrder(teamId);
        return chartMapper.toRecordTemplateResponseList(templates);
    }

    /**
     * カルテテンプレートを作成する。
     */
    @Transactional
    public RecordTemplateResponse createRecordTemplate(Long teamId, CreateRecordTemplateRequest request) {
        long currentCount = recordTemplateRepository.countByTeamId(teamId);
        if (currentCount >= MAX_RECORD_TEMPLATES_PER_TEAM) {
            throw new BusinessException(ChartErrorCode.RECORD_TEMPLATE_LIMIT_EXCEEDED);
        }

        ChartRecordTemplateEntity entity = ChartRecordTemplateEntity.builder()
                .teamId(teamId)
                .templateName(request.getTemplateName())
                .chiefComplaint(request.getChiefComplaint())
                .treatmentNote(request.getTreatmentNote())
                .allergyInfo(request.getAllergyInfo())
                .defaultCustomFields(request.getDefaultCustomFields())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .build();

        ChartRecordTemplateEntity saved = recordTemplateRepository.save(entity);
        log.info("カルテテンプレート作成: teamId={}, templateId={}", teamId, saved.getId());
        return chartMapper.toRecordTemplateResponse(saved);
    }

    /**
     * カルテテンプレートを更新する。
     */
    @Transactional
    public RecordTemplateResponse updateRecordTemplate(Long teamId, Long templateId, UpdateRecordTemplateRequest request) {
        ChartRecordTemplateEntity entity = recordTemplateRepository.findByIdAndTeamId(templateId, teamId)
                .orElseThrow(() -> new BusinessException(ChartErrorCode.RECORD_TEMPLATE_NOT_FOUND));

        entity.update(
                request.getTemplateName(),
                request.getChiefComplaint(),
                request.getTreatmentNote(),
                request.getAllergyInfo(),
                request.getDefaultCustomFields(),
                request.getSortOrder() != null ? request.getSortOrder() : entity.getSortOrder()
        );

        ChartRecordTemplateEntity saved = recordTemplateRepository.save(entity);
        log.info("カルテテンプレート更新: templateId={}", templateId);
        return chartMapper.toRecordTemplateResponse(saved);
    }

    /**
     * カルテテンプレートを物理削除する。
     */
    @Transactional
    public void deleteRecordTemplate(Long teamId, Long templateId) {
        ChartRecordTemplateEntity entity = recordTemplateRepository.findByIdAndTeamId(templateId, teamId)
                .orElseThrow(() -> new BusinessException(ChartErrorCode.RECORD_TEMPLATE_NOT_FOUND));

        recordTemplateRepository.delete(entity);
        log.info("カルテテンプレート削除: templateId={}", templateId);
    }
}
