package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.schedule.DateShiftMode;
import com.mannschaft.app.schedule.dto.AnnualEventViewResponse;
import com.mannschaft.app.schedule.dto.CopyLogResponse;
import com.mannschaft.app.schedule.dto.CopyPreviewResponse;
import com.mannschaft.app.schedule.dto.EventCategoryResponse;
import com.mannschaft.app.schedule.dto.ExecuteCopyRequest;
import com.mannschaft.app.schedule.dto.ExecuteCopyResponse;
import com.mannschaft.app.schedule.entity.ScheduleAnnualCopyLogEntity;
import com.mannschaft.app.schedule.entity.ScheduleEventCategoryEntity;
import com.mannschaft.app.schedule.service.ScheduleAnnualCopyService;
import com.mannschaft.app.schedule.service.ScheduleAnnualViewService;
import com.mannschaft.app.schedule.service.ScheduleEventCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * チーム年間行事コントローラー。チームスコープの年間ビュー・コピーAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/schedules/annual")
@Tag(name = "チーム年間行事管理", description = "F03.10 チームスコープの年間行事ビュー・コピー")
@RequiredArgsConstructor
public class TeamAnnualScheduleController {

    private final ScheduleAnnualViewService annualViewService;
    private final ScheduleAnnualCopyService annualCopyService;
    private final ScheduleEventCategoryService categoryService;

