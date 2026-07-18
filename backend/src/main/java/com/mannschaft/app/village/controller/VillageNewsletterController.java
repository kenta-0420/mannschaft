package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.village.dto.NewsletterCommentUpdateRequest;
import com.mannschaft.app.village.dto.NewsletterIssueDetailResponse;
import com.mannschaft.app.village.dto.NewsletterIssuePageResponse;
import com.mannschaft.app.village.dto.NewsletterIssueTagsUpdateRequest;
import com.mannschaft.app.village.dto.NewsletterSendLogResponse;
import com.mannschaft.app.village.dto.NewsletterSettingResponse;
import com.mannschaft.app.village.dto.NewsletterSettingUpdateRequest;
import com.mannschaft.app.village.dto.NewsletterSettingsResponse;
import com.mannschaft.app.village.dto.NewsletterTagCreateRequest;
import com.mannschaft.app.village.dto.NewsletterTagResponse;
import com.mannschaft.app.village.dto.NewsletterTagUpdateRequest;
import com.mannschaft.app.village.dto.NewsletterVisibilityUpdateRequest;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.service.VillageNewsletterIssueService;
import com.mannschaft.app.village.service.VillageNewsletterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final VillageNewsletterIssueService issueService;

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

    // ========================================================================
    // ②-4: 号（一覧 / 詳細 / コメント / タグ付け / 公開範囲）
    // 閲覧は掲示板と同一の認可（村史に倣う）・編集は HEADMAN / ELDER。認可は Service 内で完結。
    // ========================================================================

    @GetMapping("/issues")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "村ニュースレター号の一覧（新しい順・?tagId= でタグ絞り込み可）")
    public ApiResponse<NewsletterIssuePageResponse> listIssues(
            @PathVariable("villageId") UUID villageId,
            @RequestParam(value = "tagId", required = false) UUID tagId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        // size を [1,100] に丸める（過大要求での大量取得・DoS 防止・②-4 堅牢性 AC-10。他ドメイン慣習に合わせる）。
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return ApiResponse.of(issueService.listIssues(villageId, actorUserId, tagId, pageable));
    }

    @GetMapping("/issues/{issueId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "村ニュースレター号の詳細（凍結ダイジェスト＋コメント＋タグ）")
    public ApiResponse<NewsletterIssueDetailResponse> getIssue(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("issueId") UUID issueId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(issueService.getIssue(villageId, issueId, actorUserId));
    }

    @PutMapping("/issues/{issueId}/comment")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "村ニュースレター号にコメントを保存（HEADMAN / ELDER・楽観ロック・凍結後も可）")
    public ApiResponse<NewsletterIssueDetailResponse> updateIssueComment(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("issueId") UUID issueId,
            @Valid @RequestBody NewsletterCommentUpdateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(issueService.updateComment(
                villageId, issueId, actorUserId, request.comment(), request.version()));
    }

    @PutMapping("/issues/{issueId}/tags")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "村ニュースレター号にタグを付ける（HEADMAN / ELDER・楽観ロック・置き換え式）")
    public ApiResponse<NewsletterIssueDetailResponse> updateIssueTags(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("issueId") UUID issueId,
            @Valid @RequestBody NewsletterIssueTagsUpdateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(issueService.setIssueTags(
                villageId, issueId, actorUserId, request.tagIds(), request.version()));
    }

    @PutMapping("/issues/{issueId}/visibility")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "村ニュースレター号の公開範囲を切替（HEADMAN / ELDER・楽観ロック）")
    public ApiResponse<NewsletterIssueDetailResponse> updateIssueVisibility(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("issueId") UUID issueId,
            @Valid @RequestBody NewsletterVisibilityUpdateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(issueService.changeVisibility(
                villageId, issueId, actorUserId, request.visibility(), request.version()));
    }

    // ========================================================================
    // ②-4: タグ CRUD（HEADMAN / ELDER・削除は使用中ガード）
    // ========================================================================

    @GetMapping("/tags")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "村のニュースレタータグ一覧（表示順）")
    public ApiResponse<List<NewsletterTagResponse>> listTags(
            @PathVariable("villageId") UUID villageId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(issueService.listTags(villageId, actorUserId));
    }

    @PostMapping("/tags")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "村のニュースレタータグを作成（HEADMAN / ELDER）")
    public ResponseEntity<ApiResponse<NewsletterTagResponse>> createTag(
            @PathVariable("villageId") UUID villageId,
            @Valid @RequestBody NewsletterTagCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        NewsletterTagResponse response = issueService.createTag(
                villageId, actorUserId, request.name(), request.color(), request.sortOrder());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PutMapping("/tags/{tagId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "村のニュースレタータグを更新（HEADMAN / ELDER・楽観ロック）")
    public ApiResponse<NewsletterTagResponse> updateTag(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("tagId") UUID tagId,
            @Valid @RequestBody NewsletterTagUpdateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(issueService.updateTag(
                villageId, tagId, actorUserId,
                request.name(), request.color(), request.sortOrder(), request.version()));
    }

    @DeleteMapping("/tags/{tagId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "村のニュースレタータグを削除（HEADMAN / ELDER・使用中は不可）")
    public ResponseEntity<Void> deleteTag(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("tagId") UUID tagId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        issueService.deleteTag(villageId, tagId, actorUserId);
        return ResponseEntity.noContent().build();
    }
}
