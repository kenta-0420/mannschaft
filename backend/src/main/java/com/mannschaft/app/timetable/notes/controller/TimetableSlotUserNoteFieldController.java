package com.mannschaft.app.timetable.notes.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.timetable.notes.dto.CreateTimetableSlotUserNoteFieldRequest;
import com.mannschaft.app.timetable.notes.dto.TimetableSlotUserNoteFieldResponse;
import com.mannschaft.app.timetable.notes.dto.UpdateTimetableSlotUserNoteFieldRequest;
import com.mannschaft.app.timetable.notes.service.TimetableSlotUserNoteFieldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F03.15 Phase 3 カスタムメモ項目 コントローラ。
 */
@RestController
@RequestMapping("/api/v1/me/timetable-slot-note-fields")
@Tag(name = "個人メモ-カスタム項目", description = "F03.15 ユーザー定義カスタムメモ項目")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class TimetableSlotUserNoteFieldController {

    private final TimetableSlotUserNoteFieldService service;

    @GetMapping
    @Operation(summary = "カスタム項目一覧")
    public ResponseEntity<ApiResponse<List<TimetableSlotUserNoteFieldResponse>>> list() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<TimetableSlotUserNoteFieldResponse> data = service.listMine(userId).stream()
                .map(TimetableSlotUserNoteFieldResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(data));
    }

    @PostMapping
    @Operation(summary = "カスタム項目作成（最大10件）")
    public ResponseEntity<ApiResponse<TimetableSlotUserNoteFieldResponse>> create(
            @Valid @RequestBody CreateTimetableSlotUserNoteFieldRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        var entity = service.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(TimetableSlotUserNoteFieldResponse.from(entity)));
    }

    @PatchMapping("/{fieldId}")
    @Operation(summary = "カスタム項目更新")
    public ResponseEntity<ApiResponse<TimetableSlotUserNoteFieldResponse>> update(
            @PathVariable Long fieldId,
            @Valid @RequestBody UpdateTimetableSlotUserNoteFieldRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        var entity = service.update(fieldId, userId, request);
        return ResponseEntity.ok(ApiResponse.of(TimetableSlotUserNoteFieldResponse.from(entity)));
    }

    @DeleteMapping("/{fieldId}")
    @Operation(summary = "カスタム項目削除")
    public ResponseEntity<Void> delete(@PathVariable Long fieldId) {
        Long userId = SecurityUtils.getCurrentUserId();
        service.delete(fieldId, userId);
        return ResponseEntity.noContent().build();
    }
}
