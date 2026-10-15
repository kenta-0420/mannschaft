package com.mannschaft.app.match.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.match.dto.MatchAttachmentConfirmRequest;
import com.mannschaft.app.match.dto.MatchAttachmentDownloadResponse;
import com.mannschaft.app.match.dto.MatchAttachmentPresignRequest;
import com.mannschaft.app.match.dto.MatchAttachmentPresignResponse;
import com.mannschaft.app.match.dto.MatchAttachmentResponse;
import com.mannschaft.app.match.entity.MatchAttachmentEntity;
import com.mannschaft.app.match.service.MatchAccessService;
import com.mannschaft.app.match.service.MatchAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F08.10 局面写真など match スコープ添付のコントローラー（盤上競技・presign 方式・01 §B.7 / 03 §C.7a）。
 *
 * <p><b>【Bean 名衝突回避】</b> 単純名 {@code MatchRecordAttachmentController}（tournament 系に同名なし）＋
 * 明示 Bean 名を付与（feedback_spring_bean_name_collision_same_simplename）。</p>
 *
 * <p>テナント文脈はパス {@code /organizations/{orgId}/matches/{matchId}/attachments} で持つ（IDOR 1 段目）。
 * 添付の追加（presign/確定）・削除は記録権限（Service が {@link MatchAccessService#assertCanRecordTimeline}）、
 * 一覧・DL URL 発行は閲覧可視性（{@link MatchAccessService#assertCanView}・F00 委譲）。
 * SVG 除外・サイズ上限・件数上限・IDOR 逆引きは Service が担保する（03 §C.7a）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.7 / 03 §C.7a</p>
 */
@RestController("matchRecordAttachmentController")
@RequestMapping("/api/v1/organizations/{orgId}/matches/{matchId}/attachments")
@Tag(name = "試合局面写真", description = "F08.10 局面写真添付（presign・盤上競技）")
@RequiredArgsConstructor
public class MatchRecordAttachmentController {

    private final MatchAttachmentService attachmentService;
    private final MatchAccessService matchAccessService;

    @PostMapping("/presign")
    @Operation(summary = "局面写真アップロード presign URL 発行（記録権限・SVG 除外・10MB 上限）")
    public ResponseEntity<ApiResponse<MatchAttachmentPresignResponse>> presign(
            @PathVariable Long orgId,
            @PathVariable UUID matchId,
            @Valid @RequestBody MatchAttachmentPresignRequest request) {
        Long actor = SecurityUtils.getCurrentUserId();
        MatchAttachmentService.PresignResult result = attachmentService.generateUploadUrl(
                matchId, orgId, actor, request.getContentType(), request.getFileSize());
        return ResponseEntity.ok(ApiResponse.of(MatchAttachmentPresignResponse.builder()
                .uploadUrl(result.getUploadUrl())
                .fileKey(result.getFileKey())
                .expiresInSeconds(result.getExpiresInSeconds())
                .build()));
    }

    @PostMapping
    @Operation(summary = "局面写真の確定（メタデータ登録・記録権限）")
    public ResponseEntity<ApiResponse<MatchAttachmentResponse>> confirm(
            @PathVariable Long orgId,
            @PathVariable UUID matchId,
            @Valid @RequestBody MatchAttachmentConfirmRequest request) {
        Long actor = SecurityUtils.getCurrentUserId();
        MatchAttachmentService.ConfirmCommand command = MatchAttachmentService.ConfirmCommand.builder()
                .fileKey(request.getFileKey())
                .originalFilename(request.getOriginalFilename())
                .contentType(request.getContentType())
                .fileSize(request.getFileSize())
                .build();
        MatchAttachmentEntity saved = attachmentService.confirmAttachment(matchId, orgId, actor, command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(MatchAttachmentResponse.from(saved)));
    }

    @GetMapping
    @Operation(summary = "局面写真一覧（閲覧可視性・作成日時昇順）")
    public ResponseEntity<ApiResponse<List<MatchAttachmentResponse>>> list(
            @PathVariable Long orgId,
            @PathVariable UUID matchId) {
        Long actor = SecurityUtils.getCurrentUserId();
        matchAccessService.assertCanView(actor, matchId);
        List<MatchAttachmentResponse> list = attachmentService.listAttachments(matchId, orgId).stream()
                .map(MatchAttachmentResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(list));
    }

    @GetMapping("/{attachmentId}/download-url")
    @Operation(summary = "局面写真の短命ダウンロード URL 発行（閲覧可視性・生 key は返さない）")
    public ResponseEntity<ApiResponse<MatchAttachmentDownloadResponse>> downloadUrl(
            @PathVariable Long orgId,
            @PathVariable UUID matchId,
            @PathVariable UUID attachmentId) {
        Long actor = SecurityUtils.getCurrentUserId();
        matchAccessService.assertCanView(actor, matchId);
        MatchAttachmentService.DownloadUrl dl =
                attachmentService.generateDownloadUrl(matchId, attachmentId, orgId);
        return ResponseEntity.ok(ApiResponse.of(MatchAttachmentDownloadResponse.builder()
                .downloadUrl(dl.getDownloadUrl())
                .expiresInSeconds(dl.getExpiresInSeconds())
                .build()));
    }

    @DeleteMapping("/{attachmentId}")
    @Operation(summary = "局面写真の削除（記録権限・R2 ベストエフォート削除）")
    public ResponseEntity<Void> delete(
            @PathVariable Long orgId,
            @PathVariable UUID matchId,
            @PathVariable UUID attachmentId) {
        Long actor = SecurityUtils.getCurrentUserId();
        attachmentService.deleteAttachment(matchId, attachmentId, orgId, actor);
        return ResponseEntity.noContent().build();
    }
}
