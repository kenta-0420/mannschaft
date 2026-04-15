package com.mannschaft.app.gallery.event;

import com.mannschaft.app.common.storage.ImageConverter;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.gallery.GalleryMediaType;
import com.mannschaft.app.gallery.entity.PhotoEntity;
import com.mannschaft.app.gallery.repository.PhotoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * 写真アップロード後のサムネイル自動生成リスナー。
 * トランザクションコミット後に非同期でWebPサムネイルを生成し、PhotoEntityを更新する。
 * 動画ファイルはサムネイル自動生成をスキップする。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhotoUploadEventListener {

    private static final int THUMBNAIL_MAX_SIZE = 400;

    private final PhotoRepository photoRepository;
    private final R2StorageService r2StorageService;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void handlePhotoUpload(PhotoUploadEvent event) {
        log.info("サムネイル自動生成開始: photoIds={}", event.photoIds());

        int successCount = 0;
        for (Long photoId : event.photoIds()) {
            try {
                generateThumbnail(photoId);
                successCount++;
            } catch (Exception e) {
                log.warn("サムネイル生成失敗: photoId={}", photoId, e);
            }
        }

        log.info("サムネイル自動生成完了: {}/{}", successCount, event.photoIds().size());
    }

    private void generateThumbnail(Long photoId) {
        PhotoEntity photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalStateException("写真が見つかりません: photoId=" + photoId));

        // 動画ファイルはサムネイル自動生成をスキップ
        if (photo.getMediaType() == GalleryMediaType.VIDEO) {
            log.debug("動画ファイルのためサムネイル生成をスキップ: photoId={}", photoId);
            return;
        }

        byte[] originalBytes = r2StorageService.download(photo.getR2Key());

        BufferedImage original;
        try {
            original = ImageIO.read(new ByteArrayInputStream(originalBytes));
        } catch (Exception e) {
            log.warn("画像の読み込み失敗: photoId={}", photoId, e);
            return;
        }
        if (original == null) {
            log.warn("画像の読み込み失敗（非画像ファイル）: photoId={}", photoId);
            return;
        }

        try {
            // WebP形式でサムネイル生成（失敗時はJPEGフォールバック）
            ImageConverter.ConversionResult result =
                    ImageConverter.createThumbnailWebP(originalBytes, THUMBNAIL_MAX_SIZE);

            String thumbExtension = result.converted() ? ".webp" : ".jpg";
            String thumbKey = photo.getR2Key()
                    .replaceFirst("photos/", "photos/thumbs/")
                    .replaceFirst("\\.[^.]+$", thumbExtension);

            r2StorageService.upload(thumbKey, result.data(), result.contentType());

            photo.updateThumbnailAndExif(thumbKey, original.getWidth(), original.getHeight(),
                    photo.getTakenAt(), photo.getContentType());
            photoRepository.save(photo);

            log.debug("サムネイル生成完了: photoId={}, thumbKey={}, webp={}", photoId, thumbKey, result.converted());
        } catch (Exception e) {
            log.warn("サムネイル生成中にエラー: photoId={}", photoId, e);
        }
    }
}
