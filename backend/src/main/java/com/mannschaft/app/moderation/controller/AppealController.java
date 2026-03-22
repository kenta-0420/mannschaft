package com.mannschaft.app.moderation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.moderation.dto.AppealResponse;
import com.mannschaft.app.moderation.dto.SubmitAppealRequest;
import com.mannschaft.app.moderation.service.ModerationAppealService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 異議申立てコントローラー（トークン認証）。異議申立て詳細取得・理由送信APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/appeals")
@Tag(name = "異議申立て", description = "F10.2 異議申立て（トークン認証）")
@RequiredArgsConstructor
public class AppealController {

    private final ModerationAppealService appealService;

    /**
     * 異議申立て詳細を取得する（トークン認証）。
     */
    @GetMapping("/{id}")
    @Operation(summary = "異議申立て詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<AppealResponse>> getAppeal(
            @PathVariable Long id,
            @RequestParam String token) {
        AppealResponse response = appealService.getAppeal(id, token);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 異議申立て理由を送信する。
     */
    @PatchMapping("/{id}/submit")
    @Operation(summary = "異議申立て理由送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "送信成功")
    public ResponseEntity<ApiResponse<AppealResponse>> submitAppeal(
            @PathVariable Long id,
            @Valid @RequestBody SubmitAppealRequest request) {
        AppealResponse response = appealService.submitAppeal(id, request.getAppealReason(), request.getToken());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
