package com.mannschaft.app.corkboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.corkboard.dto.CorkboardDetailResponse;
import com.mannschaft.app.corkboard.dto.CorkboardResponse;
import com.mannschaft.app.corkboard.dto.CreateCorkboardRequest;
import com.mannschaft.app.corkboard.service.CorkboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 組織コルクボードコントローラー。
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/corkboards")
@Tag(name = "組織コルクボード", description = "F09.8 組織コルクボードCRUD")
@RequiredArgsConstructor
public class OrganizationCorkboardController {

    private final CorkboardService corkboardService;

    /**
     * 組織ボード一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "組織コルクボード一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CorkboardResponse>>> listBoards(@PathVariable Long orgId) {
        List<CorkboardResponse> boards = corkboardService.listScopedBoards("ORGANIZATION", orgId);
        return ResponseEntity.ok(ApiResponse.of(boards));
    }

    /**
     * 組織ボードを作成する。
     */
    @PostMapping
    @Operation(summary = "組織コルクボード作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<CorkboardResponse>> createBoard(
            @PathVariable Long orgId, @Valid @RequestBody CreateCorkboardRequest request) {
        CorkboardResponse response = corkboardService.createScopedBoard("ORGANIZATION", orgId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 組織ボード詳細を取得する（カード・セクション含む）。組織所属チェックを実施する。
     */
    @GetMapping("/{boardId}")
    @Operation(summary = "組織コルクボード詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<CorkboardDetailResponse>> getBoard(
            @PathVariable Long orgId, @PathVariable Long boardId) {
        Long userId = SecurityUtils.getCurrentUserId();
        CorkboardDetailResponse response = corkboardService.getOrganizationBoardDetail(orgId, boardId, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
