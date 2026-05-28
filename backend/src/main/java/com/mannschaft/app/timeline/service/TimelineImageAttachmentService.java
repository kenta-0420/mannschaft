package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageQuotaExceededException;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.dto.ImageUploadUrlRequest;
import com.mannschaft.app.timeline.dto.ImageUploadUrlResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.UUID;

/**
 * タイムライン画像ファイル用 Presigned URL 発行サービス。
 * R2 に直アップロードするための Presigned PUT URL を生成する。
 *
 * <p>動画の {@link TimelineVideoAttachmentService} と同パターン。
 * 画像ファイルは一時ディレクトリを挟まず
 * {@code timeline/{scopeType}/{scopeId}/img-{uuid}.{ext}} 形式で保存する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineImageAttachmentService {

    private static final Duration UPLOAD_TTL = Duration.ofMinutes(15);
    private static final long UPLOAD_TTL_SECONDS = UPLOAD_TTL.toSeconds();

    private final R2StorageService r2StorageService;
    /** F13 Phase 4-γ: 統合ストレージクォータサービス。 */
    private final StorageQuotaService storageQuotaService;

    /**
     * 画像ファイル用 R2 Presigned PUT URL を発行する。
     * R2 オブジェクトキー形式: timeline/{scope_type}/{scope_id}/img-{uuid}.{ext}
     *
     * <p>URL 発行前にスコープ別クォータを確認する。超過時は 409 を返す。</p>
     *
     * @param request リクエスト（contentType, scopeType, scopeId）
     * @param userId  ログインユーザー ID（PERSONAL スコープのフォールバックおよびログ用）
     * @return Presigned URL とオブジェクトキー
     */
    public ImageUploadUrlResponse generateUploadUrl(ImageUploadUrlRequest request, Long userId) {
        String ext = resolveExtension(request.getContentType());
        String scopeTypeStr = request.getScopeType().toUpperCase();
        long scopeId = request.getScopeId() != null ? request.getScopeId() : 0L;

        // presign 前のクォータチェック（動画と同じ二段チェック方針）
        ScopeResolution scope = resolveScope(scopeTypeStr, scopeId, userId);
        try {
            storageQuotaService.checkQuota(scope.scopeType(), scope.scopeId(), 0L);
        } catch (StorageQuotaExceededException e) {
            log.info("タイムライン画像のクォータ超過（presign 時）: userId={}, scope={}/{}, used={}, included={}",
                    userId, scope.scopeType(), scope.scopeId(), e.getUsedBytes(), e.getIncludedBytes());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ストレージ容量が不足しているためアップロードできません");
        }

        String uuid = UUID.randomUUID().toString();
        String r2Key = String.format("timeline/%s/%d/img-%s.%s", scopeTypeStr, scopeId, uuid, ext);

        PresignedUploadResult result = r2StorageService.generateUploadUrl(r2Key, request.getContentType(), UPLOAD_TTL);
        log.info("画像アップロード Presigned URL 発行: userId={}, key={}", userId, r2Key);
        return new ImageUploadUrlResponse(result.uploadUrl(), result.s3Key(), UPLOAD_TTL_SECONDS);
    }

    /**
     * スコープ文字列と scopeId からストレージスコープを解決する。
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
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/heic" -> "heic";
            default -> throw new IllegalArgumentException("非対応 MIME タイプ: " + contentType);
        };
    }
}
