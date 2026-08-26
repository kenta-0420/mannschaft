package com.mannschaft.app.corkboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.corkboard.dto.CorkboardDetailResponse;
import com.mannschaft.app.corkboard.dto.CorkboardResponse;
import com.mannschaft.app.corkboard.dto.CreateCorkboardRequest;
import com.mannschaft.app.corkboard.dto.UpdateCorkboardRequest;
import com.mannschaft.app.corkboard.service.CorkboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * チームコルクボードコントローラー。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/corkboards")
@Tag(name = "チームコルクボード", description = "F09.8 チームコルクボードCRUD")
@RequiredArgsConstructor
public class TeamCorkboardController {

    private final CorkboardService corkboardService;

    /**
     * チームボード一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "チームコルクボード一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CorkboardResponse>>> listBoards(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<CorkboardResponse> boards = corkboardService.listScopedBoards("TEAM", teamId, userId);
        return ResponseEntity.ok(ApiResponse.of(boards));
    }

    /**
     * チームボードを作成する。
     */
    @PostMapping
    @Operation(summary = "チームコルクボード作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<CorkboardResponse>> createBoard(
            @PathVariable Long teamId, @Valid @RequestBody CreateCorkboardRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        CorkboardResponse response = corkboardService.createScopedBoard("TEAM", teamId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * チームボード詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "チームコルクボード詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<CorkboardDetailResponse>> getBoard(
            @PathVariable Long teamId, @PathVariable Long id) {
        // F09.8 件A: viewerCanEdit 算出のため userId を渡す
        Long userId = SecurityUtils.getCurrentUserId();
        CorkboardDetailResponse response = corkboardService.getScopedBoard("TEAM", teamId, id, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チームボードを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "チームコルクボード更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<CorkboardResponse>> updateBoard(
            @PathVariable Long teamId, @PathVariable Long id,
            @Valid @RequestBody UpdateCorkboardRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        CorkboardResponse response = corkboardService.updateScopedBoard("TEAM", teamId, id, userId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チームボードを削除する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "チームコルクボード削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteBoard(@PathVariable Long teamId, @PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        corkboardService.deleteScopedBoard("TEAM", teamId, id, userId);
        return ResponseEntity.noContent().build();
    }
}
