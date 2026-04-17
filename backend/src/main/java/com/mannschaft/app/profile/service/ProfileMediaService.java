package com.mannschaft.app.profile.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.profile.ProfileMediaRole;
import com.mannschaft.app.profile.ProfileMediaScope;
import com.mannschaft.app.profile.dto.ProfileMediaCommitRequest;
import com.mannschaft.app.profile.dto.ProfileMediaResponse;
import com.mannschaft.app.profile.dto.ProfileMediaUploadUrlRequest;
import com.mannschaft.app.profile.dto.ProfileMediaUploadUrlResponse;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * プロフィールメディア（アイコン・バナー）管理サービス。
 *
 * <ul>
 *   <li>USER / TEAM / ORGANIZATION のアイコン・バナー画像を Cloudflare R2 で管理する。</li>
 *   <li>アップロード URL 発行（Presigned PUT URL）・コミット（DB 更新 + 旧ファイル削除）・削除を担う。</li>
 *   <li>許可 MIME: image/jpeg, image/png, image/webp, image/gif（BANNER に GIF 不可）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileMediaService {

    // ==================== ファイル制約定数 ====================

    /** 許可する MIME タイプ */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif"
    );

    /** アイコンファイルサイズ上限（5MB） */
    private static final long ICON_MAX_BYTES = 5L * 1024 * 1024;

    /** バナーファイルサイズ上限（10MB） */
    private static final long BANNER_MAX_BYTES = 10L * 1024 * 1024;

    /** Presigned PUT URL の有効期限 */
    private static final Duration UPLOAD_TTL = Duration.ofSeconds(600);

    /** Presigned URL の有効秒数（レスポンス用） */
    private static final int UPLOAD_TTL_SECS = 600;

    /** ダウンロード URL の有効期限 */
    private static final Duration DOWNLOAD_TTL = Duration.ofSeconds(3600);

    /** R2 キープレフィックステンプレート: {scope}/{id}/{role}/ */
    private static final String KEY_TEMPLATE = "%s/%d/%s/";

    private final R2StorageService r2StorageService;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;

    // ==================== アップロード URL 発行 ====================

    /**
     * Presigned PUT URL を発行する。
     * スコープの存在確認・MIME 検証・サイズ検証を行い、R2 キーと URL を返す。
     *
     * @param scope   スコープ（USER / TEAM / ORGANIZATION）
     * @param scopeId スコープ ID
     * @param role    メディアロール（ICON / BANNER）
     * @param req     リクエスト DTO
     * @return アップロード URL レスポンス DTO
     */
    @Transactional(readOnly = true)
    public ProfileMediaUploadUrlResponse generateUploadUrl(
            ProfileMediaScope scope,
            Long scopeId,
            ProfileMediaRole role,
            ProfileMediaUploadUrlRequest req) {

        // スコープ存在確認
        validateScopeExists(scope, scopeId);

        String contentType = req.getContentType();
        long fileSize = req.getFileSize();

        // バナーへの GIF 不可チェック
        if (role == ProfileMediaRole.BANNER && "image/gif".equals(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "GIF はバナーに使用できません");
        }

        // MIME タイプ検証
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "許可されていない MIME タイプです: " + contentType);
        }

        // ファイルサイズ検証
        long maxBytes = (role == ProfileMediaRole.ICON) ? ICON_MAX_BYTES : BANNER_MAX_BYTES;
        if (fileSize > maxBytes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ファイルサイズが上限を超えています（上限: " + maxBytes / (1024 * 1024) + "MB）");
        }

        // R2 キー生成
        String r2Key = String.format(KEY_TEMPLATE,
                scope.name().toLowerCase(),
                scopeId,
                role.name().toLowerCase())
                + UUID.randomUUID() + "." + resolveExtension(contentType);

        // Presigned PUT URL 発行
        PresignedUploadResult result = r2StorageService.generateUploadUrl(r2Key, contentType, UPLOAD_TTL);

        return ProfileMediaUploadUrlResponse.builder()
                .r2Key(r2Key)
                .uploadUrl(result.uploadUrl())
                .expiresIn(UPLOAD_TTL_SECS)
                .build();
    }

    // ==================== コミット ====================

    /**
     * アップロード完了後に DB を更新し、旧ファイルを削除する。
     *
     * @param scope   スコープ（USER / TEAM / ORGANIZATION）
     * @param scopeId スコープ ID
     * @param role    メディアロール（ICON / BANNER）
     * @param req     コミットリクエスト DTO（r2Key を含む）
     * @return プロフィールメディアレスポンス DTO（署名付き表示 URL を含む）
     */
    @Transactional
    public ProfileMediaResponse commit(
            ProfileMediaScope scope,
            Long scopeId,
            ProfileMediaRole role,
            ProfileMediaCommitRequest req) {

        // r2Key プレフィックス検証
        String expectedPrefix = String.format(KEY_TEMPLATE,
                scope.name().toLowerCase(),
                scopeId,
                role.name().toLowerCase());
        if (!req.getR2Key().startsWith(expectedPrefix)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "r2Key が不正です");
        }

        String oldKey = null;
        String newKey = req.getR2Key();

        // スコープ別に DB を更新
        switch (scope) {
            case USER -> {
                UserEntity user = userRepository.findById(scopeId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "ユーザーが見つかりません: id=" + scopeId));
                if (role == ProfileMediaRole.ICON) {
                    oldKey = user.getAvatarUrl();
                    user.updateAvatarUrl(newKey);
                } else {
                    oldKey = user.getBannerUrl();
                    user.updateBannerUrl(newKey);
                }
            }
            case TEAM -> {
                TeamEntity team = teamRepository.findById(scopeId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "チームが見つかりません: id=" + scopeId));
                if (role == ProfileMediaRole.ICON) {
                    oldKey = team.getIconUrl();
                    team.updateIconUrl(newKey);
                } else {
                    oldKey = team.getBannerUrl();
                    team.updateBannerUrl(newKey);
                }
            }
            case ORGANIZATION -> {
                OrganizationEntity org = organizationRepository.findById(scopeId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "組織が見つかりません: id=" + scopeId));
                if (role == ProfileMediaRole.ICON) {
                    oldKey = org.getIconUrl();
                    org.updateIconUrl(newKey);
                } else {
                    oldKey = org.getBannerUrl();
                    org.updateBannerUrl(newKey);
                }
            }
        }

        // 旧キーを削除（失敗してもDB更新は継続）
        if (oldKey != null) {
            try {
                r2StorageService.delete(oldKey);
            } catch (Exception e) {
                log.warn("R2削除に失敗しました（DB更新は続行）: r2Key={}", oldKey, e);
            }
        }

        log.info("プロフィールメディア更新: scope={}, scopeId={}, role={}", scope, scopeId, role);

        return ProfileMediaResponse.builder()
                .mediaRole(role.name().toLowerCase())
                .url(resolveUrl(newKey))
                .build();
    }

    // ==================== 削除 ====================

    /**
     * プロフィールメディアを削除する（冪等）。
     * r2Key が null の場合は何もせずに返る。
     *
     * @param scope         スコープ（USER / TEAM / ORGANIZATION）
     * @param scopeId       スコープ ID
     * @param role          メディアロール（ICON / BANNER）
     * @param requestUserId リクエストユーザー ID（将来の権限チェック用）
     */
    @Transactional
    public void delete(
            ProfileMediaScope scope,
            Long scopeId,
            ProfileMediaRole role,
            Long requestUserId) {

        String currentKey = null;

        // スコープ別に現在の r2Key を取得し、DB をクリア
        switch (scope) {
            case USER -> {
                UserEntity user = userRepository.findById(scopeId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "ユーザーが見つかりません: id=" + scopeId));
                if (role == ProfileMediaRole.ICON) {
                    currentKey = user.getAvatarUrl();
                    if (currentKey == null) return;
                    user.updateAvatarUrl(null);
                } else {
                    currentKey = user.getBannerUrl();
                    if (currentKey == null) return;
                    user.updateBannerUrl(null);
                }
            }
            case TEAM -> {
                TeamEntity team = teamRepository.findById(scopeId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "チームが見つかりません: id=" + scopeId));
                if (role == ProfileMediaRole.ICON) {
                    currentKey = team.getIconUrl();
                    if (currentKey == null) return;
                    team.updateIconUrl(null);
                } else {
                    currentKey = team.getBannerUrl();
                    if (currentKey == null) return;
                    team.updateBannerUrl(null);
                }
            }
            case ORGANIZATION -> {
                OrganizationEntity org = organizationRepository.findById(scopeId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "組織が見つかりません: id=" + scopeId));
                if (role == ProfileMediaRole.ICON) {
                    currentKey = org.getIconUrl();
                    if (currentKey == null) return;
                    org.updateIconUrl(null);
                } else {
                    currentKey = org.getBannerUrl();
                    if (currentKey == null) return;
                    org.updateBannerUrl(null);
                }
            }
        }

        // R2 からファイルを削除（失敗してもDB更新は継続）
        try {
            r2StorageService.delete(currentKey);
        } catch (Exception e) {
            log.warn("R2削除に失敗しました（DB更新は続行）: r2Key={}", currentKey, e);
        }

        log.info("プロフィールメディア削除: scope={}, scopeId={}, role={}", scope, scopeId, role);
    }

    // ==================== プライベートメソッド ====================

    /**
     * スコープの存在を確認する。存在しない場合は 404 をスローする。
     */
    private void validateScopeExists(ProfileMediaScope scope, Long scopeId) {
        switch (scope) {
            case USER -> {
                if (!userRepository.existsById(scopeId)) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "ユーザーが見つかりません: id=" + scopeId);
                }
            }
            case TEAM -> {
                if (!teamRepository.existsById(scopeId)) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "チームが見つかりません: id=" + scopeId);
                }
            }
            case ORGANIZATION -> {
                if (!organizationRepository.existsById(scopeId)) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "組織が見つかりません: id=" + scopeId);
                }
            }
        }
    }

    /**
     * R2 キーから署名付き表示 URL を生成する。
     * r2Key が null の場合は null を返す。
     *
     * @param r2Key R2 オブジェクトキー
     * @return 署名付き表示 URL、または null
     */
    private String resolveUrl(String r2Key) {
        if (r2Key == null) {
            return null;
        }
        return r2StorageService.generateDownloadUrl(r2Key, DOWNLOAD_TTL);
    }

    /**
     * MIME タイプから拡張子を解決する。
     *
     * @param contentType MIME タイプ
     * @return 拡張子文字列
     */
    private String resolveExtension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png"  -> "png";
            case "image/webp" -> "webp";
            case "image/gif"  -> "gif";
            default           -> "bin";
        };
    }
}
