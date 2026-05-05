package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaExceededException;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.files.dto.StartMultipartUploadRequest;
import com.mannschaft.app.files.dto.StartMultipartUploadResponse;
import com.mannschaft.app.files.service.MultipartUploadService;
import com.mannschaft.app.schedule.dto.ScheduleMediaListResponse;
import com.mannschaft.app.schedule.dto.ScheduleMediaPatchRequest;
import com.mannschaft.app.schedule.dto.ScheduleMediaResponse;
import com.mannschaft.app.schedule.dto.ScheduleMediaUploadUrlRequest;
import com.mannschaft.app.schedule.dto.ScheduleMediaUploadUrlResponse;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleMediaUploadEntity;
import com.mannschaft.app.schedule.repository.ScheduleMediaUploadRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * スケジュールメディア（写真・動画）アップロード管理サービス。
 *
 * <ul>
 *   <li>IMAGE（100MB 以下） → Presigned PUT URL（単発）を発行する。</li>
 *   <li>VIDEO または 100MB 超 → Multipart Upload を開始し uploadId を返す。</li>
 *   <li>カバー写真切り替え・経費証憑フラグ管理を担う。</li>
 *   <li>孤立メディアの日次クリーンアップも担う。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleMediaService {

    // ==================== 定数 ====================

    /** 許可する画像 MIME タイプ */
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/heic"
    );

    /** 許可する動画 MIME タイプ */
    private static final Set<String> ALLOWED_VIDEO_TYPES = Set.of(
            "video/mp4", "video/webm", "video/quicktime"
    );

    /** IMAGE ファイルサイズ上限（50MB） */
    private static final long IMAGE_MAX_BYTES = 50L * 1024 * 1024;

    /** VIDEO アップロード上限サイズ（1GB） */
    private static final long VIDEO_MAX_BYTES = 1024L * 1024 * 1024;

    /** 1スケジュールあたりの画像上限枚数 */
    private static final int MAX_IMAGE_PER_SCHEDULE = 50;

    /** 1スケジュールあたりの動画上限本数 */
    private static final int MAX_VIDEO_PER_SCHEDULE = 5;

    /** Presigned PUT URL の有効期限 */
    private static final Duration UPLOAD_URL_TTL = Duration.ofSeconds(600);

    /** Presigned URL の有効秒数（レスポンス用） */
    private static final int UPLOAD_URL_TTL_SECONDS = 600;

    /** Multipart 推奨パートサイズ（10MB） */
    private static final long RECOMMENDED_PART_SIZE = 10L * 1024 * 1024;

    /** 画像ファイルに対して Multipart Upload を適用するしきい値（100MB） */
    private static final long MULTIPART_THRESHOLD_BYTES = 100L * 1024 * 1024;

    /**
     * R2 オブジェクトキープレフィックステンプレート。
     *
     * <p><b>F13 Phase 5-a</b>: 新統一パス命名規則 {@code schedules/{scopeType}/{scopeId}/{scheduleId}/}
     * に変更。スコープ種別（TEAM/ORGANIZATION/PERSONAL）とスコープ ID を含める。</p>
     */
    private static final String SCHEDULE_PREFIX_TEMPLATE = "schedules/%s/%d/%d/";

    /** R2 配信 URL プレースホルダーベース */
    private static final String R2_BASE_URL = "https://storage.example.com/";

    /** F13 Phase 4-γ: storage_usage_logs.reference_type に記録するテーブル名。 */
    private static final String REFERENCE_TYPE = "schedule_media_uploads";

    // ==================== 依存 ====================

    private final R2StorageService r2StorageService;
    private final MultipartUploadService multipartUploadService;
    private final ScheduleMediaUploadRepository scheduleMediaUploadRepository;
    private final ScheduleRepository scheduleRepository;
    /** F13 Phase 4-γ: 統合ストレージクォータサービス。 */
    private final StorageQuotaService storageQuotaService;

    // ==================== 公開メソッド ====================

    /**
     * スケジュールに添付するメディアのアップロード URL を発行する。
     * IMAGE（100MB 以下）→ Presigned PUT URL を発行する。
     * VIDEO または 100MB 超 → Multipart Upload を開始し uploadId を返す。
     *
     * <p><b>F13 Phase 4-γ</b>: アップロード URL 発行前に {@link StorageQuotaService#checkQuota} で
     * クォータを確認する。超過時は 409 Conflict を返す。</p>
     *
     * @param scheduleId スケジュール ID
     * @param uploaderId アップロードを行うユーザー ID
     * @param req        リクエスト情報
     * @return アップロード URL 発行レスポンス
     */
    @Transactional
    public ScheduleMediaUploadUrlResponse generateUploadUrl(
            Long scheduleId, Long uploaderId, ScheduleMediaUploadUrlRequest req) {

        // スケジュール存在確認
        ScheduleEntity schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "スケジュールが見つかりません"));

        validateRequest(scheduleId, req);

        // F13 Phase 4-γ: 統合クォータチェック（presign 前）
        ScopeResolution scope = resolveScope(schedule, uploaderId);
        try {
            storageQuotaService.checkQuota(scope.scopeType(), scope.scopeId(), req.getFileSize());
        } catch (StorageQuotaExceededException e) {
            log.info("スケジュールメディアのクォータ超過: scheduleId={}, uploaderId={}, scope={}/{}, requested={}",
                    scheduleId, uploaderId, scope.scopeType(), scope.scopeId(), e.getRequestedBytes());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ストレージ容量が不足しているためアップロードできません");
        }

        // F13 Phase 5-a: スコープ種別・スコープIDを含む新統一パスを生成
        String prefix = String.format(SCHEDULE_PREFIX_TEMPLATE,
                scope.scopeType().name(), scope.scopeId(), scheduleId);

        // VIDEO または 100MB 超 → Multipart Upload
        if ("VIDEO".equals(req.getMediaType()) || req.getFileSize() > MULTIPART_THRESHOLD_BYTES) {
            return handleVideoUpload(scheduleId, uploaderId, req, prefix, scope);
        } else {
            // IMAGE（100MB 以下）→ Presigned PUT URL
            return handleImageUpload(scheduleId, uploaderId, req, prefix, scope);
        }
    }

    /**
     * スケジュールのメディア一覧を取得する。
     *
     * @param scheduleId         スケジュール ID
     * @param mediaType          メディア種別フィルタ（null = フィルタなし）
     * @param expenseReceiptOnly true の場合、経費証憑のみ返す
     * @param page               ページ番号（1始まり）
     * @param size               1ページあたりの件数
     * @return メディア一覧レスポンス
     */
    public ScheduleMediaListResponse listMedia(
            Long scheduleId, String mediaType, boolean expenseReceiptOnly,
            int page, int size) {

        // スケジュール存在確認
        scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "スケジュールが見つかりません"));

        Pageable pageable = PageRequest.of(page - 1, size);

        Page<ScheduleMediaUploadEntity> resultPage;
        if (expenseReceiptOnly) {
            resultPage = scheduleMediaUploadRepository
                    .findByScheduleIdAndIsExpenseReceiptTrueOrderByCreatedAtDesc(scheduleId, pageable);
        } else if (mediaType != null) {
            resultPage = scheduleMediaUploadRepository
                    .findByScheduleIdAndMediaTypeOrderByCreatedAtDesc(
                            scheduleId, mediaType.toUpperCase(), pageable);
        } else {
            resultPage = scheduleMediaUploadRepository
                    .findByScheduleIdOrderByCreatedAtDesc(scheduleId, pageable);
        }

        List<ScheduleMediaResponse> items = resultPage.getContent().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return ScheduleMediaListResponse.builder()
                .items(items)
                .totalCount(resultPage.getTotalElements())
                .page(page)
                .size(size)
                .hasNext(resultPage.hasNext())
                .build();
    }

    /**
     * スケジュールメディアのメタデータを更新する。
     * キャプション・撮影日時・カバー写真フラグ・経費証憑フラグを部分更新する。
     *
     * @param scheduleId      スケジュール ID
     * @param mediaId         メディア ID
     * @param requestUserId   リクエストを行うユーザー ID
     * @param isAdminOrDeputy 管理者または副管理者フラグ
     * @param req             更新リクエスト
     * @return 更新後のメディアレスポンス
     */
    @Transactional
    public ScheduleMediaResponse updateMedia(
            Long scheduleId, Long mediaId, Long requestUserId, boolean isAdminOrDeputy,
            ScheduleMediaPatchRequest req) {

        ScheduleMediaUploadEntity entity = scheduleMediaUploadRepository.findById(mediaId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "メディアが見つかりません"));

        // scheduleId の一致確認
        if (!scheduleId.equals(entity.getScheduleId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "指定されたスケジュールにメディアが見つかりません");
        }

        // is_cover 変更権限チェック（MEMBER は変更不可）
        if (req.getIsCover() != null && !isAdminOrDeputy) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "カバー写真の設定は管理者のみ変更できます");
        }

        // 他人のメディアを操作する権限チェック
        if (!requestUserId.equals(entity.getUploaderId()) && !isAdminOrDeputy) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "他のユーザーがアップロードしたメディアは変更できません");
        }

        // フィールド更新
        if (req.getCaption() != null) {
            entity.updateCaption(req.getCaption());
        }

        if (req.getTakenAt() != null) {
            entity.updateTakenAt(req.getTakenAt());
        }

        if (req.getIsCover() != null && req.getIsCover()) {
            // カバー写真切り替え（@Transactional で保護）
            markAsCover(scheduleId, entity);
        }

        if (req.getIsExpenseReceipt() != null) {
            if (Boolean.FALSE.equals(req.getIsExpenseReceipt()) && !isAdminOrDeputy) {
                // MEMBER は is_expense_receipt を false に変更不可
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "経費証憑フラグの解除は管理者のみ可能です");
            }
            entity.updateIsExpenseReceipt(req.getIsExpenseReceipt());
        }

        ScheduleMediaUploadEntity saved = scheduleMediaUploadRepository.save(entity);
        log.info("メディアメタデータ更新: scheduleId={}, mediaId={}, userId={}",
                scheduleId, mediaId, requestUserId);
        return toResponse(saved);
    }

    /**
     * スケジュールメディアを削除する。
     * R2 からファイルを削除し、DB レコードを物理削除する。
     *
     * <p><b>F13 Phase 4-γ</b>: DB 削除完了後に {@link StorageQuotaService#recordDeletion} で
     * 使用量を減算する。スコープはスケジュールに紐付く teamId / organizationId / userId で判定する。</p>
     *
     * @param scheduleId      スケジュール ID
     * @param mediaId         メディア ID
     * @param requestUserId   リクエストを行うユーザー ID
     * @param isAdminOrDeputy 管理者または副管理者フラグ
     */
    @Transactional
    public void deleteMedia(Long scheduleId, Long mediaId, Long requestUserId, boolean isAdminOrDeputy) {
        ScheduleMediaUploadEntity entity = scheduleMediaUploadRepository.findById(mediaId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "メディアが見つかりません"));

        // scheduleId の一致確認
        if (!scheduleId.equals(entity.getScheduleId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "指定されたスケジュールにメディアが見つかりません");
        }

        // 権限チェック（自分のメディアでない かつ 管理者でない → 403）
        if (!requestUserId.equals(entity.getUploaderId()) && !isAdminOrDeputy) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "このメディアを削除する権限がありません");
        }

        long fileSize = entity.getFileSize() != null ? entity.getFileSize() : 0L;

        // R2 からメインファイルを削除（失敗しても続行）
        try {
            r2StorageService.delete(entity.getR2Key());
        } catch (Exception e) {
            log.warn("R2 メインファイル削除に失敗しました（DB 削除は続行）: mediaId={}, key={}",
                    mediaId, entity.getR2Key(), e);
        }

        // サムネイルがあれば削除（失敗しても続行）
        if (entity.getThumbnailR2Key() != null) {
            try {
                r2StorageService.delete(entity.getThumbnailR2Key());
            } catch (Exception e) {
                log.warn("R2 サムネイル削除に失敗しました（DB 削除は続行）: mediaId={}, thumbnailKey={}",
                        mediaId, entity.getThumbnailR2Key(), e);
            }
        }

        scheduleMediaUploadRepository.delete(entity);
        log.info("メディア削除完了: scheduleId={}, mediaId={}, userId={}",
                scheduleId, mediaId, requestUserId);

        // F13 Phase 4-γ: 使用量減算（スコープはスケジュールで判定）
        if (fileSize > 0) {
            scheduleRepository.findById(scheduleId).ifPresent(schedule -> {
                ScopeResolution scope = resolveScope(schedule, requestUserId);
                storageQuotaService.recordDeletion(
                        scope.scopeType(), scope.scopeId(), fileSize,
                        StorageFeatureType.SCHEDULE_MEDIA,
                        REFERENCE_TYPE, mediaId, requestUserId);
            });
        }
    }

    /**
     * 孤立メディアのクリーンアップ（日次バッチ）。
     * schedule_id IS NULL かつ 72 時間以上経過したレコードを R2 から削除して物理削除する。
     * スケジュール削除時（ON DELETE SET NULL）によって schedule_id が NULL になったレコードも対象となる。
     */
    @Scheduled(cron = "0 30 2 * * *")
    @Transactional
    public void cleanupOrphanMedia() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(72);
        List<ScheduleMediaUploadEntity> orphans = scheduleMediaUploadRepository.findOrphanMedia(cutoff);

        for (ScheduleMediaUploadEntity orphan : orphans) {
            try {
                r2StorageService.delete(orphan.getR2Key());
                if (orphan.getThumbnailR2Key() != null) {
                    r2StorageService.delete(orphan.getThumbnailR2Key());
                }
            } catch (Exception e) {
                // R2 削除失敗は警告ログのみ（DB 削除は続行する）
                log.warn("孤立メディアの R2 削除に失敗しました（DB 削除は続行）: mediaId={}, key={}",
                        orphan.getId(), orphan.getR2Key(), e);
            }
        }

        scheduleMediaUploadRepository.deleteAll(orphans);
        log.info("孤立メディアのクリーンアップ完了: 削除件数={}", orphans.size());
    }

    // ==================== プライベートメソッド ====================

    /**
     * リクエストのバリデーションを行う。
     * MIME タイプ・サイズ・スケジュールあたり枚数／本数を検証する。
     *
     * @param scheduleId スケジュール ID
     * @param req        リクエスト
     */
    private void validateRequest(Long scheduleId, ScheduleMediaUploadUrlRequest req) {
        if ("IMAGE".equals(req.getMediaType())) {
            if (!ALLOWED_IMAGE_TYPES.contains(req.getContentType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "サポートされていない画像形式です: " + req.getContentType());
            }
            if (req.getFileSize() > IMAGE_MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "画像サイズが上限（50MB）を超えています");
            }
            // スケジュールあたりの画像枚数上限チェック
            int count = scheduleMediaUploadRepository.countByScheduleIdAndMediaType(scheduleId, "IMAGE");
            if (count >= MAX_IMAGE_PER_SCHEDULE) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "1スケジュールあたりの画像上限（" + MAX_IMAGE_PER_SCHEDULE + "枚）を超えています");
            }
        } else if ("VIDEO".equals(req.getMediaType())) {
            if (!ALLOWED_VIDEO_TYPES.contains(req.getContentType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "サポートされていない動画形式です: " + req.getContentType());
            }
            if (req.getFileSize() > VIDEO_MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "動画サイズが上限（1GB）を超えています。大容量動画はファイル共有機能をご利用ください");
            }
            // スケジュールあたりの動画本数上限チェック
            int count = scheduleMediaUploadRepository.countByScheduleIdAndMediaType(scheduleId, "VIDEO");
            if (count >= MAX_VIDEO_PER_SCHEDULE) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "1スケジュールあたりの動画上限（" + MAX_VIDEO_PER_SCHEDULE + "本）を超えています");
            }
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "mediaType は IMAGE または VIDEO を指定してください");
        }
    }

    /**
     * 画像アップロード URL 発行処理（100MB 以下）。
     * R2 の Presigned PUT URL を発行し、schedule_media_uploads に INSERT する。
     *
     * <p><b>F13 Phase 4-γ</b>: INSERT 完了直後に {@link StorageQuotaService#recordUpload} で
     * 使用量を加算する。IMAGE は Presigned URL 発行 + DB INSERT が同時に行われるため、
     * presign 時に recordUpload する（confirm ステップはない）。</p>
     *
     * @param scheduleId スケジュール ID
     * @param uploaderId アップロードを行うユーザー ID
     * @param req        リクエスト情報
     * @param prefix     R2 オブジェクトキープレフィックス
     * @param scope      解決済みストレージスコープ
     * @return レスポンス（uploadUrl, expiresIn を設定。uploadId は null）
     */
    private ScheduleMediaUploadUrlResponse handleImageUpload(
            Long scheduleId, Long uploaderId, ScheduleMediaUploadUrlRequest req, String prefix,
            ScopeResolution scope) {

        // R2 オブジェクトキー生成: {prefix}{uuid}.{ext}
        String ext = resolveImageExtension(req.getContentType());
        String uuid = UUID.randomUUID().toString();
        String r2Key = prefix + uuid + "." + ext;

        // R2StorageService.generateUploadUrl() で Presigned PUT URL 発行
        PresignedUploadResult result = r2StorageService.generateUploadUrl(r2Key, req.getContentType(), UPLOAD_URL_TTL);

        // schedule_media_uploads に INSERT
        ScheduleMediaUploadEntity entity = ScheduleMediaUploadEntity.builder()
                .scheduleId(scheduleId)
                .uploaderId(uploaderId)
                .mediaType("IMAGE")
                .r2Key(r2Key)
                .fileName(req.getFileName())
                .fileSize(req.getFileSize())
                .contentType(req.getContentType())
                .processingStatus("READY")
                .build();
        ScheduleMediaUploadEntity saved = scheduleMediaUploadRepository.save(entity);

        log.info("画像アップロード Presigned URL 発行: uploaderId={}, scheduleId={}, mediaId={}, key={}",
                uploaderId, scheduleId, saved.getId(), r2Key);

        // F13 Phase 4-γ: 使用量加算（IMAGE は presign 発行＋INSERT 完了を確定とみなす）
        storageQuotaService.recordUpload(
                scope.scopeType(), scope.scopeId(), req.getFileSize(),
                StorageFeatureType.SCHEDULE_MEDIA,
                REFERENCE_TYPE, saved.getId(), uploaderId);

        return ScheduleMediaUploadUrlResponse.builder()
                .mediaId(saved.getId())
                .mediaType("IMAGE")
                .r2Key(r2Key)
                .uploadUrl(result.uploadUrl())
                .expiresIn(UPLOAD_URL_TTL_SECONDS)
                .build();
    }

    /**
     * 動画アップロード（Multipart Upload 開始）処理。
     * VIDEO または 100MB 超の IMAGE に対して Multipart Upload を開始し、
     * schedule_media_uploads に INSERT する。
     * 100MB 超の IMAGE でも mediaType は IMAGE を維持する。
     *
     * <p><b>F13 Phase 4-γ</b>: INSERT 完了直後に {@link StorageQuotaService#recordUpload} で
     * 使用量を加算する。Multipart Upload は Multipart Complete（クライアント側）で確定するが、
     * checkQuota は presign 時（本メソッド呼び出し前）に実施済みのため、ここでは recordUpload のみ行う。</p>
     *
     * @param scheduleId スケジュール ID
     * @param uploaderId アップロードを行うユーザー ID
     * @param req        リクエスト情報
     * @param prefix     R2 オブジェクトキープレフィックス（"schedules/{scheduleId}/"）
     * @param scope      解決済みストレージスコープ
     * @return レスポンス（uploadId, partSize を設定。uploadUrl は null）
     */
    private ScheduleMediaUploadUrlResponse handleVideoUpload(
            Long scheduleId, Long uploaderId, ScheduleMediaUploadUrlRequest req, String prefix,
            ScopeResolution scope) {

        // ファイル名から拡張子を決定
        String ext = "VIDEO".equals(req.getMediaType())
                ? resolveVideoExtension(req.getContentType())
                : resolveImageExtension(req.getContentType());
        String fileName = UUID.randomUUID() + "." + ext;

        // Multipart Upload セッションの prefix は "schedules/{scheduleId}/" を指定
        // ScheduleMedia 専用のプレフィックスとして渡す
        String multipartPrefix = prefix; // "schedules/{scheduleId}/"

        StartMultipartUploadRequest startReq = new StartMultipartUploadRequest(
                null,                    // folderId（schedule 経由なので不要）
                fileName,                // fileName
                req.getContentType(),    // contentType
                req.getFileSize(),       // fileSize
                1,                       // partCount（仮値。クライアントが実際のパート数を決定する）
                RECOMMENDED_PART_SIZE,   // partSize
                multipartPrefix          // targetPrefix（"schedules/{scheduleId}/"）
        );

        StartMultipartUploadResponse startResponse = multipartUploadService.startUpload(uploaderId, startReq);

        // 実際のメディア種別を保持する（100MB 超の IMAGE でも mediaType は "IMAGE" のまま）
        String actualMediaType = req.getMediaType();
        String processingStatus = "VIDEO".equals(actualMediaType) ? "PENDING" : "READY";

        // schedule_media_uploads に INSERT
        ScheduleMediaUploadEntity entity = ScheduleMediaUploadEntity.builder()
                .scheduleId(scheduleId)
                .uploaderId(uploaderId)
                .mediaType(actualMediaType)
                .r2Key(startResponse.getFileKey())
                .fileName(req.getFileName())
                .fileSize(req.getFileSize())
                .contentType(req.getContentType())
                .processingStatus(processingStatus)
                .build();
        ScheduleMediaUploadEntity saved = scheduleMediaUploadRepository.save(entity);

        log.info("Multipart Upload 開始: uploaderId={}, scheduleId={}, mediaId={}, uploadId={}, key={}",
                uploaderId, scheduleId, saved.getId(), startResponse.getUploadId(), startResponse.getFileKey());

        // F13 Phase 4-γ: 使用量加算（Multipart 開始＋DB INSERT 完了を確定とみなす）
        storageQuotaService.recordUpload(
                scope.scopeType(), scope.scopeId(), req.getFileSize(),
                StorageFeatureType.SCHEDULE_MEDIA,
                REFERENCE_TYPE, saved.getId(), uploaderId);

        return ScheduleMediaUploadUrlResponse.builder()
                .mediaId(saved.getId())
                .mediaType(actualMediaType)
                .r2Key(startResponse.getFileKey())
                .uploadId(startResponse.getUploadId())
                .partSize(RECOMMENDED_PART_SIZE)
                .build();
    }

    /**
     * カバー写真を切り替える。
     * 同一スケジュールの既存カバー写真（is_cover = TRUE）を FALSE にしてから、
     * 指定エンティティを TRUE に設定する。
     * 呼び出し元の @Transactional で保護されること。
     *
     * @param scheduleId スケジュール ID
     * @param entity     カバー写真に設定するエンティティ
     */
    private void markAsCover(Long scheduleId, ScheduleMediaUploadEntity entity) {
        // 既存のカバー写真を全て FALSE に
        List<ScheduleMediaUploadEntity> currentCovers =
                scheduleMediaUploadRepository.findByScheduleIdAndIsCoverTrue(scheduleId);
        for (ScheduleMediaUploadEntity cover : currentCovers) {
            if (!cover.getId().equals(entity.getId())) {
                cover.updateIsCover(false);
                scheduleMediaUploadRepository.save(cover);
            }
        }
        // 対象を TRUE に
        entity.updateIsCover(true);
        log.info("カバー写真切り替え: scheduleId={}, mediaId={}", scheduleId, entity.getId());
    }

    /**
     * 画像 MIME タイプから拡張子を返す。
     *
     * @param contentType MIME タイプ
     * @return 拡張子（ドットなし）
     */
    private String resolveImageExtension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/heic" -> "heic";
            default -> "bin";
        };
    }

    /**
     * 動画 MIME タイプから拡張子を返す。
     *
     * @param contentType MIME タイプ
     * @return 拡張子（ドットなし）
     */
    private String resolveVideoExtension(String contentType) {
        return switch (contentType) {
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            default -> "bin";
        };
    }

    /**
     * スケジュールのスコープを解決する。
     *
     * <ul>
     *     <li>teamId が設定されている場合 → TEAM スコープ</li>
     *     <li>organizationId が設定されている場合 → ORGANIZATION スコープ</li>
     *     <li>それ以外（個人スケジュール） → PERSONAL スコープ（uploaderId を使用）</li>
     * </ul>
     *
     * @param schedule   スケジュールエンティティ
     * @param uploaderId アップロードを行うユーザー ID（PERSONAL フォールバック用）
     * @return 解決済みスコープ
     */
    public ScopeResolution resolveScope(ScheduleEntity schedule, Long uploaderId) {
        if (schedule.getTeamId() != null) {
            return new ScopeResolution(StorageScopeType.TEAM, schedule.getTeamId());
        }
        if (schedule.getOrganizationId() != null) {
            return new ScopeResolution(StorageScopeType.ORGANIZATION, schedule.getOrganizationId());
        }
        // 個人スケジュール
        return new ScopeResolution(StorageScopeType.PERSONAL, uploaderId);
    }

    /** 解決されたストレージスコープ。 */
    public record ScopeResolution(StorageScopeType scopeType, Long scopeId) {}

    /**
     * エンティティをレスポンス DTO に変換する。
     *
     * @param entity エンティティ
     * @return レスポンス DTO
     */
    private ScheduleMediaResponse toResponse(ScheduleMediaUploadEntity entity) {
        String url = R2_BASE_URL + entity.getR2Key();
        String thumbnailUrl = entity.getThumbnailR2Key() != null
                ? R2_BASE_URL + entity.getThumbnailR2Key()
                : null;

        return ScheduleMediaResponse.builder()
                .id(entity.getId())
                .mediaType(entity.getMediaType())
                .url(url)
                .thumbnailUrl(thumbnailUrl)
                .fileName(entity.getFileName())
                .fileSize(entity.getFileSize())
                .caption(entity.getCaption())
                .takenAt(entity.getTakenAt())
                .isCover(entity.getIsCover())
                .isExpenseReceipt(entity.getIsExpenseReceipt())
                .processingStatus(entity.getProcessingStatus())
                .uploaderId(entity.getUploaderId())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
