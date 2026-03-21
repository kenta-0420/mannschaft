package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.FileSharingMapper;
import com.mannschaft.app.filesharing.dto.StarResponse;
import com.mannschaft.app.filesharing.entity.SharedFileStarEntity;
import com.mannschaft.app.filesharing.repository.SharedFileStarRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ファイルスターサービス。ファイルのお気に入り管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SharedFileStarService {

    private final SharedFileStarRepository starRepository;
    private final FileSharingMapper fileSharingMapper;

    /**
     * ユーザーのスター一覧を取得する。
     *
     * @param userId ユーザーID
     * @return スターレスポンスリスト
     */
    public List<StarResponse> listStarsByUser(Long userId) {
        List<SharedFileStarEntity> stars = starRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return fileSharingMapper.toStarResponseList(stars);
    }

    /**
     * ファイルにスターを付ける。
     *
     * @param fileId ファイルID
     * @param userId ユーザーID
     * @return 作成されたスターレスポンス
     */
    @Transactional
    public StarResponse addStar(Long fileId, Long userId) {
        if (starRepository.existsByFileIdAndUserId(fileId, userId)) {
            throw new BusinessException(FileSharingErrorCode.STAR_ALREADY_EXISTS);
        }

        SharedFileStarEntity entity = SharedFileStarEntity.builder()
                .fileId(fileId)
                .userId(userId)
                .build();

        SharedFileStarEntity saved = starRepository.save(entity);
        log.info("スター追加: fileId={}, userId={}", fileId, userId);
        return fileSharingMapper.toStarResponse(saved);
    }

    /**
     * ファイルのスターを外す。
     *
     * @param fileId ファイルID
     * @param userId ユーザーID
     */
    @Transactional
    public void removeStar(Long fileId, Long userId) {
        SharedFileStarEntity entity = starRepository.findByFileIdAndUserId(fileId, userId)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.STAR_NOT_FOUND));
        starRepository.delete(entity);
        log.info("スター削除: fileId={}, userId={}", fileId, userId);
    }

    /**
     * ファイルのスター数を取得する。
     *
     * @param fileId ファイルID
     * @return スター数
     */
    public long countStars(Long fileId) {
        return starRepository.countByFileId(fileId);
    }
}
