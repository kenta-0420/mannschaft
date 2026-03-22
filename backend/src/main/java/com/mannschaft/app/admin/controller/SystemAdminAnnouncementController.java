package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.AnnouncementResponse;
import com.mannschaft.app.admin.dto.CreateAnnouncementRequest;
import com.mannschaft.app.admin.dto.UpdateAnnouncementRequest;
import com.mannschaft.app.admin.service.PlatformAnnouncementService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * システム管理者向けお知らせコントローラー。
 */
@RestController
@RequestMapping("/api/v1/system-admin/announcements")
@Tag(name = "システム管理 - お知らせ", description = "F10.1 プラットフォームお知らせ管理API")
@RequiredArgsConstructor
public class SystemAdminAnnouncementController {

    private final PlatformAnnouncementService announcementService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * お知らせ一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "お知らせ一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<AnnouncementResponse>> getAllAnnouncements(Pageable pageable) {
        Page<AnnouncementResponse> page = announcementService.getAllAnnouncements(pageable);
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(page.getContent(), meta));
    }

    /**
     * お知らせを作成する。
     */
    @PostMapping
    @Operation(summary = "お知らせ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<AnnouncementResponse>> createAnnouncement(
            @Valid @RequestBody CreateAnnouncementRequest request) {
        AnnouncementResponse response = announcementService.createAnnouncement(request, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * お知らせを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "お知らせ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<AnnouncementResponse>> updateAnnouncement(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAnnouncementRequest request) {
        AnnouncementResponse response = announcementService.updateAnnouncement(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * お知らせを公開する。
     */
    @PatchMapping("/{id}/publish")
    @Operation(summary = "お知らせ公開")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "公開成功")
    public ResponseEntity<ApiResponse<AnnouncementResponse>> publishAnnouncement(@PathVariable Long id) {
        AnnouncementResponse response = announcementService.publishAnnouncement(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * お知らせを削除する（論理削除）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "お知らせ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteAnnouncement(@PathVariable Long id) {
        announcementService.deleteAnnouncement(id);
        return ResponseEntity.noContent().build();
    }
}
