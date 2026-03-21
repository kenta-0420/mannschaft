package com.mannschaft.app.event.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.event.dto.CreateTimetableItemRequest;
import com.mannschaft.app.event.dto.ReorderTimetableRequest;
import com.mannschaft.app.event.dto.TimetableItemResponse;
import com.mannschaft.app.event.dto.UpdateTimetableItemRequest;
import com.mannschaft.app.event.service.EventTimetableService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * イベントタイムテーブルコントローラー。タイムテーブル項目のCRUD・並び替えAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/timetable")
@Tag(name = "イベントタイムテーブル", description = "F03.8 タイムテーブルCRUD・並び替え")
@RequiredArgsConstructor
public class EventTimetableController {

    private final EventTimetableService timetableService;

    /**
     * タイムテーブル一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "タイムテーブル一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TimetableItemResponse>>> listTimetableItems(
            @PathVariable Long eventId) {
        List<TimetableItemResponse> response = timetableService.listTimetableItems(eventId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * タイムテーブル項目を作成する。
     */
    @PostMapping
    @Operation(summary = "タイムテーブル項目作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TimetableItemResponse>> createTimetableItem(
            @PathVariable Long eventId,
            @Valid @RequestBody CreateTimetableItemRequest request) {
        TimetableItemResponse response = timetableService.createTimetableItem(eventId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * タイムテーブル項目を更新する。
     */
    @PatchMapping("/{itemId}")
    @Operation(summary = "タイムテーブル項目更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TimetableItemResponse>> updateTimetableItem(
            @PathVariable Long eventId,
            @PathVariable Long itemId,
            @Valid @RequestBody UpdateTimetableItemRequest request) {
        TimetableItemResponse response = timetableService.updateTimetableItem(itemId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * タイムテーブル項目を削除する。
     */
    @DeleteMapping("/{itemId}")
    @Operation(summary = "タイムテーブル項目削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteTimetableItem(
            @PathVariable Long eventId,
            @PathVariable Long itemId) {
        timetableService.deleteTimetableItem(itemId);
        return ResponseEntity.noContent().build();
    }

    /**
     * タイムテーブル項目を並び替える。
     */
    @PutMapping("/reorder")
    @Operation(summary = "タイムテーブル並び替え")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "並び替え成功")
    public ResponseEntity<ApiResponse<List<TimetableItemResponse>>> reorderTimetableItems(
            @PathVariable Long eventId,
            @Valid @RequestBody ReorderTimetableRequest request) {
        List<TimetableItemResponse> response = timetableService.reorderTimetableItems(eventId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
