package com.mannschaft.app.files.service;

import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.R2StorageService.PresignedPartUrl;
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
            "timeline/", "gallery/", "blog/", "files/"
    );

    /** 許可される Content-Type（動画・アーカイブ等） */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "video/mp4", "video/webm", "video/quicktime",
            "application/zip", "application/x-tar", "application/gzip",
            "application/octet-stream"
    );

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
        // ターゲットプレフィックスの検証
        String prefix = req.getTargetPrefix() != null ? req.getTargetPrefix() : DEFAULT_PREFIX;
        if (!ALLOWED_PREFIXES.contains(prefix)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "不正なターゲットプレフィックスです: " + prefix + "（許可: " + ALLOWED_PREFIXES + "）");
        }

        // Content-Type のホワイトリスト検証
        if (!ALLOWED_CONTENT_TYPES.contains(req.getContentType())) {
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

        // R2 で Multipart Upload を開始
        String r2UploadId = r2StorageService.createMultipartUpload(r2Key, req.getContentType());

        // DB にセッションを保存
        MultipartUploadSessionEntity session = MultipartUploadSessionEntity.builder()
                .uploadId(r2UploadId)
                .r2Key(r2Key)
                .feature(resolveFeature(prefix))
                .scopeType("PERSONAL")
                .scopeId(uploaderId)
                .uploaderId(uploaderId)
                .contentType(req.getContentType())
                .status("IN_PROGRESS")
                .expiresAt(LocalDateTime.now().plus(SESSION_TTL))
                .build();
        sessionRepository.save(session);

        log.info("Multipart Upload 開始: uploaderId={}, r2Key={}, uploadId={}", uploaderId, r2Key, r2UploadId);
        return new StartMultipartUploadResponse(r2UploadId, r2Key, req.getPartCount(), req.getPartSize());
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

        List<PresignedPartUrl> presignedUrls = r2StorageService.createPresignedPartUrls(
                req.getFileKey(), uploadId, req.getPartNumbers(), PART_URL_TTL);

        List<PresignedPartUrlDto> dtos = presignedUrls.stream()
                .map(p -> new PresignedPartUrlDto(p.partNumber(), p.uploadUrl()))
                .collect(Collectors.toList());

        log.info("Multipart パート URL 発行: uploadId={}, parts={}", uploadId, req.getPartNumbers().size());
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

        MultipartUploadSessionEntity session = findSessionOrThrow(uploadId);
        validateInProgress(session);
        validateSessionOwner(session, requesterId);

        // AWS SDK の CompletedPart に変換
        List<CompletedPart> completedParts = req.getParts().stream()
                .map(p -> CompletedPart.builder()
                        .partNumber(p.partNumber())
                        .eTag(p.etag())
                        .build())
                .collect(Collectors.toList());

        // R2 で Multipart Upload を完了
        r2StorageService.completeMultipartUpload(req.getFileKey(), uploadId, completedParts);

        // R2 HeadObject で最終ファイルサイズを取得
        long fileSize = r2StorageService.getObjectSize(req.getFileKey());

        // セッションステータスを COMPLETED に更新
        MultipartUploadSessionEntity updated = session.toBuilder()
                .status("COMPLETED")
                .build();
        sessionRepository.save(updated);

        log.info("Multipart Upload 完了: uploadId={}, fileKey={}, fileSize={}", uploadId, req.getFileKey(), fileSize);
        return new CompleteMultipartResponse(req.getFileKey(), fileSize);
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
        MultipartUploadSessionEntity session = findSessionOrThrow(uploadId);
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
