package com.mannschaft.app.timetable.notes.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.timetable.notes.TimetableSlotKind;
import com.mannschaft.app.timetable.notes.dto.TimetableSlotUserNoteResponse;
import com.mannschaft.app.timetable.notes.dto.UpsertTimetableSlotUserNoteRequest;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteEntity;
import com.mannschaft.app.timetable.notes.service.TimetableSlotUserNoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * F03.15 Phase 3 個人メモ コントローラ。
 */
@RestController
@RequestMapping("/api/v1/me/timetable-slot-notes")
@Tag(name = "個人メモ", description = "F03.15 個人メモ CRUD・ダッシュボード集約")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class TimetableSlotUserNoteController {

    private final TimetableSlotUserNoteService service;

    @GetMapping
    @Operation(summary = "個人メモ取得（指定スロット）")
    public ResponseEntity<ApiResponse<List<TimetableSlotUserNoteResponse>>> list(
            @RequestParam("slot_kind") TimetableSlotKind slotKind,
            @RequestParam("slot_id") Long slotId,
            @RequestParam(value = "target_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate,
            @RequestParam(value = "include_default", required = false, defaultValue = "false")
            boolean includeDefault) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<TimetableSlotUserNoteEntity> notes =
                service.findNotes(userId, slotKind, slotId, targetDate, includeDefault);
        List<TimetableSlotUserNoteResponse> data = notes.stream()
                .map(n -> service.toResponse(n, userId))
                .toList();
        return ResponseEntity.ok(ApiResponse.of(data));
    }

    @PutMapping
    @Operation(summary = "個人メモ アップサート")
    public ResponseEntity<ApiResponse<TimetableSlotUserNoteResponse>> upsert(
            @Valid @RequestBody UpsertTimetableSlotUserNoteRequest request,
            @RequestHeader(value = "If-Unmodified-Since", required = false)
            String ifUnmodifiedSince) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long ifUnmodifiedSinceMillis = parseIfUnmodifiedSince(ifUnmodifiedSince);
        TimetableSlotUserNoteEntity saved = service.upsert(userId, request, ifUnmodifiedSinceMillis);
        return ResponseEntity.ok(ApiResponse.of(service.toResponse(saved, userId)));
    }

    @DeleteMapping("/{noteId}")
    @Operation(summary = "個人メモ削除（論理）")
    public ResponseEntity<Void> delete(@PathVariable Long noteId) {
        Long userId = SecurityUtils.getCurrentUserId();
        service.delete(noteId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/today")
    @Operation(summary = "今日のメモ集約（ダッシュボード用）")
    public ResponseEntity<ApiResponse<List<TimetableSlotUserNoteResponse>>> today() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<TimetableSlotUserNoteResponse> data = service.findForDate(userId, LocalDate.now()).stream()
                .map(n -> service.toResponse(n, userId))
                .toList();
        return ResponseEntity.ok(ApiResponse.of(data));
    }

    @GetMapping("/upcoming")
    @Operation(summary = "今週の準備物集約（期間指定）")
    public ResponseEntity<ApiResponse<List<TimetableSlotUserNoteResponse>>> upcoming(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<TimetableSlotUserNoteResponse> data = service.findUpcoming(userId, from, to).stream()
                .map(n -> service.toResponse(n, userId))
                .toList();
        return ResponseEntity.ok(ApiResponse.of(data));
    }

    /**
     * RFC1123 形式の日時を Epoch ms に変換する。パース不可なら null（楽観排他なし扱い）。
     */
    private Long parseIfUnmodifiedSince(String header) {
        if (header == null || header.isBlank()) return null;
        try {
            return ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
