package com.mannschaft.app.timetable.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.timetable.TimetableVisibility;
import com.mannschaft.app.timetable.dto.CreateTimetableRequest;
import com.mannschaft.app.timetable.dto.DuplicateTimetableRequest;
import com.mannschaft.app.timetable.dto.TimetableResponse;
import com.mannschaft.app.timetable.dto.UpdateTimetableRequest;
import com.mannschaft.app.timetable.dto.WeeklyViewResponse;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.service.TimetableService;
import com.mannschaft.app.timetable.service.TimetableSlotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.mannschaft.app.common.SecurityUtils;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/timetables")
@Tag(name = "時間割管理", description = "F03.9 時間割のCRUD・ステータス遷移・複製")
@RequiredArgsConstructor
public class TimetableController {

    private final TimetableService timetableService;
    private final TimetableSlotService slotService;

    @GetMapping
    @Operation(summary = "時間割一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TimetableResponse>>> listTimetables(
            @PathVariable Long teamId) {
        List<TimetableResponse> timetables = timetableService.getByTeamId(teamId).stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(timetables));
    }

    @PostMapping
    @Operation(summary = "時間割作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TimetableResponse>> createTimetable(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateTimetableRequest request) {
        TimetableVisibility visibility = request.getVisibility() != null
                ? TimetableVisibility.valueOf(request.getVisibility())
                : TimetableVisibility.MEMBERS_ONLY;
        var data = new TimetableService.CreateTimetableData(
                request.getTermId(), request.getName(), visibility,
                request.getEffectiveFrom(), request.getEffectiveUntil(),
                request.getWeekPatternEnabled() != null ? request.getWeekPatternEnabled() : false,
                request.getWeekPatternBaseDate(), request.getPeriodOverride(),
                request.getNotes(), SecurityUtils.getCurrentUserId());
        TimetableEntity entity = timetableService.create(teamId, data);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(toResponse(entity)));
    }

    @GetMapping("/current")
    @Operation(summary = "現在有効な時間割")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TimetableResponse>> getCurrentTimetable(
            @PathVariable Long teamId) {
        return timetableService.getEffective(teamId, LocalDate.now())
                .map(e -> ResponseEntity.ok(ApiResponse.of(toResponse(e))))
                .orElse(ResponseEntity.ok(ApiResponse.of(null)));
    }

    @GetMapping("/{timetableId}")
    @Operation(summary = "時間割詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TimetableResponse>> getTimetable(
            @PathVariable Long teamId,
            @PathVariable Long timetableId) {
        TimetableEntity entity = timetableService.getById(timetableId, teamId);
        return ResponseEntity.ok(ApiResponse.of(toResponse(entity)));
    }

    @PatchMapping("/{timetableId}")
    @Operation(summary = "時間割更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TimetableResponse>> updateTimetable(
            @PathVariable Long teamId,
            @PathVariable Long timetableId,
            @Valid @RequestBody UpdateTimetableRequest request) {
        TimetableVisibility visibility = request.getVisibility() != null
                ? TimetableVisibility.valueOf(request.getVisibility()) : null;
        var data = new TimetableService.UpdateTimetableData(
                request.getName(), visibility,
                request.getEffectiveFrom(), request.getEffectiveUntil(),
                request.getWeekPatternEnabled(), request.getWeekPatternBaseDate(),
                request.getPeriodOverride(), request.getNotes());
        TimetableEntity entity = timetableService.update(timetableId, teamId, data);
        return ResponseEntity.ok(ApiResponse.of(toResponse(entity)));
    }

    @DeleteMapping("/{timetableId}")
    @Operation(summary = "時間割削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteTimetable(
            @PathVariable Long teamId,
            @PathVariable Long timetableId) {
        timetableService.delete(timetableId, teamId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{timetableId}/activate")
    @Operation(summary = "時間割有効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "有効化成功")
    public ResponseEntity<ApiResponse<TimetableResponse>> activateTimetable(
            @PathVariable Long teamId,
            @PathVariable Long timetableId) {
        TimetableEntity entity = timetableService.activate(timetableId, teamId);
        return ResponseEntity.ok(ApiResponse.of(toResponse(entity)));
    }

    @PostMapping("/{timetableId}/archive")
    @Operation(summary = "時間割アーカイブ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アーカイブ成功")
    public ResponseEntity<ApiResponse<TimetableResponse>> archiveTimetable(
            @PathVariable Long teamId,
            @PathVariable Long timetableId) {
        TimetableEntity entity = timetableService.archive(timetableId, teamId);
        return ResponseEntity.ok(ApiResponse.of(toResponse(entity)));
    }

    @PostMapping("/{timetableId}/revert-to-draft")
    @Operation(summary = "下書きに戻す")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "戻し成功")
    public ResponseEntity<ApiResponse<TimetableResponse>> revertToDraft(
            @PathVariable Long teamId,
            @PathVariable Long timetableId) {
        TimetableEntity entity = timetableService.revertToDraft(timetableId, teamId);
        return ResponseEntity.ok(ApiResponse.of(toResponse(entity)));
    }

    @PostMapping("/{timetableId}/duplicate")
    @Operation(summary = "時間割複製")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "複製成功")
    public ResponseEntity<ApiResponse<TimetableResponse>> duplicateTimetable(
            @PathVariable Long teamId,
            @PathVariable Long timetableId,
            @Valid @RequestBody DuplicateTimetableRequest request) {
        var data = new TimetableService.DuplicateTimetableData(
                request.getTargetTermId(), request.getName(),
                request.getEffectiveFrom(), request.getEffectiveUntil(),
                SecurityUtils.getCurrentUserId());
        TimetableEntity entity = timetableService.duplicate(timetableId, teamId, data);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(toResponse(entity)));
    }

    @GetMapping("/{timetableId}/weekly")
    @Operation(summary = "週間表示")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<WeeklyViewResponse>> getWeeklyView(
            @PathVariable Long teamId,
            @PathVariable Long timetableId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekOf) {
        TimetableEntity timetable = timetableService.getById(timetableId, teamId);
        LocalDate targetDate = weekOf != null ? weekOf : LocalDate.now();
        TimetableSlotService.WeeklyViewData viewData = slotService.getWeeklyView(timetableId, timetable, targetDate);

        // WeeklyViewData → WeeklyViewResponse に変換
        Map<String, WeeklyViewResponse.DayInfo> days = viewData.days().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            TimetableSlotService.DayViewData dayData = entry.getValue();
                            List<WeeklyViewResponse.SlotInfo> slotInfos = dayData.slots().stream()
                                    .map(slot -> new WeeklyViewResponse.SlotInfo(
                                            slot.periodNumber(),
                                            slot.subjectName(),
                                            slot.teacherName(),
                                            slot.roomName(),
                                            slot.color(),
                                            slot.notes(),
                                            slot.isChanged(),
                                            slot.originalSubject(),
                                            slot.changeType() != null ? slot.changeType().name() : null,
                                            slot.changeReason()))
                                    .toList();
                            return new WeeklyViewResponse.DayInfo(
                                    dayData.date(),
                                    dayData.isDayOff(),
                                    dayData.dayOffReason(),
                                    slotInfos);
                        },
                        (a, b) -> a,
                        java.util.LinkedHashMap::new
                ));

        // periods は現時点では空リスト（時限定義はTimetablePeriodTemplateServiceから取得可能だが
        // timetableId→teamId→orgId のルックアップが必要なためControllerでは省略し、
        // フロントエンドは別途 GET /organizations/{id}/timetable-periods で取得する）
        List<WeeklyViewResponse.PeriodInfo> periodInfos = List.of();

        var response = new WeeklyViewResponse(
                viewData.timetableId(),
                timetable.getName(),
                viewData.weekStart(),
                viewData.weekEnd(),
                viewData.weekPatternEnabled(),
                viewData.currentWeekPattern().name(),
                periodInfos,
                days);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    private TimetableResponse toResponse(TimetableEntity entity) {
        return new TimetableResponse(
                entity.getId(), entity.getName(), entity.getTermId(),
                null, // termName - resolved at service layer if needed
                entity.getStatus().name(), entity.getVisibility().name(),
                entity.getEffectiveFrom(), entity.getEffectiveUntil(),
                entity.getWeekPatternEnabled(), entity.getWeekPatternBaseDate(),
                entity.getPeriodOverride(), entity.getNotes(), entity.getCreatedAt());
    }
}
