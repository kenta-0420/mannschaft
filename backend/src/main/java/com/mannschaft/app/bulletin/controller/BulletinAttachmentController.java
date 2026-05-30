package com.mannschaft.app.bulletin.controller;

import com.mannschaft.app.bulletin.dto.AttachmentDownloadUrlResponse;
import com.mannschaft.app.bulletin.dto.AttachmentPresignRequest;
import com.mannschaft.app.bulletin.dto.AttachmentPresignResponse;
import com.mannschaft.app.bulletin.dto.AttachmentResponse;
import com.mannschaft.app.bulletin.dto.CreateAttachmentRequest;
import com.mannschaft.app.bulletin.service.BulletinAttachmentService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
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

/**
 * 掲示板（F05.1）添付ファイルコントローラー（方式 A：presigned URL）。
 *
 * <p>F05.2 回覧板の {@code CirculationAttachmentController} を範に、presign 発行 / 確定 /
 * 一覧 / ダウンロード URL / 削除の 5 系統を提供する。既存の {@code bulletin_attachments}
 * テーブルを流用する（新規 DDL なし）。スコープ（ORG/TEAM/VILLAGE/PERSONAL）は対象スレッド/
 * 返信から逆引きして認可する。</p>
 *
 * <p>既存のスレッド作成 multipart 経路（{@code GlobalBulletinThreadController}）は当面残置し、
 * 本コントローラーは追加のみ（既存 API を壊さない）。</p>
 */
@RestController
@RequestMapping("/api/v1/bulletin")
@Tag(name = "掲示板添付ファイル", description = "F05.1 掲示板スレッド/返信の添付ファイル管理")
@RequiredArgsConstructor
public class BulletinAttachmentController {

    private final BulletinAttachmentService bulletinAttachmentService;

    /**
     * 添付ファイルアップロード用 presigned URL を発行する。
     */
    @PostMapping("/attachments/upload-url")
    @Operation(summary = "添付ファイルアップロード用 presigned URL 発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    public ResponseEntity<ApiResponse<AttachmentPresignResponse>> presignUpload(
            @Valid @RequestBody AttachmentPresignRequest request) {
        AttachmentPresignResponse response =
                bulletinAttachmentService.generateUploadUrl(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 添付ファイルを確定（メタデータ登録）する。
     */
    @PostMapping("/attachments")
    @Operation(summary = "添付ファイル確定（メタデータ登録）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "登録成功")
    public ResponseEntity<ApiResponse<AttachmentResponse>> confirmAttachment(
            @Valid @RequestBody CreateAttachmentRequest request) {
        AttachmentResponse response =
                bulletinAttachmentService.confirmAttachment(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * スレッドの添付ファイル一覧を取得する。
     */
    @GetMapping("/threads/{threadId}/attachments")
    @Operation(summary = "スレッド添付ファイル一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<AttachmentResponse>>> listThreadAttachments(
            @PathVariable Long threadId) {
        List<AttachmentResponse> attachments =
                bulletinAttachmentService.listThreadAttachments(threadId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(attachments));
    }

    /**
     * 返信の添付ファイル一覧を取得する。
     */
    @GetMapping("/replies/{replyId}/attachments")
    @Operation(summary = "返信添付ファイル一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<AttachmentResponse>>> listReplyAttachments(
            @PathVariable Long replyId) {
        List<AttachmentResponse> attachments =
                bulletinAttachmentService.listReplyAttachments(replyId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(attachments));
    }

    /**
     * 添付ファイルの短命 presigned ダウンロード URL を発行する（生 fileKey は返さない）。
     */
    @GetMapping("/attachments/{id}/download-url")
    @Operation(summary = "添付ファイルダウンロード用 presigned URL 発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    public ResponseEntity<ApiResponse<AttachmentDownloadUrlResponse>> downloadUrl(
            @PathVariable Long id) {
        AttachmentDownloadUrlResponse response =
                bulletinAttachmentService.generateDownloadUrl(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 添付ファイルを削除する（本人 or モデレーター/ADMIN）。
     */
    @DeleteMapping("/attachments/{id}")
    @Operation(summary = "添付ファイル削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteAttachment(@PathVariable Long id) {
        bulletinAttachmentService.deleteAttachment(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
