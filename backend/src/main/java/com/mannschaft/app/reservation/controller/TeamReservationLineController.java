package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.reservation.dto.CreateReservationLineRequest;
import com.mannschaft.app.reservation.dto.ReservationLineResponse;
import com.mannschaft.app.reservation.dto.UpdateReservationLineRequest;
import com.mannschaft.app.reservation.service.ReservationLineService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * チーム予約ラインコントローラー。予約メニュー（ライン）のCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/reservation-lines")
@Tag(name = "予約ライン管理", description = "F03.4 チーム予約ラインCRUD")
@RequiredArgsConstructor
public class TeamReservationLineController {

    private final ReservationLineService lineService;

    /**
     * 予約ライン一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "予約ライン一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ReservationLineResponse>>> listLines(
            @PathVariable Long teamId) {
        List<ReservationLineResponse> lines = lineService.listLines(teamId);
        return ResponseEntity.ok(ApiResponse.of(lines));
    }

    /**
     * 予約ラインを作成する。
     */
    @PostMapping
    @Operation(summary = "予約ライン作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ReservationLineResponse>> createLine(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateReservationLineRequest request) {
        ReservationLineResponse response = lineService.createLine(teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 予約ラインを更新する。
     */
    @PatchMapping("/{lineId}")
    @Operation(summary = "予約ライン更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ReservationLineResponse>> updateLine(
            @PathVariable Long teamId,
            @PathVariable Long lineId,
            @Valid @RequestBody UpdateReservationLineRequest request) {
        ReservationLineResponse response = lineService.updateLine(teamId, lineId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 予約ラインを削除する。
     */
    @DeleteMapping("/{lineId}")
    @Operation(summary = "予約ライン削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteLine(
            @PathVariable Long teamId,
            @PathVariable Long lineId) {
        lineService.deleteLine(teamId, lineId);
        return ResponseEntity.noContent().build();
    }
}
