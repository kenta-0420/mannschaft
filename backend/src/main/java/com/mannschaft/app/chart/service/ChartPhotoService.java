package com.mannschaft.app.chart.service;

import com.mannschaft.app.chart.ChartErrorCode;
import com.mannschaft.app.chart.ChartMapper;
import com.mannschaft.app.chart.ChartPhotoUrlProvider;
import com.mannschaft.app.chart.PhotoType;
import com.mannschaft.app.chart.dto.ChartPhotoResponse;
import com.mannschaft.app.chart.entity.ChartPhotoEntity;
import com.mannschaft.app.chart.entity.ChartRecordEntity;
import com.mannschaft.app.chart.repository.ChartPhotoRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

/**
 * カルテ写真サービス。写真のアップロード・削除を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChartPhotoService {

    private static final int MAX_PHOTOS_PER_CHART = 20;
    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10MB
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp"
    );

    private final ChartPhotoRepository photoRepository;
    private final ChartRecordRepository recordRepository;
    private final ChartMapper chartMapper;
    private final ChartPhotoUrlProvider photoUrlProvider;

    /**
     * 写真をアップロードする。
     */
    @Transactional
    public ChartPhotoResponse uploadPhoto(Long teamId, Long chartId, MultipartFile file,
                                          String photoType, String note, Boolean isSharedToCustomer) {
        // カルテ存在確認
        ChartRecordEntity record = recordRepository.findByIdAndTeamId(chartId, teamId)
                .orElseThrow(() -> new BusinessException(ChartErrorCode.CHART_NOT_FOUND));

        // ファイルバリデーション
        validateFile(file);

        // 写真種別バリデーション
        PhotoType.valueOf(photoType);

        // 枚数上限チェック
        long currentCount = photoRepository.countByChartRecordId(chartId);
        if (currentCount >= MAX_PHOTOS_PER_CHART) {
            throw new BusinessException(ChartErrorCode.PHOTO_LIMIT_EXCEEDED);
        }

        // S3キー生成
        String yearMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String extension = getFileExtension(file.getOriginalFilename());
        String s3Key = String.format("charts/%d/%s/%d/%s.%s",
                teamId, yearMonth, chartId, UUID.randomUUID(), extension);

        // TODO: 実際のS3アップロード処理 + EXIFのGPS情報ストリップ

        ChartPhotoEntity entity = ChartPhotoEntity.builder()
                .chartRecordId(chartId)
                .photoType(photoType)
                .s3Key(s3Key)
                .originalFilename(file.getOriginalFilename())
                .fileSizeBytes((int) file.getSize())
                .contentType(file.getContentType())
                .sortOrder((int) currentCount)
                .note(note)
                .isSharedToCustomer(isSharedToCustomer != null ? isSharedToCustomer : false)
                .build();

        ChartPhotoEntity saved = photoRepository.save(entity);
        log.info("写真アップロード: chartId={}, photoId={}", chartId, saved.getId());

        return chartMapper.toPhotoResponse(saved,
                photoUrlProvider.generateSignedUrl(saved.getS3Key()),
                photoUrlProvider.getExpiresAt());
    }

    /**
     * 写真を削除する。
     */
    @Transactional
    public void deletePhoto(Long teamId, Long photoId) {
        ChartPhotoEntity photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new BusinessException(ChartErrorCode.PHOTO_NOT_FOUND));

        // カルテがこのチームに属しているか確認
        recordRepository.findByIdAndTeamId(photo.getChartRecordId(), teamId)
                .orElseThrow(() -> new BusinessException(ChartErrorCode.CHART_NOT_FOUND));

        // TODO: S3オブジェクトは論理削除時には削除しない（物理削除バッチで処理）
        photoRepository.delete(photo);
        log.info("写真削除: photoId={}", photoId);
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(ChartErrorCode.INVALID_FILE_TYPE);
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new BusinessException(ChartErrorCode.INVALID_FILE_TYPE);
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ChartErrorCode.FILE_SIZE_EXCEEDED);
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "bin";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
