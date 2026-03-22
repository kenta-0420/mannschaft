package com.mannschaft.app.corkboard.controller;

import com.mannschaft.app.common.ApiResponse;
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
 * 個人コルクボードコントローラー。
 */
@RestController
@RequestMapping("/api/v1/users/me/corkboards")
@Tag(name = "個人コルクボード", description = "F09.8 個人コルクボードCRUD")
@RequiredArgsConstructor
public class MyCorkboardController {

    private final CorkboardService corkboardService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 個人ボード一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "個人コルクボード一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CorkboardResponse>>> listBoards() {
        List<CorkboardResponse> boards = corkboardService.listPersonalBoards(getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(boards));
    }

    /**
     * 個人ボードを作成する。
     */
    @PostMapping
    @Operation(summary = "個人コルクボード作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<CorkboardResponse>> createBoard(
            @Valid @RequestBody CreateCorkboardRequest request) {
        CorkboardResponse response = corkboardService.createPersonalBoard(getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 個人ボード詳細を取得する（カード・セクション含む）。
     */
    @GetMapping("/{id}")
    @Operation(summary = "個人コルクボード詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<CorkboardDetailResponse>> getBoard(@PathVariable Long id) {
        CorkboardDetailResponse response = corkboardService.getPersonalBoard(getCurrentUserId(), id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 個人ボードを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "個人コルクボード更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<CorkboardResponse>> updateBoard(
            @PathVariable Long id, @Valid @RequestBody UpdateCorkboardRequest request) {
        CorkboardResponse response = corkboardService.updatePersonalBoard(getCurrentUserId(), id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 個人ボードを削除する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "個人コルクボード削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteBoard(@PathVariable Long id) {
        corkboardService.deletePersonalBoard(getCurrentUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
