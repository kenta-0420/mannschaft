package com.mannschaft.app.gallery.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gallery.GalleryErrorCode;
import com.mannschaft.app.gallery.GalleryMapper;
import com.mannschaft.app.gallery.dto.DownloadResponse;
import com.mannschaft.app.gallery.dto.PhotoResponse;
import com.mannschaft.app.gallery.dto.UpdatePhotoRequest;
import com.mannschaft.app.gallery.dto.UploadPhotosRequest;
import com.mannschaft.app.gallery.dto.UploadPhotosResponse;
import com.mannschaft.app.gallery.entity.PhotoAlbumEntity;
import com.mannschaft.app.gallery.entity.PhotoEntity;
import com.mannschaft.app.gallery.repository.PhotoAlbumRepository;
import com.mannschaft.app.gallery.repository.PhotoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 写真サービス。写真のアップロード・削除・更新・ダウンロードを担当する。
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

    private static final int MAX_FILE_SIZE = 20 * 1024 * 1024; // 20MB
    private static final int MAX_BATCH_SIZE = 20;
    private static final int MAX_PHOTO_COUNT = 5000;
    private static final long MAX_STORAGE_SIZE = 10L * 1024 * 1024 * 1024; // 10GB

    /**
     * アルバム内写真をページング取得する。
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
     * 写真をアップロードする（メタデータ登録）。
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

        for (UploadPhotosRequest.PhotoItem item : request.getPhotos()) {
            String contentType = item.getContentType() != null ? item.getContentType() : "image/jpeg";

            PhotoEntity entity = PhotoEntity.builder()
                    .albumId(albumId)
                    .s3Key(item.getS3Key())
                    .originalFilename(item.getOriginalFilename())
                    .contentType(contentType)
                    .fileSize(item.getFileSize())
                    .caption(item.getCaption())
                    .uploadedBy(userId)
                    .build();

            PhotoEntity saved = photoRepository.save(entity);
            uploadedPhotos.add(new UploadPhotosResponse.UploadedPhotoInfo(
                    saved.getId(), null, "PROCESSING"));
        }

        // 写真カウントを更新
        album.incrementPhotoCount(request.getPhotos().size());
        albumRepository.save(album);

        log.info("写真アップロード: albumId={}, count={}", albumId, request.getPhotos().size());

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
     * 写真を削除する。
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
        log.info("写真削除: photoId={}, albumId={}", photoId, entity.getAlbumId());

        // TODO: ApplicationEvent で S3 ファイル削除を非同期実行
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

        // TODO: S3 Pre-signed URL 生成
        String downloadUrl = "https://s3.example.com/" + entity.getS3Key();
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

        // TODO: S3 から ZIP 生成 → 一時バケットに保存 → Pre-signed URL 返却
        String jobId = UUID.randomUUID().toString();
        String downloadUrl = "https://s3.example.com/tmp/download-" + jobId + ".zip";
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
