package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.schedule.dto.BatchDeleteRequest;
import com.mannschaft.app.schedule.dto.BatchDeleteResponse;
import com.mannschaft.app.schedule.dto.CreatePersonalScheduleRequest;
import com.mannschaft.app.schedule.dto.PersonalScheduleResponse;
import com.mannschaft.app.schedule.dto.UpdatePersonalScheduleRequest;
import com.mannschaft.app.schedule.service.PersonalScheduleService;
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

import java.time.LocalDateTime;
import java.util.List;

/**
 * 個人スケジュールコントローラー。個人スコープのスケジュールCRUD・一括削除APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/me/schedules")
@Tag(name = "個人スケジュール管理", description = "F03.2 個人スコープのスケジュール管理")
@RequiredArgsConstructor
public class PersonalScheduleController {

    private final PersonalScheduleService personalScheduleService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 個人スケジュールを作成する。
     */
    @PostMapping
    @Operation(summary = "個人スケジュール作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<PersonalScheduleResponse>> createSchedule(
            @Valid @RequestBody CreatePersonalScheduleRequest request) {
        PersonalScheduleResponse response = personalScheduleService.createPersonalSchedule(
                request, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 個人スケジュール一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "個人スケジュール一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PersonalScheduleResponse>>> listSchedules(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {
        List<PersonalScheduleResponse> schedules = personalScheduleService.listPersonalSchedules(
                getCurrentUserId(), from, to, q, eventType, cursor, size);
        return ResponseEntity.ok(ApiResponse.of(schedules));
    }

    /**
     * 個人スケジュール詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "個人スケジュール詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<PersonalScheduleResponse>> getSchedule(
            @PathVariable Long id) {
        PersonalScheduleResponse response = personalScheduleService.getPersonalSchedule(
                id, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 個人スケジュールを更新する。
     */
    @PatchMapping("/{id}")
    @Operation(summary = "個人スケジュール更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<PersonalScheduleResponse>> updateSchedule(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePersonalScheduleRequest request) {
        PersonalScheduleResponse response = personalScheduleService.updatePersonalSchedule(
                id, request, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 個人スケジュールを削除する（論理削除）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "個人スケジュール削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteSchedule(
            @PathVariable Long id,
            @RequestParam(defaultValue = "THIS_ONLY") String updateScope) {
        personalScheduleService.deletePersonalSchedule(id, updateScope, getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 個人スケジュールを一括削除する。
     */
    @DeleteMapping("/batch")
    @Operation(summary = "個人スケジュール一括削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "一括削除成功")
    public ResponseEntity<ApiResponse<BatchDeleteResponse>> batchDeleteSchedules(
            @Valid @RequestBody BatchDeleteRequest request) {
        BatchDeleteResponse response = personalScheduleService.batchDeletePersonalSchedules(
                request.getIds(), getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
