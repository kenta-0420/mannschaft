package com.mannschaft.app.corkboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.corkboard.dto.BatchPositionRequest;
import com.mannschaft.app.corkboard.dto.CorkboardCardResponse;
import com.mannschaft.app.corkboard.dto.CreateCardRequest;
import com.mannschaft.app.corkboard.dto.UpdateCardRequest;
import com.mannschaft.app.corkboard.service.CorkboardCardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * コルクボードカードコントローラー。カードのCRUD・位置更新・アーカイブAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/corkboards/{boardId}/cards")
@Tag(name = "コルクボードカード", description = "F09.8 コルクボードカードCRUD")
@RequiredArgsConstructor
public class CorkboardCardController {

    private final CorkboardCardService cardService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * カードを追加する。
     */
    @PostMapping
    @Operation(summary = "カード追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<CorkboardCardResponse>> createCard(
            @PathVariable Long boardId, @Valid @RequestBody CreateCardRequest request) {
        CorkboardCardResponse response = cardService.createCard(boardId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * カードを更新する。
     */
    @PutMapping("/{cardId}")
    @Operation(summary = "カード更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<CorkboardCardResponse>> updateCard(
            @PathVariable Long boardId, @PathVariable Long cardId,
            @Valid @RequestBody UpdateCardRequest request) {
        CorkboardCardResponse response = cardService.updateCard(boardId, cardId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * カードを削除する。
     */
    @DeleteMapping("/{cardId}")
    @Operation(summary = "カード削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteCard(@PathVariable Long boardId, @PathVariable Long cardId) {
        cardService.deleteCard(boardId, cardId);
        return ResponseEntity.noContent().build();
    }

    /**
     * カードをアーカイブ/アンアーカイブする。
     */
    @PatchMapping("/{cardId}/archive")
    @Operation(summary = "カードアーカイブ切替")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<CorkboardCardResponse>> archiveCard(
            @PathVariable Long boardId, @PathVariable Long cardId,
            @RequestParam(defaultValue = "true") boolean archived) {
        CorkboardCardResponse response = cardService.archiveCard(boardId, cardId, archived);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * カードの位置を一括更新する。
     */
    @PatchMapping("/batch-position")
    @Operation(summary = "カード一括位置更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<List<CorkboardCardResponse>>> batchUpdatePositions(
            @PathVariable Long boardId, @Valid @RequestBody BatchPositionRequest request) {
        List<CorkboardCardResponse> responses = cardService.batchUpdatePositions(boardId, request);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }
}
