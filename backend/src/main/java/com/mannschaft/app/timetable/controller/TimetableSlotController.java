package com.mannschaft.app.timetable.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.dto.BulkSlotUpdateRequest;
import com.mannschaft.app.timetable.dto.BulkSlotUpdateResponse;
import com.mannschaft.app.timetable.dto.TimetableSlotResponse;
import com.mannschaft.app.timetable.entity.TimetableSlotEntity;
import com.mannschaft.app.timetable.service.TimetableService;
import com.mannschaft.app.timetable.service.TimetableSlotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/timetables/{timetableId}")
@Tag(name = "時間割スロット管理", description = "F03.9 コマ（スロット）のCRUD")
@RequiredArgsConstructor
public class TimetableSlotController {

    private final TimetableSlotService slotService;
    private final TimetableService timetableService;

    @GetMapping("/slots")
    @Operation(summary = "スロット一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TimetableSlotResponse>>> listSlots(
            @PathVariable Long timetableId,
            @RequestParam(required = false) String day) {
        List<TimetableSlotEntity> slots = day != null
                ? slotService.getSlotsByDay(timetableId, day)
                : slotService.getSlots(timetableId);
        List<TimetableSlotResponse> response = slots.stream().map(this::toResponse).toList();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PutMapping("/slots")
    @Operation(summary = "スロット一括更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<BulkSlotUpdateResponse>> updateSlots(
            @PathVariable Long timetableId,
            @RequestParam(required = false) String day,
            @Valid @RequestBody BulkSlotUpdateRequest request) {
        List<TimetableSlotService.SlotData> slotDataList = request.getSlots().stream()
                .map(r -> new TimetableSlotService.SlotData(
                        r.getDayOfWeek(), r.getPeriodNumber(),
                        r.getWeekPattern() != null ? WeekPattern.valueOf(r.getWeekPattern()) : WeekPattern.EVERY,
                        r.getSubjectName(), r.getTeacherName(), r.getRoomName(),
                        r.getColor(), r.getNotes()))
                .toList();
        List<TimetableSlotEntity> updated = slotService.replaceSlots(timetableId, slotDataList, day);
        var result = new BulkSlotUpdateResponse(updated.size(), updated.size());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping("/slots/today")
    @Operation(summary = "今日のスロット（臨時変更反映済み）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TimetableSlotResponse>>> getTodaySlots(
            @PathVariable Long timetableId) {
        // timetableService経由でEntity取得（レイヤー原則遵守、NotFoundは例外スロー）
        var timetable = timetableService.getByIdWithoutTeam(timetableId);
        List<TimetableSlotService.ResolvedSlot> resolved = slotService.getTodaySlots(timetableId, timetable);
        List<TimetableSlotResponse> response = resolved.stream()
                .map(r -> new TimetableSlotResponse(
                        null, null, r.periodNumber(), null,
                        r.subjectName(), r.teacherName(), r.roomName(),
                        r.color(), r.notes()))
                .toList();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @GetMapping("/subject-suggestions")
    @Operation(summary = "教科名サジェスト（チームの全時間割から過去使用教科名を取得）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<String>>> getSubjectSuggestions(
            @PathVariable Long timetableId) {
        // timetableId → teamId を解決し、チーム単位で教科名を取得する
        var timetable = timetableService.getByIdWithoutTeam(timetableId);
        List<String> suggestions = slotService.getSubjectSuggestions(timetable.getTeamId());
        return ResponseEntity.ok(ApiResponse.of(suggestions));
    }

    private TimetableSlotResponse toResponse(TimetableSlotEntity entity) {
        return new TimetableSlotResponse(
                entity.getId(), entity.getDayOfWeek(), entity.getPeriodNumber(),
                entity.getWeekPattern() != null ? entity.getWeekPattern().name() : null,
                entity.getSubjectName(), entity.getTeacherName(), entity.getRoomName(),
                entity.getColor(), entity.getNotes());
    }
}