    /**
     * チーム年間行事ビューを取得する。
     */
    @GetMapping
    @Operation(summary = "チーム年間行事ビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<AnnualEventViewResponse>> getAnnualView(
            @PathVariable Long teamId,
            @RequestParam(name = "academic_year") Integer academicYear,
            @RequestParam(name = "category_id", required = false) List<Long> categoryIds,
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(name = "term_start_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate termStartDate,
            @RequestParam(name = "term_end_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate termEndDate) {

        ScheduleAnnualViewService.AnnualViewData viewData = annualViewService.getAnnualView(
                teamId, true, academicYear, categoryIds, eventType, termStartDate, termEndDate);

        // TODO: teamId から organizationId を取得するロジックを実装
        Long organizationId = null;
        List<ScheduleEventCategoryEntity> categoryEntities =
                categoryService.getCategoriesForTeam(teamId, organizationId);

        AnnualEventViewResponse response = toAnnualViewResponse(viewData, categoryEntities);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チーム年間行事コピープレビューを取得する。
     */
    @GetMapping("/preview-copy")
    @Operation(summary = "チーム年間行事コピープレビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<CopyPreviewResponse>> previewCopy(
            @PathVariable Long teamId,
            @RequestParam(name = "source_year") Integer sourceYear,
            @RequestParam(name = "target_year") Integer targetYear,
            @RequestParam(name = "date_shift_mode", defaultValue = "SAME_WEEKDAY") String dateShiftMode,
            @RequestParam(name = "category_id", required = false) List<Long> categoryIds) {

        DateShiftMode mode = DateShiftMode.valueOf(dateShiftMode);
        ScheduleAnnualCopyService.PreviewResult previewResult =
                annualCopyService.previewCopy(teamId, true, sourceYear, targetYear, mode, categoryIds);

        CopyPreviewResponse response = toPreviewResponse(sourceYear, targetYear, dateShiftMode, previewResult);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チーム年間行事コピーを実行する。
     */
    @PostMapping("/copy")
    @Operation(summary = "チーム年間行事コピー実行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "コピー成功")
    public ResponseEntity<ApiResponse<ExecuteCopyResponse>> executeCopy(
            @PathVariable Long teamId,
            @Valid @RequestBody ExecuteCopyRequest request) {

        DateShiftMode mode = request.getDateShiftMode() != null
                ? DateShiftMode.valueOf(request.getDateShiftMode())
                : DateShiftMode.SAME_WEEKDAY;

        List<ScheduleAnnualCopyService.CopyItem> copyItems = request.getItems().stream()
                .map(item -> new ScheduleAnnualCopyService.CopyItem(
                        item.getSourceScheduleId(),
                        item.getTargetStartAt(),
                        item.getTargetEndAt(),
                        item.getInclude()))
                .toList();

        ScheduleAnnualCopyService.CopyResult result = annualCopyService.executeCopy(
                teamId, true, request.getSourceYear(), request.getTargetYear(),
                mode, copyItems, SecurityUtils.getCurrentUserId());

        ExecuteCopyResponse response = new ExecuteCopyResponse(
                result.copyLogId(),
                result.totalCopied(),
                result.totalSkipped(),
                result.createdScheduleIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * チーム年間行事コピーログ一覧を取得する。
     */
    @GetMapping("/copy-logs")
    @Operation(summary = "チーム年間行事コピーログ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CopyLogResponse>>> getCopyLogs(
            @PathVariable Long teamId) {
        List<ScheduleAnnualCopyLogEntity> logs = annualCopyService.getCopyLogs(teamId, true);
        List<CopyLogResponse> responses = logs.stream()
                .map(this::toCopyLogResponse)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    // ── Private mapping methods ──

    private AnnualEventViewResponse toAnnualViewResponse(
            ScheduleAnnualViewService.AnnualViewData viewData,
            List<ScheduleEventCategoryEntity> categoryEntities) {

        List<EventCategoryResponse> categories = categoryEntities.stream()
                .map(this::toCategoryResponse)
                .toList();

        int totalEvents = viewData.months().stream()
                .mapToInt(m -> m.events().size())
                .sum();

        List<AnnualEventViewResponse.MonthEvents> months = viewData.months().stream()
                .map(m -> new AnnualEventViewResponse.MonthEvents(
                        m.month().toString(),
                        m.events().stream()
                                .map(this::toAnnualEventItem)
                                .toList()))
                .toList();

        return new AnnualEventViewResponse(
                viewData.academicYear(),
                viewData.yearStart(),
                viewData.yearEnd(),
                categories,
                months,
                totalEvents);
    }

    private AnnualEventViewResponse.AnnualEventItem toAnnualEventItem(
            ScheduleAnnualViewService.AnnualEventData event) {
        EventCategoryResponse categoryResponse = null;
        if (event.eventCategory() != null) {
            ScheduleAnnualViewService.EventCategoryData cat = event.eventCategory();
            categoryResponse = new EventCategoryResponse(
                    cat.id(), cat.name(), cat.color(), cat.icon(),
                    null, null, null);
        }
        return new AnnualEventViewResponse.AnnualEventItem(
                event.id(),
                event.title(),
                event.startAt(),
                event.endAt(),
                event.allDay(),
                event.eventType(),
                categoryResponse,
                event.status(),
                event.sourceScheduleId());
    }

    private CopyPreviewResponse toPreviewResponse(
            Integer sourceYear, Integer targetYear, String dateShiftMode,
            ScheduleAnnualCopyService.PreviewResult previewResult) {

        List<CopyPreviewResponse.CopyPreviewItem> items = previewResult.items().stream()
                .map(this::toPreviewItem)
                .toList();

        int totalCopyable = (int) previewResult.items().stream()
                .filter(item -> item.conflict() == null)
                .count();
        int totalWithConflicts = (int) previewResult.items().stream()
                .filter(item -> item.conflict() != null)
                .count();

        return new CopyPreviewResponse(
                sourceYear, targetYear, dateShiftMode,
                items, totalCopyable, totalWithConflicts);
    }

    private CopyPreviewResponse.CopyPreviewItem toPreviewItem(
            ScheduleAnnualCopyService.PreviewItem item) {
        EventCategoryResponse categoryResponse = null;
        if (item.eventCategory() != null) {
            ScheduleAnnualViewService.EventCategoryData cat = item.eventCategory();
            categoryResponse = new EventCategoryResponse(
                    cat.id(), cat.name(), cat.color(), cat.icon(),
                    null, null, null);
        }

        CopyPreviewResponse.CopyConflict conflict = null;
        if (item.conflict() != null) {
            conflict = new CopyPreviewResponse.CopyConflict(
                    item.conflict().type(),
                    item.conflict().existingScheduleId(),
                    item.conflict().existingTitle());
        }

        return new CopyPreviewResponse.CopyPreviewItem(
                item.sourceScheduleId(),
                item.title(),
                item.sourceStartAt(),
                item.sourceEndAt(),
                item.suggestedStartAt(),
                item.suggestedEndAt(),
                item.dateShiftNote(),
                categoryResponse,
                item.allDay(),
                conflict);
    }

    private CopyLogResponse toCopyLogResponse(ScheduleAnnualCopyLogEntity log) {
        return new CopyLogResponse(
                log.getId(),
                log.getSourceAcademicYear(),
                log.getTargetAcademicYear(),
                log.getTotalCopied(),
                log.getTotalSkipped(),
                log.getDateShiftMode() != null ? log.getDateShiftMode().name() : null,
                log.getExecutedBy(),
                log.getCreatedAt());
    }

    private EventCategoryResponse toCategoryResponse(ScheduleEventCategoryEntity entity) {
        String scope = entity.isTeamScope() ? "TEAM" : "ORGANIZATION";
        return new EventCategoryResponse(
                entity.getId(),
                entity.getName(),
                entity.getColor(),
                entity.getIcon(),
                entity.getIsDayOffCategory(),
                entity.getSortOrder(),
                scope);
    }
}
