package com.mannschaft.app.mention.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.mention.dto.MentionResponse;
import com.mannschaft.app.mention.service.MentionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * メンションコントローラー。
 *
 * <p>認証ユーザー自身宛のメンション一覧取得・既読化 API を提供する。</p>
 */
@RestController
@RequestMapping("/api/v1/mentions")
@Tag(name = "メンション", description = "メンション一覧・既読化")
@RequiredArgsConstructor
public class MentionController {

    private final MentionService mentionService;

    /**
     * 認証ユーザー宛のメンション一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "メンション一覧取得")
    public ResponseEntity<ApiResponse<List<MentionResponse>>> getMentions() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<MentionResponse> mentions = mentionService.getMentions(userId);
        return ResponseEntity.ok(ApiResponse.of(mentions));
    }

    /**
     * メンションを既読にする。
     */
    @PostMapping("/{id}/read")
    @Operation(summary = "メンション既読化")
    public ResponseEntity<ApiResponse<String>> markAsRead(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        mentionService.markAsRead(id, userId);
        return ResponseEntity.ok(ApiResponse.of("ok"));
    }
}
