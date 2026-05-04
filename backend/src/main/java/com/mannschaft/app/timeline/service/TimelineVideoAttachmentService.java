package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageQuotaExceededException;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.dto.VideoUploadUrlRequest;
import com.mannschaft.app.timeline.dto.VideoUploadUrlResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.UUID;

/**
 * タイムライン動画ファイル用 Presigned URL 発行サービス。
 * R2 に直アップロードするための Presigned PUT URL を生成する。
 *
 * <p><b>F13 Phase 4-γ</b>: Presigned URL 発行前に {@link StorageQuotaService#checkQuota} で
 * クォータを確認する。動画ファイルは {@link com.mannschaft.app.timeline.service.TimelinePostService#createPost}
 * 時（{@code timeline_post_attachments} INSERT 後）に {@code recordUpload} を行う。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineVideoAttachmentService {

    private static final Duration UPLOAD_TTL = Duration.ofMinutes(15);
    private static final long UPLOAD_TTL_SECONDS = UPLOAD_TTL.toSeconds();

    private final R2StorageService r2StorageService;
    /** F13 Phase 4-γ: 統合ストレージクォータサービス。 */
    private final StorageQuotaService storageQuotaService;

    /**
     * 動画ファイル用 R2 Presigned PUT URL を発行する。
     * R2 オブジェクトキー形式: timeline/{scope_type}/{scope_id}/tmp/{uuid}.{ext}
     *
     * <p><b>F13 Phase 4-γ</b>: URL 発行前にスコープ別クォータを確認する。超過時は 409 を返す。</p>
     *
     * @param request リクエスト（contentType, scopeType, scopeId）
     * @param userId  ログインユーザー ID（PERSONAL スコープのフォールバックおよびログ用）
     * @return Presigned URL とオブジェクトキー
     */
    public VideoUploadUrlResponse generateUploadUrl(VideoUploadUrlRequest request, Long userId) {
        String ext = resolveExtension(request.getContentType());
        String scopeTypeStr = request.getScopeType().toUpperCase();
        long scopeId = request.getScopeId() != null ? request.getScopeId() : 0L;

        // F13 Phase 4-γ: presign 前のクォータチェック
        ScopeResolution scope = resolveScope(scopeTypeStr, scopeId, userId);
        try {
            // ファイルサイズは presign 時点では不明なため、最低限チェック（0バイト相当）
            // 実際のサイズチェックは createPost 時に再確認する（設計書 §4 の二段チェック方針）
            storageQuotaService.checkQuota(scope.scopeType(), scope.scopeId(), 0L);
        } catch (StorageQuotaExceededException e) {
            log.info("タイムライン動画のクォータ超過（presign 時）: userId={}, scope={}/{}, used={}, included={}",
                    userId, scope.scopeType(), scope.scopeId(), e.getUsedBytes(), e.getIncludedBytes());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ストレージ容量が不足しているためアップロードできません");
        }

        String uuid = UUID.randomUUID().toString();
        String r2Key = String.format("timeline/%s/%d/tmp/%s.%s", scopeTypeStr, scopeId, uuid, ext);

        PresignedUploadResult result = r2StorageService.generateUploadUrl(r2Key, request.getContentType(), UPLOAD_TTL);
        log.info("動画アップロード Presigned URL 発行: userId={}, key={}", userId, r2Key);
        return new VideoUploadUrlResponse(result.uploadUrl(), result.s3Key(), UPLOAD_TTL_SECONDS);
    }

    /**
     * スコープ文字列（POST リクエストの scopeType）と scopeId からストレージスコープを解決する。
     *
     * <ul>
     *     <li>TEAM → TEAM スコープ</li>
     *     <li>ORGANIZATION → ORGANIZATION スコープ</li>
     *     <li>PUBLIC / PERSONAL / その他 → 投稿者の PERSONAL スコープ</li>
     * </ul>
     */
    ScopeResolution resolveScope(String scopeTypeStr, long scopeId, Long userId) {
        PostScopeType postScope;
        try {
            postScope = PostScopeType.valueOf(scopeTypeStr);
        } catch (IllegalArgumentException e) {
            return new ScopeResolution(StorageScopeType.PERSONAL, userId);
        }
        return switch (postScope) {
            case TEAM -> new ScopeResolution(StorageScopeType.TEAM, scopeId);
            case ORGANIZATION -> new ScopeResolution(StorageScopeType.ORGANIZATION, scopeId);
            default -> new ScopeResolution(StorageScopeType.PERSONAL, userId);
        };
    }

    /** 解決されたストレージスコープ。 */
    public record ScopeResolution(StorageScopeType scopeType, Long scopeId) {}

    /** MIME タイプから拡張子を返す。 */
    private String resolveExtension(String contentType) {
        return switch (contentType) {
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            default -> throw new IllegalArgumentException("非対応 MIME タイプ: " + contentType);
        };
    }
}
