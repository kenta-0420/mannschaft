package com.mannschaft.app.chat.controller;

import com.mannschaft.app.chat.service.ChatMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * チャット既読コントローラー。既読処理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/chat/channels/{channelId}/read")
@Tag(name = "チャット既読", description = "F04.2 チャット既読管理")
@RequiredArgsConstructor
public class ChatReadController {

    private final ChatMemberService memberService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * チャンネルを既読にする。
     */
    @PostMapping
    @Operation(summary = "既読にする")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "既読成功")
    public ResponseEntity<Void> markAsRead(@PathVariable Long channelId) {
        memberService.markAsRead(channelId, getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
