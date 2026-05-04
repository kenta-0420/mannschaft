package com.mannschaft.app.timetable.personal.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.timetable.personal.dto.PersonalTimetableSettingsResponse;
import com.mannschaft.app.timetable.personal.dto.UpdatePersonalTimetableSettingsRequest;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSettingsEntity;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F03.15 Phase 3 個人時間割ユーザー設定コントローラ。
 */
@RestController
@RequestMapping("/api/v1/me/personal-timetable-settings")
@Tag(name = "個人時間割-設定", description = "F03.15 ユーザー個別の時間割設定")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class PersonalTimetableSettingsController {

    private final PersonalTimetableSettingsService service;

    @GetMapping
    @Operation(summary = "設定取得（未存在時はデフォルトで作成）")
    public ResponseEntity<ApiResponse<PersonalTimetableSettingsResponse>> get() {
        Long userId = SecurityUtils.getCurrentUserId();
        PersonalTimetableSettingsEntity entity = service.getOrCreate(userId);
        return ResponseEntity.ok(ApiResponse.of(
                PersonalTimetableSettingsResponse.from(
                        entity, service.parseVisibleDefaultFields(entity))));
    }

    @PutMapping
    @Operation(summary = "設定更新（UPSERT・部分更新）")
    public ResponseEntity<ApiResponse<PersonalTimetableSettingsResponse>> update(
            @Valid @RequestBody UpdatePersonalTimetableSettingsRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        PersonalTimetableSettingsEntity entity = service.update(userId, request);
        return ResponseEntity.ok(ApiResponse.of(
                PersonalTimetableSettingsResponse.from(
                        entity, service.parseVisibleDefaultFields(entity))));
    }
}
