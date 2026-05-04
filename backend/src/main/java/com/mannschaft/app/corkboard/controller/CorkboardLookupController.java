package com.mannschaft.app.corkboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.corkboard.dto.CorkboardDetailResponse;
import com.mannschaft.app.corkboard.service.CorkboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F09.8 Phase A2: scope-agnostic なボード詳細取得コントローラー。
 *
 * <p>boardId 単独でボード詳細を取得する API を提供する。Service 層で
 * {@code scope_type} を逆引きし、適切な権限チェック（個人=所有者 / チーム・組織=メンバー）
 * を実行する。フロントエンドの一覧画面から「/corkboard/{id}」形式のシンプルな
 * 遷移リンクを生成できるようにするためのもの。</p>
 */
@RestController
@RequestMapping("/api/v1/corkboards")
@Tag(name = "コルクボード詳細取得 (scope-agnostic)", description = "F09.8 Phase A2 boardId 単独でのボード詳細取得")
@RequiredArgsConstructor
public class CorkboardLookupController {

    private final CorkboardService corkboardService;

    /**
     * boardId 単独でボード詳細を取得する（scope-agnostic）。
     *
     * <p>権限チェック失敗時は 403 ({@code CORKBOARD_009})、ボード未存在時は 404
     * ({@code CORKBOARD_001}) を返す。</p>
     *
     * @param boardId ボードID
     * @return ボード詳細レスポンス
     */
    @GetMapping("/{boardId}")
    @Operation(summary = "ボード詳細取得 (scope-agnostic)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<CorkboardDetailResponse>> getBoard(@PathVariable Long boardId) {
        Long userId = SecurityUtils.getCurrentUserId();
        CorkboardDetailResponse response = corkboardService.getBoardDetailByIdOnly(boardId, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
