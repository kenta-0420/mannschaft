package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.schedule.dto.ScheduleMediaListResponse;
import com.mannschaft.app.schedule.dto.ScheduleMediaPatchRequest;
import com.mannschaft.app.schedule.dto.ScheduleMediaResponse;
import com.mannschaft.app.schedule.dto.ScheduleMediaUploadUrlRequest;
import com.mannschaft.app.schedule.dto.ScheduleMediaUploadUrlResponse;
import com.mannschaft.app.schedule.service.ScheduleMediaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * スケジュールメディアコントローラー。
 * F03.12 カレンダー予定メディア管理（写真・動画添付）のAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/schedules/{scheduleId}/media")
@Tag(name = "スケジュールメディア", description = "F03.12 カレンダー予定の写真・動画添付管理")
@RequiredArgsConstructor
public class ScheduleMediaController {

    private static final String SCOPE_TYPE_TEAM = "TEAM";

    private final ScheduleMediaService scheduleMediaService;
    private final AccessControlService accessControlService;

    /**
     * スケジュールメディアのアップロード URL を発行する。
     * IMAGE の場合は Presigned PUT URL を返す。
     * VIDEO の場合は Multipart Upload の uploadId を返す。
     *
     * @param scheduleId スケジュールID
     * @param request    リクエスト情報（mediaType, contentType, fileSize, fileName）
     * @return アップロード URL 発行レスポンス
     */
    @PostMapping("/upload-url")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "スケジュールメディアアップロード URL 発行",
            description = "IMAGE → Presigned PUT URL 発行。VIDEO → Multipart Upload 開始。"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "URL 発行成功")
    public ResponseEntity<ApiResponse<ScheduleMediaUploadUrlResponse>> generateUploadUrl(
            @PathVariable Long scheduleId,
            @RequestBody @Valid ScheduleMediaUploadUrlRequest request) {

        Long currentUserId = SecurityUtils.getCurrentUserId();
        ScheduleMediaUploadUrlResponse response =
                scheduleMediaService.generateUploadUrl(scheduleId, currentUserId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スケジュールに添付されたメディア一覧を取得する。
     *
     * @param scheduleId          スケジュールID
     * @param mediaType           絞り込み（"IMAGE" または "VIDEO"）。null の場合は全件
     * @param expenseReceiptOnly  経費領収書のみに絞り込むかどうか
     * @param page                ページ番号（1始まり）
     * @param size                ページサイズ
     * @return メディア一覧レスポンス
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "スケジュールメディア一覧取得",
            description = "スケジュールに添付された画像・動画の一覧を取得する。"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ScheduleMediaListResponse>> listMedia(
            @PathVariable Long scheduleId,
            @RequestParam(required = false) String mediaType,
            @RequestParam(defaultValue = "false") boolean expenseReceiptOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        ScheduleMediaListResponse response =
                scheduleMediaService.listMedia(scheduleId, mediaType, expenseReceiptOnly, page, size);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スケジュールメディアのメタ情報を更新する（キャプション・撮影日時・カバーフラグ等）。
     * isCover の変更は ADMIN/DEPUTY_ADMIN のみ可能。
     *
     * @param scheduleId スケジュールID
     * @param mediaId    メディアID
     * @param request    更新内容
     * @return 更新後のメディア情報
     */
    @PatchMapping("/{mediaId}")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "スケジュールメディア更新",
            description = "メディアのキャプション・撮影日時・カバーフラグ・経費領収書フラグを更新する。"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ScheduleMediaResponse>> updateMedia(
            @PathVariable Long scheduleId,
            @PathVariable Long mediaId,
            @RequestBody @Valid ScheduleMediaPatchRequest request) {

        Long currentUserId = SecurityUtils.getCurrentUserId();
        // TODO(F03.12): スケジュールの所属チームIDを解決して scopeId を渡す必要がある。
        //   現時点では ScheduleMediaService 内で isAdminOrDeputy 判定ロジックを持たせる。
        //   スケジュール→チームID解決が実装されたら以下のように変更:
        //   boolean isAdminOrDeputy = accessControlService.isAdminOrAbove(currentUserId, teamId, SCOPE_TYPE_TEAM);
        ScheduleMediaResponse response =
                scheduleMediaService.updateMedia(scheduleId, mediaId, currentUserId, false, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スケジュールメディアを削除する。
     * アップロードした本人、または ADMIN/DEPUTY_ADMIN のみ削除可能。
     *
     * @param scheduleId スケジュールID
     * @param mediaId    メディアID
     * @return 204 No Content
     */
    @DeleteMapping("/{mediaId}")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "スケジュールメディア削除",
            description = "アップロードした本人または管理者がメディアを削除する。"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteMedia(
            @PathVariable Long scheduleId,
            @PathVariable Long mediaId) {

        Long currentUserId = SecurityUtils.getCurrentUserId();
        // TODO(F03.12): スケジュールの所属チームIDを解決して scopeId を渡す必要がある。
        //   スケジュール→チームID解決が実装されたら以下のように変更:
        //   boolean isAdminOrDeputy = accessControlService.isAdminOrAbove(currentUserId, teamId, SCOPE_TYPE_TEAM);
        scheduleMediaService.deleteMedia(scheduleId, mediaId, currentUserId, false);
        return ResponseEntity.noContent().build();
    }
}
