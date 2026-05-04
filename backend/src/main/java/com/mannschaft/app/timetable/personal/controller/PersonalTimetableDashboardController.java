package com.mannschaft.app.timetable.personal.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.timetable.personal.dto.DashboardTimetableTodayResponse;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * F03.15 Phase 3 個人ダッシュボード「今日の時間割」コントローラ。
 */
@RestController
@RequestMapping("/api/v1/me/dashboard")
@Tag(name = "個人時間割-ダッシュボード", description = "F03.15 ダッシュボード集約 API")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class PersonalTimetableDashboardController {

    private final PersonalTimetableDashboardService service;

    @GetMapping("/timetable-today")
    @Operation(summary = "今日の時間割（チーム＋個人マージ）")
    public ResponseEntity<ApiResponse<DashboardTimetableTodayResponse>> today() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                service.getTimetableToday(userId, LocalDate.now())));
    }
}
