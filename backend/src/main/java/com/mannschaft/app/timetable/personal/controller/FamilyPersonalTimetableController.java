package com.mannschaft.app.timetable.personal.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.timetable.personal.dto.FamilyPersonalTimetableResponse;
import com.mannschaft.app.timetable.personal.dto.FamilyWeeklyViewResponse;
import com.mannschaft.app.timetable.personal.service.FamilyPersonalTimetableService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * F03.15 Phase 5 家族からの個人時間割閲覧コントローラ。
 *
 * <p>家族チームのメンバーが、同じチームに所属する別メンバーの個人時間割を閲覧する API。
 * 設計書 §4 / §6.1 / §6.9 に従い、条件不一致は全て 404 統一でレスポンスする。</p>
 */
@RestController
@RequestMapping("/api/v1/families/{teamId}/members/{userId}/personal-timetables")
@Tag(name = "家族個人時間割閲覧",
        description = "F03.15 Phase 5 家族チームメンバーの個人時間割閲覧（メモ・添付・リンク先は除外）")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class FamilyPersonalTimetableController {

    private final FamilyPersonalTimetableService service;

    @GetMapping
    @Operation(summary = "家族メンバーの個人時間割一覧（status=ACTIVE のみ、共有設定済みのみ）")
    public ResponseEntity<ApiResponse<List<FamilyPersonalTimetableResponse>>> list(
            @PathVariable Long teamId,
            @PathVariable Long userId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        List<FamilyPersonalTimetableResponse> data = service.listForFamily(
                        teamId, userId, currentUserId).stream()
                .map(FamilyPersonalTimetableResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(data));
    }

    @GetMapping("/{id}/weekly")
    @Operation(summary = "家族メンバーの個人時間割の週間ビュー（メモ・添付・リンク先は除外）")
    public ResponseEntity<ApiResponse<FamilyWeeklyViewResponse>> weekly(
            @PathVariable Long teamId,
            @PathVariable Long userId,
            @PathVariable Long id,
            @Parameter(description = "週の任意の日付。月曜日を起点に当該週を返す")
            @RequestParam(name = "week_of", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekOf) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        LocalDate target = weekOf != null ? weekOf : LocalDate.now();
        FamilyWeeklyViewResponse data = service.getWeeklyViewForFamily(
                teamId, userId, id, currentUserId, target);
        return ResponseEntity.ok(ApiResponse.of(data));
    }
}
