package com.mannschaft.app.shift.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.shift.dto.CreatePositionRequest;
import com.mannschaft.app.shift.dto.ShiftPositionResponse;
import com.mannschaft.app.shift.dto.UpdatePositionRequest;
import com.mannschaft.app.shift.service.ShiftPositionService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * シフトポジションコントローラー。シフトポジションのCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/shifts/positions")
@Tag(name = "シフトポジション管理", description = "F03.5 シフトポジションのCRUD")
@RequiredArgsConstructor
public class ShiftPositionController {

    private final ShiftPositionService positionService;

    /**
     * チームのポジション一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "ポジション一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ShiftPositionResponse>>> listPositions(
            @RequestParam Long teamId) {
        List<ShiftPositionResponse> responses = positionService.listPositions(teamId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * ポジションを作成する。
     */
    @PostMapping
    @Operation(summary = "ポジション作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ShiftPositionResponse>> createPosition(
            @RequestParam Long teamId,
            @Valid @RequestBody CreatePositionRequest request) {
        ShiftPositionResponse response = positionService.createPosition(teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * ポジションを更新する。
     */
    @PatchMapping("/{positionId}")
    @Operation(summary = "ポジション更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ShiftPositionResponse>> updatePosition(
            @PathVariable Long positionId,
            @Valid @RequestBody UpdatePositionRequest request) {
        ShiftPositionResponse response = positionService.updatePosition(positionId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ポジションを削除する。
     */
    @DeleteMapping("/{positionId}")
    @Operation(summary = "ポジション削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePosition(
            @PathVariable Long positionId) {
        positionService.deletePosition(positionId);
        return ResponseEntity.noContent().build();
    }
}
