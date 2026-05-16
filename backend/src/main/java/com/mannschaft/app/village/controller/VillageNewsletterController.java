package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.village.dto.NewsletterSendLogResponse;
import com.mannschaft.app.village.dto.NewsletterSettingResponse;
import com.mannschaft.app.village.dto.NewsletterSettingUpdateRequest;
import com.mannschaft.app.village.dto.NewsletterSettingsResponse;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.service.VillageNewsletterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F17.1 Phase 3-β-E — 村ニュースレター Controller。
 *
 * <p>担当 API:</p>
 * <ul>
 *   <li>{@code GET    /api/v1/villages/{villageId}/newsletter} — 設定取得（誰でも）</li>
 *   <li>{@code PUT    /api/v1/villages/{villageId}/newsletter} — 設定 upsert（HEADMAN / ELDER）</li>
 *   <li>{@code POST   /api/v1/villages/{villageId}/newsletter/opt-out} — opt-out（村人自身）</li>
 *   <li>{@code DELETE /api/v1/villages/{villageId}/newsletter/opt-out} — opt-in 復帰</li>
 *   <li>{@code GET    /api/v1/villages/{villageId}/newsletter/send-logs?frequency=} — 履歴取得</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/villages/{villageId}/newsletter")
@Tag(name = "村ニュースレター (F17.1 Phase 3-β-E)",
     description = "週次/月次ニュースレターの設定・opt-out・配信履歴")
@RequiredArgsConstructor
public class VillageNewsletterController {

    private final VillageNewsletterService newsletterService;

    @GetMapping
    @Operation(summary = "村のニュースレター設定を取得する")
    public ApiResponse<NewsletterSettingsResponse> getSettings(
            @PathVariable("villageId") UUID villageId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(newsletterService.getNewsletterSettings(villageId, actorUserId));
    }

    @PutMapping
    @Operation(summary = "村のニュースレター設定を upsert（HEADMAN / ELDER のみ）")
    public ApiResponse<NewsletterSettingResponse> updateSettings(
            @PathVariable("villageId") UUID villageId,
            @Valid @RequestBody NewsletterSettingUpdateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(newsletterService.updateNewsletterSettings(villageId, request, actorUserId));
    }

    @PostMapping("/opt-out")
    @Operation(summary = "当該ユーザーをニュースレターから opt-out する")
    public ResponseEntity<Void> optOut(@PathVariable("villageId") UUID villageId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        newsletterService.optOut(villageId, actorUserId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/opt-out")
    @Operation(summary = "当該ユーザーの opt-out を解除する（= opt-in に戻す）")
    public ResponseEntity<Void> optIn(@PathVariable("villageId") UUID villageId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        newsletterService.optIn(villageId, actorUserId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/send-logs")
    @Operation(summary = "指定頻度のニュースレター配信履歴を取得する")
    public ApiResponse<List<NewsletterSendLogResponse>> listSendLogs(
            @PathVariable("villageId") UUID villageId,
            @RequestParam("frequency") VillageNewsletterFrequency frequency) {
        return ApiResponse.of(newsletterService.listSendLogs(villageId, frequency));
    }
}
