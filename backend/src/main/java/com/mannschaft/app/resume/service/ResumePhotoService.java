package com.mannschaft.app.resume.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.resume.ResumeErrorCode;
import com.mannschaft.app.resume.entity.ResumeEntity;
import com.mannschaft.app.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * 証明写真アップロード・削除・presigned URL 発行サービス（F01.10）。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §5.10 / §5.11
 *
 * <p>マジックバイト検証 + 再エンコード（EXIF/GPS 除去・寸法上限 2000px）を行い、
 * R2 に保存する。JPEG / PNG のフォーマットを維持し WebP 変換はしない。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumePhotoService {

    /** 証明写真の最大ファイルサイズ（5MB）。 */
    private static final long MAX_PHOTO_SIZE_BYTES = 5L * 1024 * 1024;

    /** 再エンコード後の最大辺（px）。デコンプレッション爆弾対策。 */
    private static final int MAX_PHOTO_SIZE_PX = 2000;

    /** 証明写真 presigned URL の TTL（5 分）。 */
    private static final Duration PHOTO_URL_TTL = Duration.ofMinutes(5);

    /** JPEG マジックバイト（先頭 2 バイト: FF D8）。 */
    private static final byte[] JPEG_MAGIC = { (byte) 0xFF, (byte) 0xD8 };

    /** PNG マジックバイト（先頭 4 バイト: 89 50 4E 47）。 */
    private static final byte[] PNG_MAGIC = { (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47 };

    private final ResumeRepository resumeRepository;
    private final StorageService storageService;
    private final AuditLogService auditLogService;

    /**
     * 証明写真をアップロードし、{@code photo_key} を更新して presigned URL を返す。
     *
     * <p>処理手順（設計書§5.10）:
     * <ol>
     *   <li>所有者確認（{@code findByIdAndUserId}。不一致・不存在はいずれも RESUME_001 → 404 で存在を秘匿）</li>
     *   <li>Content-Type + マジックバイト検証（JPEG/PNG のみ。それ以外 → RESUME_007）</li>
     *   <li>サイズ検証（5MB 超過 → RESUME_006）</li>
     *   <li>画像の再エンコード（EXIF/GPS 除去・寸法上限 2000px）</li>
     *   <li>R2 保存: {@code user/{userId}/resume/{resumeId}/photo.{ext}}</li>
     *   <li>{@code resumes.photo_key} 更新</li>
     *   <li>監査ログ: {@code RESUME_PHOTO_UPLOADED} を記録</li>
     *   <li>presigned URL（TTL 5 分）を返却</li>
     * </ol>
     *
     * @param resumeId 対象の履歴書 ID
     * @param userId   認証ユーザー ID
     * @param file     アップロードファイル
     * @return presigned URL（TTL 5 分）
     */
    @Transactional
    public String uploadPhoto(UUID resumeId, Long userId, MultipartFile file) {
        // --- 1. 所有者確認 ---
        // 認可判定は入力検証・再エンコードより前に置く。所有者以外に対して
        // 「ファイル形式は妥当だが履歴書が無い／形式が不正」という差分を返さないことで、
        // 履歴書の存在有無が推測される余地を無くす（不一致・不存在とも RESUME_001 → 404）。
        ResumeEntity resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_001));

        // --- 2. バリデーション ---
        String contentType = file.getContentType();
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("証明写真の読み込み失敗: resumeId={}", resumeId, e);
            throw new BusinessException(ResumeErrorCode.RESUME_007);
        }

        // Content-Type チェック
        if (!isAllowedContentType(contentType)) {
            throw new BusinessException(ResumeErrorCode.RESUME_007);
        }

        // マジックバイト検証（Content-Type 偽装対策）
        PhotoFormat format = detectPhotoFormat(bytes);
        if (format == null) {
            throw new BusinessException(ResumeErrorCode.RESUME_007);
        }

        // サイズ検証（5MB 超過）
        if (bytes.length > MAX_PHOTO_SIZE_BYTES) {
            throw new BusinessException(ResumeErrorCode.RESUME_006);
        }

        // --- 3. 画像の再エンコード（EXIF/GPS 除去・寸法上限）---
        byte[] sanitized = sanitizeImage(bytes, format);

        // --- 4. R2 に保存 ---
        String ext = (format == PhotoFormat.JPEG) ? "jpg" : "png";
        String storageKey = buildPhotoKey(userId, resumeId, ext);
        String uploadContentType = (format == PhotoFormat.JPEG) ? "image/jpeg" : "image/png";
        storageService.upload(storageKey, sanitized, uploadContentType);

        // --- 5. photo_key を更新 ---
        resume.updatePhotoKey(storageKey);
        resumeRepository.save(resume);

        // --- 6. 監査ログ ---
        auditLogService.record(
                AuditEventType.RESUME_PHOTO_UPLOADED.name(),
                userId, userId,
                null, null,
                null, null, null,
                "{\"resumeId\":\"" + resumeId + "\"}"
        );

        // --- 7. presigned URL を返却 ---
        return storageService.generateDownloadUrl(storageKey, PHOTO_URL_TTL);
    }

    /**
     * 証明写真を削除し、{@code photo_key} を NULL にする。
     *
     * @param resumeId 対象の履歴書 ID
     * @param userId   認証ユーザー ID
     */
    @Transactional
    public void deletePhoto(UUID resumeId, Long userId) {
        ResumeEntity resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_001));

        String photoKey = resume.getPhotoKey();
        if (photoKey != null) {
            storageService.delete(photoKey);
        }

        resume.updatePhotoKey(null);
        resumeRepository.save(resume);
    }

    /**
     * {@code photo_key} から presigned URL（TTL 5 分）を発行して返す。
     * {@code photo_key} が null なら null を返す。
     *
     * @param photoKey R2 ストレージキー
     * @return presigned URL、または null
     */
    public String generatePhotoUrl(String photoKey) {
        if (photoKey == null || photoKey.isBlank()) {
            return null;
        }
        return storageService.generateDownloadUrl(photoKey, PHOTO_URL_TTL);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部ヘルパー
    // ──────────────────────────────────────────────────────────────────────

    /** 対応する写真フォーマット種別。 */
    private enum PhotoFormat { JPEG, PNG }

    /**
     * Content-Type が JPEG / PNG のいずれかを確認する。
     */
    private boolean isAllowedContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        return contentType.equalsIgnoreCase("image/jpeg")
                || contentType.equalsIgnoreCase("image/jpg")
                || contentType.equalsIgnoreCase("image/png");
    }

    /**
     * マジックバイトから画像フォーマットを検出する。
     * 検出できない場合は null を返す。
     */
    private PhotoFormat detectPhotoFormat(byte[] bytes) {
        if (bytes.length < 4) {
            return null;
        }
        // JPEG: FF D8
        if ((bytes[0] & 0xFF) == (JPEG_MAGIC[0] & 0xFF)
                && (bytes[1] & 0xFF) == (JPEG_MAGIC[1] & 0xFF)) {
            return PhotoFormat.JPEG;
        }
        // PNG: 89 50 4E 47
        if ((bytes[0] & 0xFF) == (PNG_MAGIC[0] & 0xFF)
                && (bytes[1] & 0xFF) == (PNG_MAGIC[1] & 0xFF)
                && (bytes[2] & 0xFF) == (PNG_MAGIC[2] & 0xFF)
                && (bytes[3] & 0xFF) == (PNG_MAGIC[3] & 0xFF)) {
            return PhotoFormat.PNG;
        }
        return null;
    }

    /**
     * 画像を再エンコードして EXIF/GPS を除去し、寸法上限を適用する。
     *
     * <p>{@link javax.imageio.ImageIO} でデコード → {@link BufferedImage} に変換（TYPE_INT_RGB）→
     * 再エンコードで EXIF メタデータは自然に除去される。
     * {@code ImageConverter.resize()} と同等のロジックをここで直接実装する。
     */
    private byte[] sanitizeImage(byte[] bytes, PhotoFormat format) {
        try {
            // デコード
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(bytes));
            if (original == null) {
                throw new BusinessException(ResumeErrorCode.RESUME_007);
            }

            // 寸法上限を超える場合はリサイズ（アスペクト比維持）
            BufferedImage resized = resizeIfNeeded(original, MAX_PHOTO_SIZE_PX);

            // 再エンコード（TYPE_INT_RGB で EXIF なし）
            // JPEG の場合は透過チャンネルがないため TYPE_INT_RGB を使う
            BufferedImage reencoded;
            if (resized.getType() == BufferedImage.TYPE_INT_RGB) {
                reencoded = resized;
            } else {
                reencoded = new BufferedImage(
                        resized.getWidth(), resized.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = reencoded.createGraphics();
                // 透過ピクセルを白背景で塗りつぶす（JPEG 非対応の透明チャンネルを潰す）
                g2d.setColor(Color.WHITE);
                g2d.fillRect(0, 0, resized.getWidth(), resized.getHeight());
                g2d.drawImage(resized, 0, 0, null);
                g2d.dispose();
            }

            // 再エンコード
            String formatName = (format == PhotoFormat.JPEG) ? "jpeg" : "png";
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            boolean written = ImageIO.write(reencoded, formatName, baos);
            if (!written) {
                log.error("画像の再エンコード失敗: format={}", formatName);
                throw new BusinessException(ResumeErrorCode.RESUME_007);
            }
            return baos.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("証明写真の再エンコード失敗", e);
            throw new BusinessException(ResumeErrorCode.RESUME_007);
        }
    }

    /**
     * 最大辺が {@code maxSize} を超える場合にリサイズする（アスペクト比維持）。
     * 超えない場合はそのまま返す。
     */
    private BufferedImage resizeIfNeeded(BufferedImage original, int maxSize) {
        int w = original.getWidth();
        int h = original.getHeight();
        if (w <= maxSize && h <= maxSize) {
            return original;
        }
        double scale = Math.min((double) maxSize / w, (double) maxSize / h);
        int newW = (int) (w * scale);
        int newH = (int) (h * scale);

        BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, newW, newH);
        g2d.drawImage(original, 0, 0, newW, newH, null);
        g2d.dispose();
        return resized;
    }

    /**
     * 証明写真の R2 ストレージキーを組み立てる。
     * パターン: {@code user/{userId}/resume/{resumeId}/photo.{ext}}
     */
    private String buildPhotoKey(Long userId, UUID resumeId, String ext) {
        return "user/" + userId + "/resume/" + resumeId + "/photo." + ext;
    }
}
