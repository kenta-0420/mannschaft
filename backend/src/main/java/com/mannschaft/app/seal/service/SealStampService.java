package com.mannschaft.app.seal.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.seal.SealErrorCode;
import com.mannschaft.app.seal.SealMapper;
import com.mannschaft.app.seal.SealVariant;
import com.mannschaft.app.seal.StampTargetType;
import com.mannschaft.app.seal.dto.StampLogResponse;
import com.mannschaft.app.seal.dto.StampRequest;
import com.mannschaft.app.seal.dto.StampVerifyResponse;
import com.mannschaft.app.seal.entity.ElectronicSealEntity;
import com.mannschaft.app.seal.entity.SealStampLogEntity;
import com.mannschaft.app.seal.repository.ElectronicSealRepository;
import com.mannschaft.app.seal.repository.SealStampLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 押印サービス。押印の実行・取消・検証を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SealStampService {

    private final SealStampLogRepository stampLogRepository;
    private final ElectronicSealRepository sealRepository;
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
        // variant は stamp 時点で seal が存在確認済みのため null にならないが、graceful fallback として orElse(null)
        return sealMapper.toStampLogResponse(saved, seal.getVariant());
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
        // sealId から variant を解決（印鑑が削除済みの場合は null）
        SealVariant variant = sealRepository.findById(saved.getSealId())
                .map(ElectronicSealEntity::getVariant)
                .orElse(null);
        return sealMapper.toStampLogResponse(saved, variant);
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
        Map<Long, SealVariant> variantMap = buildVariantMap(logs);
        return logs.stream()
                .map(log -> sealMapper.toStampLogResponse(log, variantMap.get(log.getSealId())))
                .toList();
    }

    /** 押印ログ一覧のデフォルト取得件数。 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 押印ログ一覧の最大取得件数。 */
    private static final int MAX_PAGE_SIZE = 50;

    /**
     * ユーザーの押印ログ一覧をカーソルページングで取得する（stampedAt 降順 = id 降順）。
     *
     * @param userId         ユーザーID
     * @param cursor         カーソル（直前ページ末尾の id）。null の場合は先頭から取得
     * @param size           取得件数。null の場合は 20、50 を超える場合は 50 に丸める
     * @param targetType     対象種別での絞り込み（任意）。null の場合は絞り込まない
     * @param includeRevoked 取消済みを含めるか。false の場合は取消済みを除外する
     * @return カーソルページネーション付き押印ログレスポンス
     */
    public CursorPagedResponse<StampLogResponse> listStampLogs(
            Long userId, Long cursor, Integer size, String targetType, boolean includeRevoked) {
        int effectiveSize = resolvePageSize(size);
        StampTargetType type = targetType != null && !targetType.isBlank()
                ? StampTargetType.valueOf(targetType)
                : null;

        // hasNext 判定のため effectiveSize + 1 件取得する
        Pageable pageable = PageRequest.of(0, effectiveSize + 1);
        List<SealStampLogEntity> logs = stampLogRepository.findByUserIdWithCursor(
                userId, cursor, type, includeRevoked, pageable);

        boolean hasNext = logs.size() > effectiveSize;
        if (hasNext) {
            logs = logs.subList(0, effectiveSize);
        }

        // variant を sealId 単位で一括解決する（N+1 回避）
        Map<Long, SealVariant> variantMap = buildVariantMap(logs);
        List<StampLogResponse> data = logs.stream()
                .map(log -> sealMapper.toStampLogResponse(log, variantMap.get(log.getSealId())))
                .toList();

        String nextCursor = hasNext && !logs.isEmpty()
                ? String.valueOf(logs.get(logs.size() - 1).getId())
                : null;

        return CursorPagedResponse.of(
                data,
                new CursorPagedResponse.CursorMeta(nextCursor, hasNext, effectiveSize));
    }

    /**
     * 取得件数を解決する。null は既定 {@value #DEFAULT_PAGE_SIZE}、
     * 上限 {@value #MAX_PAGE_SIZE} を超える場合は丸める。1 未満も既定値にフォールバックする。
     */
    private int resolvePageSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
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
        Map<Long, SealVariant> variantMap = buildVariantMap(logs);
        return logs.stream()
                .map(log -> sealMapper.toStampLogResponse(log, variantMap.get(log.getSealId())))
                .toList();
    }

    /**
     * 押印ログ一覧の sealId 群を一括解決して variant マップを作成する（N+1 回避）。
     * 印鑑が削除済みの場合は sealId がマップに存在しない（variant=null として graceful fallback）。
     *
     * @param logs 押印ログエンティティ一覧
     * @return sealId → SealVariant マップ
     */
    private Map<Long, SealVariant> buildVariantMap(List<SealStampLogEntity> logs) {
        Set<Long> sealIds = logs.stream()
                .map(SealStampLogEntity::getSealId)
                .collect(Collectors.toSet());
        return sealRepository.findAllById(sealIds).stream()
                .collect(Collectors.toMap(ElectronicSealEntity::getId, ElectronicSealEntity::getVariant));
    }

    /**
     * 押印ログを取得する。存在しない場合は例外をスローする。
     */
    private SealStampLogEntity findStampLogOrThrow(Long userId, Long stampLogId) {
        return stampLogRepository.findByIdAndUserId(stampLogId, userId)
                .orElseThrow(() -> new BusinessException(SealErrorCode.STAMP_LOG_NOT_FOUND));
    }
}
