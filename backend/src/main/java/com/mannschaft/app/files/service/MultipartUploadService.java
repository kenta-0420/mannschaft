package com.mannschaft.app.files.service;

import com.mannschaft.app.common.storage.FileTypeValidator;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.R2StorageService.PresignedPartUrl;
import com.mannschaft.app.common.storage.acl.StorageAclContentReference;
import com.mannschaft.app.common.storage.acl.StorageAclService;
import com.mannschaft.app.common.storage.acl.StorageAclScope;
import com.mannschaft.app.common.storage.acl.StorageAclAttachmentBinding;
import com.mannschaft.app.common.storage.acl.MultipartContentTarget;
import com.mannschaft.app.common.storage.acl.MultipartContentTargetRegistry;
import com.mannschaft.app.files.dto.CompleteMultipartRequest;
import com.mannschaft.app.files.dto.CompleteMultipartResponse;
import com.mannschaft.app.files.dto.PartUrlRequest;
import com.mannschaft.app.files.dto.PartUrlResponse;
import com.mannschaft.app.files.dto.PartUrlResponse.PresignedPartUrlDto;
import com.mannschaft.app.files.dto.StartMultipartUploadRequest;
import com.mannschaft.app.files.dto.StartMultipartUploadResponse;
import com.mannschaft.app.files.entity.MultipartUploadSessionEntity;
import com.mannschaft.app.files.repository.MultipartUploadSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.model.CompletedPart;

