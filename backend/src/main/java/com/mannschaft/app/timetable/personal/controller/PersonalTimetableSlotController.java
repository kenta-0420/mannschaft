package com.mannschaft.app.timetable.personal.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.personal.dto.BulkPersonalTimetableSlotRequest;
import com.mannschaft.app.timetable.personal.dto.PersonalTimetableSlotRequest;
import com.mannschaft.app.timetable.personal.dto.PersonalTimetableSlotResponse;
import com.mannschaft.app.timetable.personal.dto.PersonalWeeklyViewResponse;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableSlotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * F03.15 Phase 2 個人時間割のコマ・週間ビュー・今日のコマコントローラ。
 *
 * <p>Phase 2 ではリンク機能（POST/DELETE /slots/{slotId}/link）は実装しない（Phase 4 で追加）。</p>
 */
@RestController
@RequestMapping("/api/v1/me/personal-timetables/{id}")
@Tag(name = "個人時間割 コマ", description = "F03.15 個人時間割のコマ・週間ビュー・今日のコマ")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class PersonalTimetableSlotController {

    private final PersonalTimetableSlotService service;

    @GetMapping("/slots")
    @Operation(summary = "コマ一覧（曜日フィルタ可）")
    public ResponseEntity<ApiResponse<List<PersonalTimetableSlotResponse>>> listSlots(
            @PathVariable Long id,
            @RequestParam(required = false) String day) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<PersonalTimetableSlotResponse> data = service.list(id, userId, day).stream()
                .map(PersonalTimetableSlotResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(data));
    }

    @PutMapping("/slots")
    @Operation(summary = "コマ一括更新（DRAFT のみ可、曜日指定で部分置換）")
    public ResponseEntity<ApiResponse<List<PersonalTimetableSlotResponse>>> replaceSlots(
            @PathVariable Long id,
            @RequestParam(required = false) String day,
            @Valid @RequestBody BulkPersonalTimetableSlotRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<PersonalTimetableSlotService.SlotData> data = request.slots().stream()
                .map(this::toData)
                .toList();
        List<PersonalTimetableSlotEntity> saved = service.replaceAll(id, userId, day, data);
        List<PersonalTimetableSlotResponse> body = saved.stream()
                .map(PersonalTimetableSlotResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(body));
    }

    @GetMapping("/weekly")
    @Operation(summary = "週間ビュー（A/B 週適用済み）")
    public ResponseEntity<ApiResponse<PersonalWeeklyViewResponse>> weekly(
            @PathVariable Long id,
            @RequestParam(name = "week_of", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekOf) {
        Long userId = SecurityUtils.getCurrentUserId();
        LocalDate target = weekOf != null ? weekOf : LocalDate.now();
        PersonalWeeklyViewResponse view = service.getWeeklyView(id, userId, target);
        return ResponseEntity.ok(ApiResponse.of(view));
    }

    @GetMapping("/slots/today")
    @Operation(summary = "今日のコマ（A/B 週適用済み）")
    public ResponseEntity<ApiResponse<List<PersonalTimetableSlotResponse>>> today(
            @PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<PersonalTimetableSlotResponse> data = service.listToday(id, userId).stream()
                .map(PersonalTimetableSlotResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(data));
    }

    private PersonalTimetableSlotService.SlotData toData(PersonalTimetableSlotRequest r) {
        WeekPattern wp = r.weekPattern() != null
                ? WeekPattern.valueOf(r.weekPattern())
                : WeekPattern.EVERY;
        return new PersonalTimetableSlotService.SlotData(
                r.dayOfWeek(),
                r.periodNumber(),
                wp,
                r.subjectName(),
                r.courseCode(),
                r.teacherName(),
                r.roomName(),
                r.credits(),
                r.color(),
                r.linkedTeamId(),
                r.linkedTimetableId(),
                r.linkedSlotId(),
                r.autoSyncChanges(),
                r.notes());
    }
}
