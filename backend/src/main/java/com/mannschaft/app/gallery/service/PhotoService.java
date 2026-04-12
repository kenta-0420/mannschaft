package com.mannschaft.app.gallery.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.storage.S3ObjectDeleteEvent;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.gallery.GalleryErrorCode;
import com.mannschaft.app.gallery.GalleryMapper;
import com.mannschaft.app.gallery.GalleryMediaType;
import com.mannschaft.app.gallery.GalleryProcessingStatus;
import com.mannschaft.app.gallery.dto.DownloadResponse;
import com.mannschaft.app.gallery.dto.PhotoResponse;
import com.mannschaft.app.gallery.dto.UpdatePhotoRequest;
import com.mannschaft.app.gallery.dto.UploadPhotosRequest;
import com.mannschaft.app.gallery.dto.UploadPhotosResponse;
import com.mannschaft.app.gallery.entity.PhotoAlbumEntity;
import com.mannschaft.app.gallery.entity.PhotoEntity;
import com.mannschaft.app.gallery.event.PhotoUploadEvent;
import com.mannschaft.app.gallery.repository.PhotoAlbumRepository;
import com.mannschaft.app.gallery.repository.PhotoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 写真・動画サービス。メディアのアップロード・削除・更新・ダウンロードを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PhotoService {

    private final PhotoRepository photoRepository;
    private final PhotoAlbumRepository albumRepository;
    private final PhotoAlbumService albumService;
    private final GalleryMapper galleryMapper;
    private final R2StorageService r2StorageService;
    private final DomainEventPublisher eventPublisher;

    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024; // 100MB（動画対応）
    private static final int MAX_BATCH_SIZE = 20;
    private static final int MAX_PHOTO_COUNT = 5000;

    /**
     * アルバム内メディアをページング取得する。
     */
    public Page<PhotoResponse> listPhotos(Long albumId, String sort, Pageable pageable) {
        Page<PhotoEntity> page;
        if ("taken_at".equals(sort)) {
            page = photoRepository.findByAlbumIdOrderByTakenAtDesc(albumId, pageable);
        } else {
            page = photoRepository.findByAlbumIdOrderBySortOrder(albumId, pageable);
        }
        return page.map(galleryMapper::toPhotoResponse);
    }

    /**
     * メディアをアップロードする（メタデータ登録）。
     */
    @Transactional
    public UploadPhotosResponse uploadPhotos(Long albumId, Long userId, UploadPhotosRequest request) {
        if (request.getPhotos().size() > MAX_BATCH_SIZE) {
            throw new BusinessException(GalleryErrorCode.BATCH_UPLOAD_LIMIT_EXCEEDED);
        }

        PhotoAlbumEntity album = albumService.findAlbumOrThrow(albumId);

        // ファイルサイズチェック
        for (UploadPhotosRequest.PhotoItem item : request.getPhotos()) {
            if (item.getFileSize() > MAX_FILE_SIZE) {
                throw new BusinessException(GalleryErrorCode.FILE_SIZE_EXCEEDED);
            }
        }

        // ストレージクォータチェック
        int currentPhotoCount;
        if (album.getTeamId() != null) {
            currentPhotoCount = albumRepository.sumPhotoCountByTeamId(album.getTeamId());
        } else {
            currentPhotoCount = albumRepository.sumPhotoCountByOrganizationId(album.getOrganizationId());
        }

        if (currentPhotoCount + request.getPhotos().size() > MAX_PHOTO_COUNT) {
            throw new BusinessException(GalleryErrorCode.PHOTO_LIMIT_EXCEEDED);
        }

        List<UploadPhotosResponse.UploadedPhotoInfo> uploadedPhotos = new ArrayList<>();
        List<Long> savedPhotoIds = new ArrayList<>();

        for (UploadPhotosRequest.PhotoItem item : request.getPhotos()) {
            String contentType = item.getContentType() != null ? item.getContentType() : "image/jpeg";

            // メディアタイプを決定
            GalleryMediaType mediaType = item.getMediaType() != null
                    ? GalleryMediaType.valueOf(item.getMediaType())
                    : GalleryMediaType.PHOTO;

            // VIDEO の場合はストレージ存在確認
            if (mediaType == GalleryMediaType.VIDEO && !r2StorageService.objectExists(item.getR2Key())) {
                throw new BusinessException(GalleryErrorCode.MEDIA_NOT_FOUND_IN_STORAGE);
            }

            // processingStatus を決定
            GalleryProcessingStatus processingStatus;
            if (mediaType == GalleryMediaType.PHOTO) {
                processingStatus = GalleryProcessingStatus.READY;
            } else if (item.getThumbnailR2Key() != null && !item.getThumbnailR2Key().isBlank()) {
                processingStatus = GalleryProcessingStatus.READY;
            } else {
                processingStatus = GalleryProcessingStatus.PENDING;
            }

            PhotoEntity entity = PhotoEntity.builder()
                    .albumId(albumId)
                    .r2Key(item.getR2Key())
                    .thumbnailR2Key(item.getThumbnailR2Key())
                    .originalFilename(item.getOriginalFilename())
                    .contentType(contentType)
                    .fileSize(item.getFileSize())
                    .caption(item.getCaption())
                    .uploadedBy(userId)
                    .mediaType(mediaType)
                    .processingStatus(processingStatus)
                    .build();

            PhotoEntity saved = photoRepository.save(entity);
            savedPhotoIds.add(saved.getId());
            uploadedPhotos.add(new UploadPhotosResponse.UploadedPhotoInfo(
                    saved.getId(), null, processingStatus.name()));
        }

        // 写真カウントを更新
        album.incrementPhotoCount(request.getPhotos().size());
        albumRepository.save(album);

        // サムネイル自動生成イベント発行（トランザクションコミット後に非同期実行）
        eventPublisher.publish(new PhotoUploadEvent(savedPhotoIds));

        log.info("メディアアップロード: albumId={}, count={}", albumId, request.getPhotos().size());

        return new UploadPhotosResponse(
                uploadedPhotos.size(), album.getPhotoCount(), uploadedPhotos);
    }

    /**
     * 写真情報を更新する。
     */
    @Transactional
    public PhotoResponse updatePhoto(Long photoId, UpdatePhotoRequest request) {
        PhotoEntity entity = findPhotoOrThrow(photoId);

        Integer sortOrder = request.getSortOrder() != null ? request.getSortOrder() : entity.getSortOrder();
        entity.update(request.getCaption(), sortOrder);

        PhotoEntity saved = photoRepository.save(entity);
        log.info("写真更新: photoId={}", photoId);
        return galleryMapper.toPhotoResponse(saved);
    }

    /**
     * 写真・動画を削除する。
     */
    @Transactional
    public void deletePhoto(Long photoId) {
        PhotoEntity entity = findPhotoOrThrow(photoId);

        // アルバムの写真カウントを減算
        albumRepository.findById(entity.getAlbumId()).ifPresent(album -> {
            album.decrementPhotoCount();
            albumRepository.save(album);
        });

        photoRepository.delete(entity);
        log.info("メディア削除: photoId={}, albumId={}", photoId, entity.getAlbumId());

        eventPublisher.publish(new S3ObjectDeleteEvent(entity.getR2Key()));
    }

    /**
     * 個別写真ダウンロード用の Pre-signed URL を生成する。
     */
    public DownloadResponse getPhotoDownloadUrl(Long photoId) {
        PhotoEntity entity = findPhotoOrThrow(photoId);

        // アルバムの allow_download チェック
        PhotoAlbumEntity album = albumService.findAlbumOrThrow(entity.getAlbumId());
        if (!album.getAllowDownload()) {
            throw new BusinessException(GalleryErrorCode.DOWNLOAD_NOT_ALLOWED);
        }

        String downloadUrl = r2StorageService.generateDownloadUrl(entity.getR2Key(), Duration.ofMinutes(30));
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);

        return new DownloadResponse(downloadUrl, entity.getOriginalFilename(), null, expiresAt);
    }

    /**
     * アルバム一括ダウンロード用の Pre-signed URL を生成する。
     */
    public DownloadResponse getAlbumDownloadUrl(Long albumId, List<Long> photoIds, int limit) {
        PhotoAlbumEntity album = albumService.findAlbumOrThrow(albumId);

        if (!album.getAllowDownload()) {
            throw new BusinessException(GalleryErrorCode.DOWNLOAD_NOT_ALLOWED);
        }

        List<PhotoEntity> photos;
        if (photoIds != null && !photoIds.isEmpty()) {
            // 指定された写真IDのみ取得（選択ダウンロード）
            photos = photoRepository.findByAlbumIdAndIdIn(albumId, photoIds);
        } else {
            photos = photoRepository.findByAlbumIdOrderBySortOrder(albumId);
        }

        // limit で最大枚数を制限
        if (photos.size() > limit) {
            photos = photos.subList(0, limit);
        }

        String jobId = UUID.randomUUID().toString();

        ByteArrayOutputStream zipOut = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipOut)) {
            for (int i = 0; i < photos.size(); i++) {
                PhotoEntity photo = photos.get(i);
                // 動画ファイルはZIPダウンロード対象から除外（サイズが大きいため）
                if (photo.getMediaType() == GalleryMediaType.VIDEO) {
                    continue;
                }
                byte[] data = r2StorageService.download(photo.getR2Key());
                String entryName = (i + 1) + "_" + photo.getOriginalFilename();
                zos.putNextEntry(new ZipEntry(entryName));
                zos.write(data);
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("ZIP 生成に失敗しました", e);
        }

        String zipKey = "tmp/album-" + albumId + "/" + jobId + ".zip";
        r2StorageService.upload(zipKey, zipOut.toByteArray(), "application/zip");
        String downloadUrl = r2StorageService.generateDownloadUrl(zipKey, Duration.ofHours(1));
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(1);

        return new DownloadResponse(downloadUrl, null, photos.size(), expiresAt);
    }

    /**
     * 写真エンティティを取得する。存在しない場合は例外をスローする。
     */
    private PhotoEntity findPhotoOrThrow(Long photoId) {
        return photoRepository.findById(photoId)
                .orElseThrow(() -> new BusinessException(GalleryErrorCode.PHOTO_NOT_FOUND));
    }
}
