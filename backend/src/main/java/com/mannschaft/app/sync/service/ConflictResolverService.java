package com.mannschaft.app.sync.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.sync.SyncErrorCode;
import com.mannschaft.app.sync.dto.ConflictDetailResponse;
import com.mannschaft.app.sync.dto.ConflictResponse;
import com.mannschaft.app.sync.dto.ResolveConflictRequest;
import com.mannschaft.app.sync.entity.OfflineSyncConflictEntity;
import com.mannschaft.app.sync.repository.OfflineSyncConflictRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F11.1 オフライン同期: コンフリクト解決サービス。
 *
 * 未解決コンフリクトの一覧取得、詳細取得、解決（CLIENT_WIN / SERVER_WIN / MANUAL_MERGE）、破棄を行う。
 * 権限チェックは userId ベースで行い、他人のコンフリクトにはアクセスできない。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConflictResolverService {

    private final OfflineSyncConflictRepository conflictRepository;

    /**
     * ユーザーの未解決コンフリクト一覧を取得する。
     *
     * @param userId ユーザーID
     * @param pageable ページング情報
     * @return 未解決コンフリクトのページ
     */
    public Page<ConflictResponse> getMyConflicts(Long userId, Pageable pageable) {
        return conflictRepository
                .findByUserIdAndResolutionIsNullOrderByCreatedAtDesc(userId, pageable)
                .map(this::toConflictResponse);
    }

    /**
     * コンフリクトの詳細を取得する。
     *
     * @param conflictId コンフリクトID
     * @param userId リクエスト元のユーザーID（権限チェック）
     * @return コンフリクト詳細
     * @throws BusinessException コンフリクトが見つからない場合、または他人のコンフリクトの場合
     */
    public ConflictDetailResponse getConflictDetail(Long conflictId, Long userId) {
        OfflineSyncConflictEntity entity = findByIdAndCheckOwnership(conflictId, userId);
        return toConflictDetailResponse(entity);
    }

    /**
     * コンフリクトを解決する。
     *
     * @param conflictId コンフリクトID
     * @param userId リクエスト元のユーザーID
     * @param request 解決リクエスト
     * @return 更新後のコンフリクト詳細
     * @throws BusinessException 既に解決済み、マージデータ不足、権限なし等
     */
    @Transactional
    public ConflictDetailResponse resolveConflict(Long conflictId, Long userId,
                                                   ResolveConflictRequest request) {
        OfflineSyncConflictEntity entity = findByIdAndCheckOwnership(conflictId, userId);

        if (entity.getResolution() != null) {
            throw new BusinessException(SyncErrorCode.CONFLICT_ALREADY_RESOLVED);
        }

        String resolution = request.getResolution();

        if ("MANUAL_MERGE".equals(resolution) && (request.getMergedData() == null
                || request.getMergedData().isBlank())) {
            throw new BusinessException(SyncErrorCode.CONFLICT_MERGE_DATA_REQUIRED);
        }

        entity.resolve(resolution);

        log.info("コンフリクト解決: conflictId={}, userId={}, resolution={}",
                conflictId, userId, resolution);

        return toConflictDetailResponse(entity);
    }

    /**
     * コンフリクトを破棄（DISCARDED）する。
     *
     * @param conflictId コンフリクトID
     * @param userId リクエスト元のユーザーID
     * @throws BusinessException 既に解決済み、権限なし等
     */
    @Transactional
    public void discardConflict(Long conflictId, Long userId) {
        OfflineSyncConflictEntity entity = findByIdAndCheckOwnership(conflictId, userId);

        if (entity.getResolution() != null) {
            throw new BusinessException(SyncErrorCode.CONFLICT_ALREADY_RESOLVED);
        }

        entity.resolve("DISCARDED");

        log.info("コンフリクト破棄: conflictId={}, userId={}", conflictId, userId);
    }

    /**
     * ID とユーザーID でコンフリクトを取得する。見つからない場合は例外をスローする。
     */
    private OfflineSyncConflictEntity findByIdAndCheckOwnership(Long conflictId, Long userId) {
        return conflictRepository.findByIdAndUserId(conflictId, userId)
                .orElseThrow(() -> new BusinessException(SyncErrorCode.CONFLICT_NOT_FOUND));
    }

    private ConflictResponse toConflictResponse(OfflineSyncConflictEntity entity) {
        return new ConflictResponse(
                entity.getId(),
                entity.getResourceType(),
                entity.getResourceId(),
                entity.getClientVersion(),
                entity.getServerVersion(),
                entity.getResolution(),
                entity.getResolvedAt(),
                entity.getCreatedAt());
    }

    private ConflictDetailResponse toConflictDetailResponse(OfflineSyncConflictEntity entity) {
        return new ConflictDetailResponse(
                entity.getId(),
                entity.getUserId(),
                entity.getResourceType(),
                entity.getResourceId(),
                entity.getClientData(),
                entity.getServerData(),
                entity.getClientVersion(),
                entity.getServerVersion(),
                entity.getResolution(),
                entity.getResolvedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
