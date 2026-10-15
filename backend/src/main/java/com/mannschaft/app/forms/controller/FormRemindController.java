package com.mannschaft.app.forms.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.forms.dto.FormRemindResponse;
import com.mannschaft.app.forms.dto.FormRemindSpecificRequest;
import com.mannschaft.app.forms.service.FormReminderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * フォームリマインダーコントローラ（F05.7 Phase 11 第四陣 4-B）。
 *
 * <p>2 種類のリマインドを提供:</p>
 * <ul>
 *   <li>{@code POST .../remind} — 全未提出者宛て。候補ユーザーリストはリクエスト body で指定する。
 *       設計書ベースの「スコープ内 MEMBER 自動取得」は team/org Service との結合を避けるため、
 *       Phase 11 第四陣 4-B 時点では Controller 経由で渡す形にしている。</li>
 *   <li>{@code POST .../remind-specific} — 特定ユーザー向け。リクエスト body で対象を明示。</li>
 * </ul>
 *
 * <p>双方ともリマインド配信そのものは {@code FormTemplateRemindEvent} を発火するだけ。
 * 実通知は notification ドメインのリスナーで実装する（モジュラーモノリス境界保持）。</p>
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/form-templates/{templateId}")
@Tag(name = "フォームリマインド", description = "F05.7 未提出者・特定者向けリマインド送信")
@RequiredArgsConstructor
public class FormRemindController {

    private final FormReminderService reminderService;

    /**
     * 全未提出者リマインド送信。
     *
     * <p>リクエスト body は候補ユーザー ID リスト（スコープ内 MEMBER 一覧）。
     * Service が既提出者を除外して残りに対しイベント発火する。</p>
     */
    @PostMapping("/remind")
    @Operation(summary = "未提出者リマインド送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "送信成功")
    public ResponseEntity<ApiResponse<FormRemindResponse>> remindAll(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long templateId,
            @RequestBody(required = false) List<Long> candidateUserIds) {
        FormRemindResponse response = reminderService.remindAllUnsubmitted(
                scopeType, scopeId, templateId,
                candidateUserIds == null ? List.of() : candidateUserIds,
                SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 特定者向けリマインド送信。
     */
    @PostMapping("/remind-specific")
    @Operation(summary = "特定者向けリマインド送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "送信成功")
    public ResponseEntity<ApiResponse<FormRemindResponse>> remindSpecific(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long templateId,
            @Valid @RequestBody FormRemindSpecificRequest request) {
        FormRemindResponse response = reminderService.remindSpecificUsers(
                scopeType, scopeId, templateId,
                request.getUserIds(), request.getMessage(),
                SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
