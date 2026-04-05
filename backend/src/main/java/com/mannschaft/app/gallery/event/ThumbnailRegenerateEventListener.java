package com.mannschaft.app.gallery.event;

import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.service.BatchJobLogService;
import com.mannschaft.app.common.storage.ImageConverter;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.gallery.entity.PhotoAlbumEntity;
import com.mannschaft.app.gallery.entity.PhotoEntity;
import com.mannschaft.app.gallery.repository.PhotoAlbumRepository;
import com.mannschaft.app.gallery.repository.PhotoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * サムネイル一括再生成イベントリスナー。
 * トランザクションコミット後に非同期でサムネイルを再生成する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThumbnailRegenerateEventListener {

    private static final int THUMBNAIL_MAX_SIZE = 400;

    private final PhotoRepository photoRepository;
    private final PhotoAlbumRepository albumRepository;
    private final StorageService storageService;
    private final BatchJobLogService batchJobLogService;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleThumbnailRegenerate(ThumbnailRegenerateEvent event) {
        log.info("サムネイル再生成ジョブ開始: jobId={}", event.jobId());

        BatchJobLogEntity jobLog = batchJobLogService.startJob("regenerate-thumbnails:" + event.jobId());

        try {
            List<PhotoEntity> photos = resolveTargetPhotos(event);
            int processedCount = 0;

            for (PhotoEntity photo : photos) {
                try {
                    regenerateThumbnail(photo);
                    processedCount++;
                } catch (Exception e) {
                    log.warn("サムネイル再生成失敗: photoId={}", photo.getId(), e);
                }
            }

            batchJobLogService.completeJob(jobLog, processedCount);
            log.info("サムネイル再生成ジョブ完了: jobId={}, processed={}/{}", event.jobId(), processedCount, photos.size());

        } catch (Exception e) {
            log.error("サムネイル再生成ジョブ失敗: jobId={}", event.jobId(), e);
            batchJobLogService.failJob(jobLog, e.getMessage());
        }
    }

    private List<PhotoEntity> resolveTargetPhotos(ThumbnailRegenerateEvent event) {
        List<Long> albumIds;
        if (event.teamId() != null) {
            albumIds = albumRepository.findByTeamIdOrderByCreatedAtDesc(event.teamId())
                    .stream().map(PhotoAlbumEntity::getId).toList();
        } else if (event.organizationId() != null) {
            albumIds = albumRepository.findByOrganizationIdOrderByCreatedAtDesc(event.organizationId())
                    .stream().map(PhotoAlbumEntity::getId).toList();
        } else {
            return photoRepository.findAll();
        }

        return albumIds.stream()
                .flatMap(albumId -> photoRepository.findByAlbumIdOrderBySortOrder(albumId).stream())
                .toList();
    }

    private void regenerateThumbnail(PhotoEntity photo) {
        try {
            byte[] originalBytes = storageService.download(photo.getS3Key());

            BufferedImage original = ImageIO.read(new ByteArrayInputStream(originalBytes));
            if (original == null) {
                log.warn("画像の読み込み失敗（非画像ファイル）: photoId={}", photo.getId());
                return;
            }

            // WebP形式でサムネイル生成（失敗時はJPEGフォールバック）
            ImageConverter.ConversionResult result =
                    ImageConverter.createThumbnailWebP(originalBytes, THUMBNAIL_MAX_SIZE);

            String thumbExtension = result.converted() ? ".webp" : ".jpg";
            String thumbKey = photo.getS3Key()
                    .replaceFirst("photos/", "photos/thumbs/")
                    .replaceFirst("\\.[^.]+$", thumbExtension);
            storageService.upload(thumbKey, result.data(), result.contentType());

            photo.updateThumbnailAndExif(thumbKey, original.getWidth(), original.getHeight(),
                    photo.getTakenAt(), photo.getContentType());
            photoRepository.save(photo);

        } catch (Exception e) {
            throw new RuntimeException("サムネイル生成失敗: photoId=" + photo.getId(), e);
        }
    }
}
