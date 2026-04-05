package com.mannschaft.app.seal.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.seal.SealErrorCode;
import com.mannschaft.app.seal.SealMapper;
import com.mannschaft.app.seal.StampTargetType;
import com.mannschaft.app.seal.dto.StampLogResponse;
import com.mannschaft.app.seal.dto.StampRequest;
import com.mannschaft.app.seal.dto.StampVerifyResponse;
import com.mannschaft.app.seal.entity.ElectronicSealEntity;
import com.mannschaft.app.seal.entity.SealStampLogEntity;
import com.mannschaft.app.seal.repository.SealStampLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 押印サービス。押印の実行・取消・検証を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SealStampService {

    private final SealStampLogRepository stampLogRepository;
    private final SealService sealService;
    private final SealMapper sealMapper;

    /**
     * 押印を実行する。
     *
     * @param userId  ユーザーID
     * @param request 押印リクエスト
     * @return 押印ログレスポンス
     */
    @Transactional
    public StampLogResponse stamp(Long userId, StampRequest request) {
        ElectronicSealEntity seal = sealService.getSealEntity(request.getSealId());

        if (seal.isDeleted()) {
            throw new BusinessException(SealErrorCode.SEAL_DELETED);
        }

        StampTargetType targetType = StampTargetType.valueOf(request.getTargetType());

        SealStampLogEntity entity = SealStampLogEntity.builder()
                .userId(userId)
                .sealId(request.getSealId())
                .sealHashAtStamp(seal.getSealHash())
                .targetType(targetType)
                .targetId(request.getTargetId())
                .stampDocumentHash(request.getStampDocumentHash())
                .build();

        SealStampLogEntity saved = stampLogRepository.save(entity);
        log.info("押印実行: userId={}, sealId={}, target={}:{}", userId, request.getSealId(), targetType, request.getTargetId());
        return sealMapper.toStampLogResponse(saved);
    }

    /**
     * 押印を取り消す。
     *
     * @param userId     ユーザーID
     * @param stampLogId 押印ログID
     * @return 更新された押印ログレスポンス
     */
    @Transactional
    public StampLogResponse revokeStamp(Long userId, Long stampLogId) {
        SealStampLogEntity entity = findStampLogOrThrow(userId, stampLogId);

        if (entity.isAlreadyRevoked()) {
            throw new BusinessException(SealErrorCode.ALREADY_REVOKED);
        }

        entity.revoke();
        SealStampLogEntity saved = stampLogRepository.save(entity);
        log.info("押印取消: userId={}, stampLogId={}", userId, stampLogId);
        return sealMapper.toStampLogResponse(saved);
    }

    /**
     * 押印を検証する。印鑑ハッシュの一致と取消状態を確認する。
     *
     * @param stampLogId 押印ログID
     * @return 検証レスポンス
     */
    public StampVerifyResponse verifyStamp(Long stampLogId) {
        SealStampLogEntity stampLog = stampLogRepository.findById(stampLogId)
                .orElseThrow(() -> new BusinessException(SealErrorCode.STAMP_LOG_NOT_FOUND));

        if (stampLog.isAlreadyRevoked()) {
            return new StampVerifyResponse(stampLogId, false, true, "この押印は取り消されています");
        }

        ElectronicSealEntity seal = sealService.getSealEntity(stampLog.getSealId());
        boolean hashValid = stampLog.verify(seal.getSealHash());

        if (!hashValid) {
            return new StampVerifyResponse(stampLogId, false, false, "印鑑が押印後に変更されています");
        }

        return new StampVerifyResponse(stampLogId, true, false, "有効な押印です");
    }

    /**
     * ユーザーの押印ログ一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 押印ログレスポンスリスト
     */
    public List<StampLogResponse> listStampLogs(Long userId) {
        List<SealStampLogEntity> logs = stampLogRepository.findByUserIdOrderByStampedAtDesc(userId);
        return sealMapper.toStampLogResponseList(logs);
    }

    /**
     * 対象の押印ログ一覧を取得する。
     *
     * @param targetType 対象種別
     * @param targetId   対象ID
     * @return 押印ログレスポンスリスト
     */
    public List<StampLogResponse> listStampLogsByTarget(String targetType, Long targetId) {
        StampTargetType type = StampTargetType.valueOf(targetType);
        List<SealStampLogEntity> logs = stampLogRepository.findByTargetTypeAndTargetIdOrderByStampedAtDesc(type, targetId);
        return sealMapper.toStampLogResponseList(logs);
    }

    /**
     * 押印ログを取得する。存在しない場合は例外をスローする。
     */
    private SealStampLogEntity findStampLogOrThrow(Long userId, Long stampLogId) {
        return stampLogRepository.findByIdAndUserId(stampLogId, userId)
                .orElseThrow(() -> new BusinessException(SealErrorCode.STAMP_LOG_NOT_FOUND));
    }
}
