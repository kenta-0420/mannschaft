package com.mannschaft.app.notification.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.notification.dto.MentionResponse;
import com.mannschaft.app.notification.service.MentionService;
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
 * メンション一覧コントローラー。
 *
 * <p>各種コンテンツ（タイムライン投稿・チャットメッセージ・掲示板スレッド・コメント）で
 * {@code @contactHandle} 表記により他ユーザーに対するメンションが発生したとき、
 * 対象ユーザー側で参照するためのAPIを提供する。</p>
 */
@RestController
@RequestMapping("/api/v1/mentions")
@Tag(name = "メンション", description = "メンション一覧・既読化")
@RequiredArgsConstructor
public class MentionController {

    private final MentionService mentionService;

    /**
     * メンション一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "メンション一覧",
            description = "ログインユーザー宛のメンション一覧を作成日時降順で取得する。")
    public ResponseEntity<ApiResponse<List<MentionResponse>>> listMentions() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<MentionResponse> response = mentionService.listMentions(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メンションを既読にする。
     */
    @PostMapping("/{mentionId}/read")
    @Operation(summary = "メンション既読化",
            description = "指定IDのメンションを既読化する。自分宛でない場合はエラー。")
    public ResponseEntity<Void> markAsRead(@PathVariable Long mentionId) {
        Long userId = SecurityUtils.getCurrentUserId();
        mentionService.markAsRead(userId, mentionId);
        return ResponseEntity.noContent().build();
    }
}
