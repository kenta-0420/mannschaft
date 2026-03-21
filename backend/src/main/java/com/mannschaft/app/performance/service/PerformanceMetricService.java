package com.mannschaft.app.performance.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.performance.AggregationType;
import com.mannschaft.app.performance.MetricDataType;
import com.mannschaft.app.performance.PerformanceErrorCode;
import com.mannschaft.app.performance.PerformanceMapper;
import com.mannschaft.app.performance.dto.CreateMetricRequest;
import com.mannschaft.app.performance.dto.FromTemplateRequest;
import com.mannschaft.app.performance.dto.FromTemplateResponse;
import com.mannschaft.app.performance.dto.LinkableFieldResponse;
import com.mannschaft.app.performance.dto.MetricResponse;
import com.mannschaft.app.performance.dto.SortOrderRequest;
import com.mannschaft.app.performance.dto.SortOrderResponse;
import com.mannschaft.app.performance.dto.TemplateListResponse;
import com.mannschaft.app.performance.dto.UpdateMetricRequest;
import com.mannschaft.app.performance.entity.PerformanceMetricEntity;
import com.mannschaft.app.performance.entity.PerformanceMetricTemplateEntity;
import com.mannschaft.app.performance.repository.PerformanceMetricRepository;
import com.mannschaft.app.performance.repository.PerformanceMetricTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * パフォーマンス指標サービス。指標定義のCRUD・テンプレート管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PerformanceMetricService {

    private static final int MAX_ACTIVE_METRICS = 30;

    private final PerformanceMetricRepository metricRepository;
    private final PerformanceMetricTemplateRepository templateRepository;
    private final PerformanceMapper performanceMapper;

    /**
     * チームの指標定義一覧を取得する。
     *
     * @param teamId チームID
     * @return 指標レスポンスリスト
     */
    public List<MetricResponse> listMetrics(Long teamId) {
        List<PerformanceMetricEntity> metrics = metricRepository.findByTeamIdOrderBySortOrderAsc(teamId);
        return performanceMapper.toMetricResponseList(metrics);
    }

    /**
     * 指標定義を作成する。
     *
     * @param teamId      チームID
     * @param request     作成リクエスト
     * @return 作成した指標レスポンス
     */
    @Transactional
    public MetricResponse createMetric(Long teamId, CreateMetricRequest request) {
        long activeCount = metricRepository.countByTeamIdAndIsActiveTrue(teamId);
        if (activeCount >= MAX_ACTIVE_METRICS) {
            throw new BusinessException(PerformanceErrorCode.METRIC_LIMIT_EXCEEDED);
        }

        PerformanceMetricEntity entity = PerformanceMetricEntity.builder()
                .teamId(teamId)
                .name(request.getName())
                .unit(request.getUnit())
                .dataType(request.getDataType() != null ? MetricDataType.valueOf(request.getDataType()) : MetricDataType.DECIMAL)
                .aggregationType(request.getAggregationType() != null ? AggregationType.valueOf(request.getAggregationType()) : AggregationType.SUM)
                .description(request.getDescription())
                .groupName(request.getGroupName())
                .targetValue(request.getTargetValue())
                .minValue(request.getMinValue())
                .maxValue(request.getMaxValue())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .isVisibleToMembers(request.getIsVisibleToMembers() != null ? request.getIsVisibleToMembers() : true)
                .isSelfRecordable(request.getIsSelfRecordable() != null ? request.getIsSelfRecordable() : false)
                .linkedActivityFieldId(request.getLinkedActivityFieldId())
                .build();

        entity = metricRepository.save(entity);
        return performanceMapper.toMetricResponse(entity);
    }

    /**
     * 指標定義を更新する。
     *
     * @param teamId チームID
     * @param id     指標ID
     * @param request 更新リクエスト
     * @return 更新した指標レスポンス
     */
    @Transactional
    public MetricResponse updateMetric(Long teamId, Long id, UpdateMetricRequest request) {
        PerformanceMetricEntity entity = metricRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.METRIC_NOT_FOUND));

        entity.update(
                request.getName(),
                request.getUnit(),
                request.getDataType() != null ? MetricDataType.valueOf(request.getDataType()) : entity.getDataType(),
                request.getAggregationType() != null ? AggregationType.valueOf(request.getAggregationType()) : entity.getAggregationType(),
                request.getDescription(),
                request.getGroupName(),
                request.getTargetValue(),
                request.getMinValue(),
                request.getMaxValue(),
                request.getSortOrder() != null ? request.getSortOrder() : entity.getSortOrder(),
                request.getIsVisibleToMembers() != null ? request.getIsVisibleToMembers() : entity.getIsVisibleToMembers(),
                request.getIsSelfRecordable() != null ? request.getIsSelfRecordable() : entity.getIsSelfRecordable(),
                request.getLinkedActivityFieldId()
        );

        entity = metricRepository.save(entity);
        return performanceMapper.toMetricResponse(entity);
    }

    /**
     * 指標定義を無効化する（論理削除）。
     *
     * @param teamId チームID
     * @param id     指標ID
     */
    @Transactional
    public void deactivateMetric(Long teamId, Long id) {
        PerformanceMetricEntity entity = metricRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.METRIC_NOT_FOUND));
        entity.deactivate();
        metricRepository.save(entity);
    }

    /**
     * テンプレートから指標を一括作成する。
     *
     * @param teamId  チームID
     * @param request テンプレート適用リクエスト
     * @return 作成結果レスポンス
     */
    @Transactional
    public FromTemplateResponse createFromTemplate(Long teamId, FromTemplateRequest request) {
        List<PerformanceMetricTemplateEntity> templates =
                templateRepository.findBySportCategoryOrderBySortOrderAsc(request.getSportCategory());
        if (templates.isEmpty()) {
            throw new BusinessException(PerformanceErrorCode.TEMPLATE_NOT_FOUND);
        }

        Set<String> excludeNames = request.getExcludeNames() != null
                ? Set.copyOf(request.getExcludeNames())
                : Collections.emptySet();

        List<String> existingNames = metricRepository.findActiveNamesByTeamId(teamId);
        Set<String> existingNameSet = Set.copyOf(existingNames);

        List<PerformanceMetricTemplateEntity> toCreate = templates.stream()
                .filter(t -> !excludeNames.contains(t.getName()))
                .filter(t -> !existingNameSet.contains(t.getName()))
                .toList();

        long activeCount = metricRepository.countByTeamIdAndIsActiveTrue(teamId);
        if (activeCount + toCreate.size() > MAX_ACTIVE_METRICS) {
            throw new BusinessException(PerformanceErrorCode.METRIC_LIMIT_EXCEEDED);
        }

        List<PerformanceMetricEntity> created = new ArrayList<>();
        for (PerformanceMetricTemplateEntity template : toCreate) {
            PerformanceMetricEntity entity = PerformanceMetricEntity.builder()
                    .teamId(teamId)
                    .name(template.getName())
                    .unit(template.getUnit())
                    .dataType(template.getDataType())
                    .aggregationType(template.getAggregationType())
                    .description(template.getDescription())
                    .groupName(template.getGroupName())
                    .sortOrder(template.getSortOrder())
                    .minValue(template.getMinValue())
                    .maxValue(template.getMaxValue())
                    .isSelfRecordable(template.getIsSelfRecordable())
                    .build();
            created.add(metricRepository.save(entity));
        }

        return new FromTemplateResponse(
                created.size(),
                performanceMapper.toMetricResponseList(created)
        );
    }

    /**
     * 指標の並び順を一括更新する。
     *
     * @param teamId  チームID
     * @param request 並び順更新リクエスト
     * @return 更新結果レスポンス
     */
    @Transactional
    public SortOrderResponse updateSortOrder(Long teamId, SortOrderRequest request) {
        Map<Long, Integer> orderMap = request.getOrders().stream()
                .collect(Collectors.toMap(SortOrderRequest.SortOrderEntry::getId, SortOrderRequest.SortOrderEntry::getSortOrder));

        int updated = 0;
        for (Map.Entry<Long, Integer> entry : orderMap.entrySet()) {
            PerformanceMetricEntity metric = metricRepository.findByIdAndTeamId(entry.getKey(), teamId)
                    .orElseThrow(() -> new BusinessException(PerformanceErrorCode.METRIC_NOT_FOUND));
            metric.updateSortOrder(entry.getValue());
            metricRepository.save(metric);
            updated++;
        }

        return new SortOrderResponse(updated);
    }

    /**
     * 指標テンプレート一覧を取得する。
     *
     * @param sportCategory スポーツカテゴリ（null の場合は全件）
     * @return テンプレート一覧レスポンス
     */
    public TemplateListResponse listTemplates(String sportCategory) {
        List<String> categories = templateRepository.findDistinctSportCategories();
        List<PerformanceMetricTemplateEntity> allTemplates;

        if (sportCategory != null && !sportCategory.isBlank()) {
            allTemplates = templateRepository.findBySportCategoryOrderBySortOrderAsc(sportCategory);
        } else {
            allTemplates = templateRepository.findAllByOrderBySportCategoryAscSortOrderAsc();
        }

        Map<String, List<PerformanceMetricTemplateEntity>> grouped = allTemplates.stream()
                .collect(Collectors.groupingBy(PerformanceMetricTemplateEntity::getSportCategory));

        List<TemplateListResponse.CategoryTemplates> templateList = grouped.entrySet().stream()
                .map(e -> new TemplateListResponse.CategoryTemplates(
                        e.getKey(),
                        e.getValue().stream().map(performanceMapper::toTemplateMetric).toList()
                ))
                .toList();

        return new TemplateListResponse(categories, templateList);
    }

    /**
     * 活動記録連携可能なカスタムフィールド一覧を取得する。
     * TODO: F06.4 activity_custom_fields テーブルからPARTICIPANT/NUMBERフィールドを取得する
     *
     * @param teamId チームID
     * @return 連携可能フィールドリスト
     */
    public List<LinkableFieldResponse> listLinkableFields(Long teamId) {
        // TODO: F06.4 integration - query activity_custom_fields for PARTICIPANT scope + NUMBER type
        // For now, return empty list as placeholder
        return Collections.emptyList();
    }

    /**
     * チームの指標エンティティを取得する（内部用）。
     */
    public PerformanceMetricEntity getMetricEntity(Long teamId, Long metricId) {
        return metricRepository.findByIdAndTeamId(metricId, teamId)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.METRIC_NOT_FOUND));
    }

    /**
     * チームの有効な指標一覧を取得する（内部用）。
     */
    public List<PerformanceMetricEntity> getActiveMetrics(Long teamId) {
        return metricRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(teamId);
    }

    /**
     * チームのメンバー公開指標一覧を取得する（内部用）。
     */
    public List<PerformanceMetricEntity> getVisibleMetrics(Long teamId) {
        return metricRepository.findByTeamIdAndIsVisibleToMembersTrueOrderBySortOrderAsc(teamId);
    }
}