import java.time.Duration;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Multipart Upload ビジネスロジックサービス。
 * R2 の Multipart Upload API を通じた大容量ファイルアップロードの
 * 開始・パート URL 発行・完了・中断の4オペレーションを実装する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultipartUploadService {

    /** 許可されるターゲットプレフィックス（機能別） */
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "timeline/", "gallery/", "blog/", "files/", "schedules/"
    );

    /**
     * 許可される Content-Type（動画・画像・アーカイブ等）。
     * {@link FileTypeValidator} の定数を合成して使用する。
     */
    private static final Set<String> ALLOWED_CONTENT_TYPES;

    static {
        var merged = new java.util.HashSet<String>();
        merged.addAll(FileTypeValidator.ALLOWED_IMAGE_TYPES);
        merged.addAll(FileTypeValidator.ALLOWED_VIDEO_TYPES);
        merged.addAll(FileTypeValidator.ALLOWED_ARCHIVE_TYPES);
        ALLOWED_CONTENT_TYPES = java.util.Collections.unmodifiableSet(merged);
    }

    /** Multipart Upload の最大ファイルサイズ（5TB） */
    private static final long MAX_FILE_SIZE = 5_497_558_138_880L;

    /** セッション有効期限（開始から 24 時間） */
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    /** パート Presigned URL の有効期限（10 分） */
    private static final Duration PART_URL_TTL = Duration.ofMinutes(10);

    /** パート Presigned URL の有効期限（秒、レスポンス用） */
    private static final int PART_URL_TTL_SECONDS = (int) PART_URL_TTL.toSeconds();

    /** デフォルトのターゲットプレフィックス */
    private static final String DEFAULT_PREFIX = "files/";

    private final R2StorageService r2StorageService;
    private final MultipartUploadSessionRepository sessionRepository;
    private final StorageAclService storageAclService;
    private final MultipartUploadCleanupService cleanupService;
    private final MultipartContentTargetRegistry targetRegistry;
    @org.springframework.beans.factory.annotation.Qualifier("utcClock")
    private final Clock clock;

    /**
     * Multipart Upload を開始する。
     * バリデーション後に R2 で Multipart Upload セッションを作成し、
     * DB にセッション情報を保存する。
     *
     * @param uploaderId アップロードを行うユーザー ID
     * @param req        リクエスト情報
     * @return 開始レスポンス（uploadId・fileKey）
     */
    @Transactional
    public StartMultipartUploadResponse startUpload(Long uploaderId, StartMultipartUploadRequest req) {
        // 公開APIでは機能ルートのみ許可する。テナント・親ID入りのキーは保存台帳経由でのみ発行する。
        String prefix = req.getTargetPrefix() != null ? req.getTargetPrefix() : DEFAULT_PREFIX;
        boolean prefixAllowed = ALLOWED_PREFIXES.contains(prefix);
        if (!prefixAllowed) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "不正なターゲットプレフィックスです: " + prefix + "（許可: " + ALLOWED_PREFIXES + "）");
        }

        // Content-Type のブロックリスト検証（危険な MIME タイプを明示排除）
        if (FileTypeValidator.isBlocked(req.getContentType())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "このファイル種別はセキュリティ上の理由により禁止されています: " + req.getContentType());
        }
        // Content-Type のホワイトリスト検証
        if (!FileTypeValidator.isAllowed(req.getContentType(), ALLOWED_CONTENT_TYPES)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "許可されていない Content-Type です: " + req.getContentType());
        }

        // ファイルサイズ上限チェック（5TB）
        if (req.getFileSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "ファイルサイズが上限（5TB）を超えています: " + req.getFileSize());
        }

        // R2 オブジェクトキー生成: {prefix}{uuid}.{ext}
        String ext = resolveExtension(req.getFileName());
        String uuid = UUID.randomUUID().toString();
        String r2Key = prefix + uuid + (ext.isEmpty() ? "" : "." + ext);

        return startPrepared(uploaderId, req, r2Key, null);
    }

    /** 保存済みドメインメディア専用の開始入口。クライアント申告scopeやprefixは使用しない。 */
    @Transactional
    public StartMultipartUploadResponse startContentUpload(Long uploaderId, StartMultipartUploadRequest req,
                                                           String fileKey) {
        MultipartContentTarget target = targetRegistry.resolve(fileKey, uploaderId);
        if (!FileTypeValidator.isAllowed(req.getContentType(), ALLOWED_CONTENT_TYPES)
                || FileTypeValidator.isBlocked(req.getContentType())
                || req.getFileSize() <= 0 || req.getFileSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不正なmultipartファイルです");
        }
        return startPrepared(uploaderId, req, fileKey, target);
    }

    private StartMultipartUploadResponse startPrepared(Long uploaderId, StartMultipartUploadRequest req,
                                                       String r2Key, MultipartContentTarget contentTarget) {
        String feature = r2Key.substring(0, r2Key.indexOf('/'));

        // R2 で Multipart Upload を開始
        String r2UploadId = r2StorageService.createMultipartUpload(r2Key, req.getContentType());
        MultipartContentTarget target = contentTarget != null ? contentTarget : genericTarget(r2UploadId, uploaderId);
        var compensated = new java.util.concurrent.atomic.AtomicBoolean();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status != STATUS_COMMITTED && compensated.compareAndSet(false, true)) {
                                compensateStart(r2UploadId, r2Key, feature, target, uploaderId, req.getContentType(),
                                        new IllegalStateException("multipart開始トランザクションがrollbackしました"));
                            }
                        }
                    });
        }

        // DB にセッションを保存
        MultipartUploadSessionEntity session = MultipartUploadSessionEntity.builder()
                .uploadId(r2UploadId)
                .r2Key(r2Key)
                .feature(feature)
                .scopeType(target.scope().type().name())
                .scopeId(Long.valueOf(target.scope().scopeKey()))
                .uploaderId(uploaderId)
                .contentType(req.getContentType())
                .status("IN_PROGRESS")
                .expiresAt(LocalDateTime.now(clock).plus(SESSION_TTL))
                .build();
        try {
            sessionRepository.save(session);
            storageAclService.registerPending(
                    r2Key, uploaderId, target.scope(), req.getContentType(), SESSION_TTL, target.parent());
        } catch (RuntimeException registrationFailure) {
            if (compensated.compareAndSet(false, true)) {
                compensateStart(r2UploadId, r2Key, feature, target, uploaderId, req.getContentType(), registrationFailure);
            }
            throw registrationFailure;
        }

        log.info("Multipart Upload 開始: uploaderId={}, r2Key={}, uploadId={}", uploaderId, r2Key, r2UploadId);
        return new StartMultipartUploadResponse(r2UploadId, r2Key, req.getPartCount(), req.getPartSize());
    }

    /** 外側ドメインTxの失敗とcommit失敗も含め、未確定のR2セッションを一度だけ補償する。 */
    private void compensateStart(String uploadId, String fileKey, String feature, MultipartContentTarget target,
                                 Long uploaderId, String contentType, RuntimeException failure) {
        try {
            r2StorageService.abortMultipartUpload(fileKey, uploadId);
        } catch (RuntimeException abortFailure) {
            failure.addSuppressed(abortFailure);
            log.warn("Multipart補償abort失敗: uploadId={}, fileKey={}", uploadId, fileKey, abortFailure);
            try {
                cleanupService.markAbortPending(uploadId, fileKey, feature, target.scope().type().name(),
                        Long.valueOf(target.scope().scopeKey()), uploaderId, contentType);
            } catch (RuntimeException markerFailure) {
                failure.addSuppressed(markerFailure);
                log.error("Multipart補償台帳登録失敗: uploadId={}, fileKey={}", uploadId, fileKey, failure);
            }
        }
    }

    /**
     * パート用 Presigned URL を一括発行する。
     * セッションが IN_PROGRESS であることを確認してから URL を発行する。
     *
     * @param uploadId    R2 Multipart Upload ID
     * @param requesterId リクエスト元ユーザー ID
     * @param req         リクエスト情報
     * @return パート URL レスポンス
     */
    @Transactional(readOnly = true)
    public PartUrlResponse getPartUrls(String uploadId, Long requesterId, PartUrlRequest req) {
        MultipartUploadSessionEntity session = findSessionOrThrow(uploadId);
        validateInProgress(session);
        validateSessionOwner(session, requesterId);
        validateFileKeyMatchesSession(session, req.getFileKey());
        validateNotExpired(session);
        resolveTarget(session);

        List<PresignedPartUrl> presignedUrls = r2StorageService.createPresignedPartUrls(
                session.getR2Key(), uploadId, req.getPartNumbers(), PART_URL_TTL);

        List<PresignedPartUrlDto> dtos = presignedUrls.stream()
                .map(p -> new PresignedPartUrlDto(p.partNumber(), p.uploadUrl()))
                .collect(Collectors.toList());

        log.info("Multipart パート URL 発行: uploadId={}, fileKey={}, parts={}",
                uploadId, session.getR2Key(), req.getPartNumbers().size());
        return new PartUrlResponse(dtos, PART_URL_TTL_SECONDS);
    }

    /**
     * Multipart Upload を完了する。
     * R2 にオブジェクトを組み立て、セッションステータスを COMPLETED に更新する。
     *
     * @param uploadId    R2 Multipart Upload ID
     * @param requesterId リクエスト元ユーザー ID
     * @param req         リクエスト情報（パート番号と ETag のリスト）
     * @return 完了レスポンス（fileKey・fileSize）
     */
    @Transactional
    public CompleteMultipartResponse completeUpload(
            String uploadId, Long requesterId, CompleteMultipartRequest req) {

        MultipartUploadSessionEntity session = findSessionForUpdateOrThrow(uploadId);
        validateInProgress(session);
        validateSessionOwner(session, requesterId);
        validateFileKeyMatchesSession(session, req.getFileKey());
        validateNotExpired(session);
        MultipartContentTarget target = resolveTarget(session);

        var externalObjectCompleted = new java.util.concurrent.atomic.AtomicBoolean();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status != STATUS_COMMITTED && externalObjectCompleted.get()) {
                                try {
                                    cleanupService.compensateCompletedRollback(
                                            session.getUploadId(), session.getR2Key(), session.getFeature(),
                                            session.getScopeType(), session.getScopeId(), session.getUploaderId(),
                                            session.getContentType());
                                } catch (RuntimeException cleanupFailure) {
                                    log.error("Multipart完了rollback後の補償登録に失敗しました: uploadId={}, fileKey={}",
                                            session.getUploadId(), session.getR2Key(), cleanupFailure);
                                }
                            }
                        }
                    });
        }

        // scope/親/添付束縛の不一致は不可逆なR2完了より先に拒否する。
        // R2失敗・DBコミット失敗時はこのclaimも同じTxでrollbackされ、完成済み実体は読取不可のままになる。
        storageAclService.claimPending(session.getR2Key(), requesterId, target.scope(), target.parent(), target.binding());

        // AWS SDK の CompletedPart に変換
        List<CompletedPart> completedParts = req.getParts().stream()
                .map(p -> CompletedPart.builder()
                        .partNumber(p.partNumber())
                        .eTag(p.etag())
                        .build())
                .collect(Collectors.toList());

        // R2 で Multipart Upload を完了
        // 前回R2完了後にDB保存/commitが失敗した場合、消費済みuploadIdを再completeせずHEADから復旧する。
        // キーはサーバー採番しセッションに固定済み。存在確認の通信障害は握りつぶさない。
        if (!r2StorageService.objectExists(session.getR2Key())) {
            try {
                r2StorageService.completeMultipartUpload(session.getR2Key(), uploadId, completedParts);
            } catch (RuntimeException completionFailure) {
                try {
                    externalObjectCompleted.set(r2StorageService.objectExists(session.getR2Key()));
                } catch (RuntimeException headFailure) {
                    completionFailure.addSuppressed(headFailure);
                }
                throw completionFailure;
            }
        }
        externalObjectCompleted.set(true);

        // R2 HeadObject で最終ファイルサイズを取得
        long fileSize = r2StorageService.getObjectSize(session.getR2Key());

        // セッションステータスを COMPLETED に更新
        MultipartUploadSessionEntity updated = session.toBuilder()
                .status("COMPLETED")
                .build();
        sessionRepository.save(updated);

        log.info("Multipart Upload 完了: uploadId={}, fileKey={}, fileSize={}", uploadId, session.getR2Key(), fileSize);
        return new CompleteMultipartResponse(session.getR2Key(), fileSize);
    }

    /**
     * Multipart Upload を中断する。
     * R2 のアップロード済みパートを破棄し、セッションステータスを ABORTED に更新する。
     *
     * @param uploadId    R2 Multipart Upload ID
     * @param requesterId リクエスト元ユーザー ID
     */
    @Transactional
    public void abortUpload(String uploadId, Long requesterId) {
        MultipartUploadSessionEntity session = findSessionForUpdateOrThrow(uploadId);
        validateInProgress(session);
        validateSessionOwner(session, requesterId);

        // R2 で Multipart Upload を中断
        r2StorageService.abortMultipartUpload(session.getR2Key(), uploadId);

        // セッションステータスを ABORTED に更新
        MultipartUploadSessionEntity updated = session.toBuilder()
                .status("ABORTED")
                .build();
        sessionRepository.save(updated);

        log.info("Multipart Upload 中断: uploadId={}", uploadId);
    }

    /**
     * セッションの所有者を検証する。
     * リクエスト元ユーザーがセッションを開始したユーザーと一致しない場合は 403 を返す。
     *
     * @param session     セッションエンティティ
     * @param requesterId リクエスト元ユーザー ID
     */
    private void validateSessionOwner(MultipartUploadSessionEntity session, Long requesterId) {
        if (!session.getUploaderId().equals(requesterId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "このセッションの操作権限がありません");
        }
    }

    /**
     * セッション作成時に採番した R2 キーとリクエストの fileKey が一致することを検証する。
     *
     * @param session Multipart Upload セッション
     * @param fileKey リクエストのファイルキー
     * @throws ResponseStatusException 不一致の場合（400 Bad Request）
     */
    private void validateFileKeyMatchesSession(MultipartUploadSessionEntity session, String fileKey) {
        if (!session.getR2Key().equals(fileKey)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Multipart Upload セッションの fileKey とリクエストが一致しません");
        }
    }

    /**
     * Upload ID でセッションを取得する。存在しない場合は 404 を返す。
     *
     * @param uploadId R2 Multipart Upload ID
     * @return セッションエンティティ
     */
    private MultipartUploadSessionEntity findSessionOrThrow(String uploadId) {
        return sessionRepository.findByUploadId(uploadId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Multipart Upload セッションが見つかりません: " + uploadId));
    }

    private MultipartUploadSessionEntity findSessionForUpdateOrThrow(String uploadId) {
        return sessionRepository.findByUploadIdForUpdate(uploadId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Multipart Upload セッションが見つかりません: " + uploadId));
    }

    /**
     * セッションが IN_PROGRESS であることを検証する。
     * そうでない場合は 409 Conflict を返す。
     *
     * @param session セッションエンティティ
     */
    private void validateInProgress(MultipartUploadSessionEntity session) {
        if (!"IN_PROGRESS".equals(session.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "このセッションは操作不可の状態です: status=" + session.getStatus());
        }
    }

    private void validateNotExpired(MultipartUploadSessionEntity session) {
        if (session.getExpiresAt() == null || !session.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Multipart Upload セッションは期限切れです");
        }
    }

    private MultipartContentTarget resolveTarget(MultipartUploadSessionEntity session) {
        if (session.getR2Key().split("/", -1).length == 2) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "汎用 Multipart Upload セッションは廃止されました");
        }
        MultipartContentTarget target = targetRegistry.resolve(session.getR2Key(), session.getUploaderId());
        if (!target.scope().type().name().equals(session.getScopeType())
                || !target.scope().scopeKey().equals(String.valueOf(session.getScopeId()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Multipart Upload の保存スコープが一致しません");
        }
        return target;
    }

    private MultipartContentTarget genericTarget(String uploadId, Long uploaderId) {
        return new MultipartContentTarget(StorageAclScope.personal(uploaderId),
                new StorageAclContentReference("MULTIPART_UPLOAD", uploadId),
                new StorageAclAttachmentBinding("MULTIPART_UPLOAD", uploadId));
    }

    /**
     * ファイル名から拡張子を取得する。
     * ドット以降を返す。拡張子がない場合は空文字を返す。
     *
     * @param fileName ファイル名
     * @return 拡張子（ドットなし）
     */
    private String resolveExtension(String fileName) {
        if (fileName == null) return "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) return "";
        return fileName.substring(dotIndex + 1).toLowerCase();
    }

    /**
     * ターゲットプレフィックスから機能名を解決する。
     *
     * @param prefix ターゲットプレフィックス（例: "timeline/"）
     * @return 機能名（例: "timeline"）
     */
    private String resolveFeature(String prefix) {
        return prefix.replaceAll("/$", "");
    }
}
