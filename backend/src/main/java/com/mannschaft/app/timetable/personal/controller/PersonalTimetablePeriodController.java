package com.mannschaft.app.timetable.personal.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.timetable.personal.dto.BulkPersonalTimetablePeriodRequest;
import com.mannschaft.app.timetable.personal.dto.PersonalTimetablePeriodRequest;
import com.mannschaft.app.timetable.personal.dto.PersonalTimetablePeriodResponse;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
import com.mannschaft.app.timetable.personal.service.PersonalTimetablePeriodService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F03.15 Phase 2 個人時間割の時限定義 CRUD コントローラ。
 */
@RestController
@RequestMapping("/api/v1/me/personal-timetables/{id}/periods")
@Tag(name = "個人時間割 時限定義", description = "F03.15 個人時間割の時限定義（一覧・一括更新）")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class PersonalTimetablePeriodController {

    private final PersonalTimetablePeriodService service;

    @GetMapping
    @Operation(summary = "時限一覧（自分）")
    public ResponseEntity<ApiResponse<List<PersonalTimetablePeriodResponse>>> list(
            @PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<PersonalTimetablePeriodResponse> data = service.list(id, userId).stream()
                .map(PersonalTimetablePeriodResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(data));
    }

    @PutMapping
    @Operation(summary = "時限一括更新（DRAFT のみ可）")
    public ResponseEntity<ApiResponse<List<PersonalTimetablePeriodResponse>>> replace(
            @PathVariable Long id,
            @Valid @RequestBody BulkPersonalTimetablePeriodRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<PersonalTimetablePeriodService.PeriodData> data = request.periods().stream()
                .map(this::toData)
                .toList();
        List<PersonalTimetablePeriodEntity> saved = service.replaceAll(id, userId, data);
        List<PersonalTimetablePeriodResponse> body = saved.stream()
                .map(PersonalTimetablePeriodResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(body));
    }

    private PersonalTimetablePeriodService.PeriodData toData(PersonalTimetablePeriodRequest r) {
        return new PersonalTimetablePeriodService.PeriodData(
                r.periodNumber(),
                r.label(),
                r.startTime(),
                r.endTime(),
                r.isBreak());
    }
}
