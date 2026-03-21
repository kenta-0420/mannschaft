package com.mannschaft.app.performance.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.performance.MetricDataType;
import com.mannschaft.app.performance.PerformanceErrorCode;
import com.mannschaft.app.performance.PerformanceMapper;
import com.mannschaft.app.performance.RecordSource;
import com.mannschaft.app.performance.dto.BulkRecordRequest;
import com.mannschaft.app.performance.dto.BulkRecordResponse;
import com.mannschaft.app.performance.dto.CreateRecordRequest;
import com.mannschaft.app.performance.dto.RecordResponse;
import com.mannschaft.app.performance.dto.ScheduleBulkRecordRequest;
import com.mannschaft.app.performance.dto.SelfRecordRequest;
import com.mannschaft.app.performance.dto.UpdateRecordRequest;
import com.mannschaft.app.performance.entity.PerformanceMetricEntity;
import com.mannschaft.app.performance.entity.PerformanceRecordEntity;
import com.mannschaft.app.performance.repository.PerformanceRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * パフォーマンス記録サービス。記録のCRUD・一括入力を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PerformanceRecordService {

    private final PerformanceRecordRepository recordRepository;
    private final PerformanceMetricService metricService;
    private final PerformanceSummaryService summaryService;
    private final PerformanceMapper performanceMapper;

    /**
     * パフォーマンス記録を入力する。
     *
     * @param teamId      チームID
     * @param currentUserId 現在のユーザーID
     * @param request     作成リクエスト
     * @return 記録レスポンス
     */
    @Transactional
    public RecordResponse createRecord(Long teamId, Long currentUserId, CreateRecordRequest request) {
        PerformanceMetricEntity metric = metricService.getMetricEntity(teamId, request.getMetricId());
        validateValue(metric, request.getValue());

        PerformanceRecordEntity entity = PerformanceRecordEntity.builder()
                .metricId(request.getMetricId())
                .userId(request.getUserId())
                .scheduleId(request.getScheduleId())
                .recordedDate(request.getRecordedDate())
                .value(request.getValue())
                .note(request.getNote())
                .source(RecordSource.ADMIN)
                .recordedBy(currentUserId)
                .build();

        entity = recordRepository.save(entity);
        summaryService.recalculateSummary(entity.getMetricId(), entity.getUserId(), entity.getRecordedDate());
        return performanceMapper.toRecordResponse(entity, metric.getName(), metric.getUnit());
    }

    /**
     * パフォーマンス記録を更新する。
     *
     * @param teamId チームID
     * @param id     記録ID
     * @param request 更新リクエスト
     * @return 更新した記録レスポンス
     */
    @Transactional
    public RecordResponse updateRecord(Long teamId, Long id, UpdateRecordRequest request) {
        PerformanceRecordEntity entity = recordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.RECORD_NOT_FOUND));

        PerformanceMetricEntity metric = metricService.getMetricEntity(teamId, entity.getMetricId());
        validateValue(metric, request.getValue());

        LocalDate oldDate = entity.getRecordedDate();
        entity.update(request.getValue(), request.getNote(), request.getRecordedDate());
        entity = recordRepository.save(entity);

        // 旧月と新月のサマリーを再計算
        summaryService.recalculateSummary(entity.getMetricId(), entity.getUserId(), oldDate);
        if (!oldDate.equals(request.getRecordedDate())) {
            summaryService.recalculateSummary(entity.getMetricId(), entity.getUserId(), request.getRecordedDate());
        }

        return performanceMapper.toRecordResponse(entity, metric.getName(), metric.getUnit());
    }

    /**
     * パフォーマンス記録を削除する。
     *
     * @param teamId チームID
     * @param id     記録ID
     */
    @Transactional
    public void deleteRecord(Long teamId, Long id) {
        PerformanceRecordEntity entity = recordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.RECORD_NOT_FOUND));

        // 指標がこのチームに所属しているか確認
        metricService.getMetricEntity(teamId, entity.getMetricId());

        recordRepository.delete(entity);
        summaryService.recalculateSummary(entity.getMetricId(), entity.getUserId(), entity.getRecordedDate());
    }

    /**
     * 一括記録入力する。
     *
     * @param teamId      チームID
     * @param currentUserId 現在のユーザーID
     * @param request     一括記録リクエスト
     * @return 一括記録レスポンス
     */
    @Transactional
    public BulkRecordResponse createBulkRecords(Long teamId, Long currentUserId, BulkRecordRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();

        for (int i = 0; i < request.getEntries().size(); i++) {
            BulkRecordRequest.Entry entry = request.getEntries().get(i);
            try {
                PerformanceMetricEntity metric = metricService.getMetricEntity(teamId, entry.getMetricId());
                validateValue(metric, entry.getValue());
            } catch (BusinessException e) {
                fieldErrors.add(new ErrorResponse.FieldError(
                        "entries[" + i + "].value", e.getMessage()));
            }
        }

        if (!fieldErrors.isEmpty()) {
            throw new BusinessException(PerformanceErrorCode.BULK_VALIDATION_FAILED, fieldErrors);
        }

        int created = 0;
        for (BulkRecordRequest.Entry entry : request.getEntries()) {
            PerformanceRecordEntity entity = PerformanceRecordEntity.builder()
                    .metricId(entry.getMetricId())
                    .userId(entry.getUserId())
                    .recordedDate(request.getRecordedDate())
                    .value(entry.getValue())
                    .note(request.getNote())
                    .source(RecordSource.ADMIN)
                    .recordedBy(currentUserId)
                    .build();
            recordRepository.save(entity);
            summaryService.recalculateSummary(entity.getMetricId(), entity.getUserId(), entity.getRecordedDate());
            created++;
        }

        return new BulkRecordResponse(created, null, null, request.getRecordedDate());
    }

    /**
     * スケジュールからの一括記録入力する。
     *
     * @param teamId      チームID
     * @param scheduleId  スケジュールID
     * @param currentUserId 現在のユーザーID
     * @param request     スケジュール一括記録リクエスト
     * @return 一括記録レスポンス
     */
    @Transactional
    public BulkRecordResponse createScheduleBulkRecords(Long teamId, Long scheduleId, Long currentUserId,
                                                         ScheduleBulkRecordRequest request) {
        // TODO: Validate scheduleId exists and belongs to team via ScheduleService
        LocalDate recordedDate = LocalDate.now(); // TODO: Get from schedule's start_date

        int created = 0;
        for (ScheduleBulkRecordRequest.Entry entry : request.getEntries()) {
            PerformanceMetricEntity metric = metricService.getMetricEntity(teamId, entry.getMetricId());
            validateValue(metric, entry.getValue());

            PerformanceRecordEntity entity = PerformanceRecordEntity.builder()
                    .metricId(entry.getMetricId())
                    .userId(entry.getUserId())
                    .scheduleId(scheduleId)
                    .recordedDate(recordedDate)
                    .value(entry.getValue())
                    .source(RecordSource.SCHEDULE)
                    .recordedBy(currentUserId)
                    .build();
            recordRepository.save(entity);
            summaryService.recalculateSummary(entity.getMetricId(), entity.getUserId(), entity.getRecordedDate());
            created++;
        }

        return new BulkRecordResponse(created, scheduleId, null, recordedDate);
    }

    /**
     * MEMBER 自己記録入力する。
     *
     * @param teamId      チームID
     * @param currentUserId 現在のユーザーID
     * @param request     自己記録リクエスト
     * @return 記録レスポンス
     */
    @Transactional
    public RecordResponse createSelfRecord(Long teamId, Long currentUserId, SelfRecordRequest request) {
        PerformanceMetricEntity metric = metricService.getMetricEntity(teamId, request.getMetricId());

        if (!metric.getIsSelfRecordable()) {
            throw new BusinessException(PerformanceErrorCode.SELF_RECORD_NOT_ALLOWED);
        }

        validateValue(metric, request.getValue());

        PerformanceRecordEntity entity = PerformanceRecordEntity.builder()
                .metricId(request.getMetricId())
                .userId(currentUserId)
                .recordedDate(request.getRecordedDate())
                .value(request.getValue())
                .note(request.getNote())
                .source(RecordSource.SELF)
                .recordedBy(currentUserId)
                .build();

        entity = recordRepository.save(entity);
        summaryService.recalculateSummary(entity.getMetricId(), entity.getUserId(), entity.getRecordedDate());
        return performanceMapper.toRecordResponse(entity, metric.getName(), metric.getUnit());
    }

    /**
     * 値のバリデーションを行う。
     */
    private void validateValue(PerformanceMetricEntity metric, BigDecimal value) {
        // INTEGER型チェック
        if (metric.getDataType() == MetricDataType.INTEGER) {
            if (value.stripTrailingZeros().scale() > 0) {
                throw new BusinessException(PerformanceErrorCode.INTEGER_VALUE_REQUIRED);
            }
        }

        // 範囲チェック
        if (metric.getMinValue() != null && value.compareTo(metric.getMinValue()) < 0) {
            throw new BusinessException(PerformanceErrorCode.VALUE_OUT_OF_RANGE);
        }
        if (metric.getMaxValue() != null && value.compareTo(metric.getMaxValue()) > 0) {
            throw new BusinessException(PerformanceErrorCode.VALUE_OUT_OF_RANGE);
        }
    }
}
