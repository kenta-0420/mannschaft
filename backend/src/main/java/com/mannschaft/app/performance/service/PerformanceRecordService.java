package com.mannschaft.app.performance.service;

import com.mannschaft.app.common.AccessControlService;
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
import com.mannschaft.app.schedule.service.ScheduleService;
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
    private final AccessControlService accessControlService;
    private final ScheduleService scheduleService;

    /** F00.5 メンバーシップ・ロール判定のスコープ種別（チーム）。 */
    private static final String SCOPE_TEAM = "TEAM";

    /**
     * パフォーマンス記録を入力する。
     * 他メンバーへの成績入力のため ADMIN/DEPUTY_ADMIN 専用（自己記録は {@link #createSelfRecord} 参照）。
     *
     * @param teamId      チームID
     * @param currentUserId 現在のユーザーID
     * @param request     作成リクエスト
     * @return 記録レスポンス
     */
    @Transactional
    public RecordResponse createRecord(Long teamId, Long currentUserId, CreateRecordRequest request) {
        // 変更系（作成）: 作成先スコープ（path の teamId）で checkAdminOrAbove。
        accessControlService.checkAdminOrAbove(currentUserId, teamId, SCOPE_TEAM);

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
     * @param teamId        チームID
     * @param id            記録ID
     * @param actorUserId   操作ユーザーID
     * @param request       更新リクエスト
     * @return 更新した記録レスポンス
     */
    @Transactional
    public RecordResponse updateRecord(Long teamId, Long id, Long actorUserId, UpdateRecordRequest request) {
        PerformanceRecordEntity entity = recordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.RECORD_NOT_FOUND));

        // BOLA厳禁: 記録自体は teamId 列を持たないため、紐づく指標が対象チーム配下かを
        // metricService.getMetricEntity(teamId, ...) で検証する（他チームの記録IDは404で秘匿）。
        PerformanceMetricEntity metric = metricService.getMetricEntity(teamId, entity.getMetricId());
        // 変更系（更新）: 上記で teamId 整合が確認済みのスコープで checkAdminOrAbove。
        accessControlService.checkAdminOrAbove(actorUserId, teamId, SCOPE_TEAM);
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
     * @param teamId      チームID
     * @param id          記録ID
     * @param actorUserId 操作ユーザーID
     */
    @Transactional
    public void deleteRecord(Long teamId, Long id, Long actorUserId) {
        PerformanceRecordEntity entity = recordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.RECORD_NOT_FOUND));

        // BOLA厳禁: 指標がこのチームに所属しているか確認（他チームの記録IDは404で秘匿）。
        metricService.getMetricEntity(teamId, entity.getMetricId());
        // 変更系（削除）: 上記で teamId 整合が確認済みのスコープで checkAdminOrAbove。
        accessControlService.checkAdminOrAbove(actorUserId, teamId, SCOPE_TEAM);

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
        // 変更系（一括入力）: バリデーションループが個別エラーを丸めてしまう前に top-level 403 として拒否する。
        accessControlService.checkAdminOrAbove(currentUserId, teamId, SCOPE_TEAM);

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
        // 変更系（スケジュール一括入力）: 作成先スコープ（path の teamId）で checkAdminOrAbove。
        accessControlService.checkAdminOrAbove(currentUserId, teamId, SCOPE_TEAM);
        // BOLA厳禁: scheduleId が path teamId 配下のスケジュールかを検証する（越境窓口 = ScheduleService）。
        // 他チームの scheduleId を渡して記録を紐付けられてしまう欠陥（scope 未検証）を根治する。
        if (!scheduleService.existsByIdAndTeamId(scheduleId, teamId)) {
            throw new BusinessException(PerformanceErrorCode.SCHEDULE_NOT_FOUND);
        }
        LocalDate recordedDate = LocalDate.now(); // Schedule連携後にschedule.start_dateを使用

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
        // 自己記録: 自分自身のスコープ所属のみ要求（checkMembership）。他ユーザーの代理記録は不可
        // （userId は下で currentUserId 固定のため、対象を他人に差し替えることはできない）。
        accessControlService.checkMembership(currentUserId, teamId, SCOPE_TEAM);

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
