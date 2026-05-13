package com.mannschaft.app.chat.controller;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.chat.service.ChatBoardMigrationService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * チャット→掲示板移行コントローラー。
 * depth >= 10 の深いスレッドを掲示板スレッドに移行するAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "チャット掲示板移行", description = "F04.2 チャットスレッド→掲示板移行")
@RequiredArgsConstructor
public class ChatBoardMigrationController {

    private final ChatBoardMigrationService migrationService;

    /**
     * チャットスレッドを掲示板スレッドに移行する。
     *
     * @param id      移行するルートメッセージID
     * @param request 移行リクエスト
     * @return 201 Created、移行結果（掲示板スレッドID・URL）
     */
    @PostMapping("/messages/{id}/migrate-to-board")
    @Operation(summary = "チャットスレッドを掲示板に移行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "移行成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "メッセージが見つからない")
    public ResponseEntity<ApiResponse<MigrateToBoardResponse>> migrateToBoard(
            @PathVariable Long id,
            @Valid @RequestBody MigrateToBoardRequest request) {

        Long bulletinThreadId = migrationService.migrateToBoard(
                id,
                request.getBoardScopeId(),
                ScopeType.valueOf(request.getScopeType()),
                request.getCategoryId(),
                request.getTitle(),
                Boolean.TRUE.equals(request.getCopyHistory()),
                SecurityUtils.getCurrentUserId()
        );

        MigrateToBoardResponse response = new MigrateToBoardResponse(
                bulletinThreadId,
                "/boards/" + request.getScopeType() + "/" + request.getBoardScopeId() + "/threads/" + bulletinThreadId
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ========== リクエスト / レスポンス DTO（内部クラス）==========

    /**
     * チャット→掲示板移行リクエスト。
     */
    @Getter
    @RequiredArgsConstructor
    public static class MigrateToBoardRequest {
        /** 移行先掲示板のスコープID（例: チームID、組織ID）。 */
        @NotNull
        private final Long boardScopeId;
        /** スコープ種別（TEAM, ORGANIZATION 等）。 */
        @NotBlank
        private final String scopeType;
        /** 移行先カテゴリID。 */
        @NotNull
        private final Long categoryId;
        /** 掲示板スレッドのタイトル。 */
        @NotBlank
        private final String title;
        /** true の場合、チャット返信履歴を掲示板本文に含める。 */
        private final Boolean copyHistory;
    }

    /**
     * チャット→掲示板移行レスポンス。
     */
    @Getter
    @RequiredArgsConstructor
    public static class MigrateToBoardResponse {
        /** 作成された掲示板スレッドID。 */
        private final Long bulletinThreadId;
        /** 掲示板スレッドの相対URL。 */
        private final String bulletinThreadUrl;
    }
}
