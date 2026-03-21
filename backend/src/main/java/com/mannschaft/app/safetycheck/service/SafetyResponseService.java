package com.mannschaft.app.safetycheck.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.safetycheck.FollowupStatus;
import com.mannschaft.app.safetycheck.MessageSource;
import com.mannschaft.app.safetycheck.SafetyCheckErrorCode;
import com.mannschaft.app.safetycheck.SafetyCheckMapper;
import com.mannschaft.app.safetycheck.SafetyCheckStatus;
import com.mannschaft.app.safetycheck.SafetyResponseStatus;
import com.mannschaft.app.safetycheck.dto.BulkRespondRequest;
import com.mannschaft.app.safetycheck.dto.RespondRequest;
import com.mannschaft.app.safetycheck.dto.SafetyResponseResponse;
import com.mannschaft.app.safetycheck.entity.SafetyCheckEntity;
import com.mannschaft.app.safetycheck.entity.SafetyResponseEntity;
import com.mannschaft.app.safetycheck.entity.SafetyResponseFollowupEntity;
import com.mannschaft.app.safetycheck.repository.SafetyCheckRepository;
import com.mannschaft.app.safetycheck.repository.SafetyResponseFollowupRepository;
import com.mannschaft.app.safetycheck.repository.SafetyResponseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 安否確認回答サービス。回答の登録・一括回答を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SafetyResponseService {

    private static final int BULK_RESPOND_LIMIT = 100;

    private final SafetyCheckRepository safetyCheckRepository;
    private final SafetyResponseRepository responseRepository;
    private final SafetyResponseFollowupRepository followupRepository;
    private final SafetyCheckMapper mapper;

    /**
     * 安否確認に回答する。
     *
     * @param safetyCheckId 安否確認ID
     * @param req           回答リクエスト
     * @param userId        回答者ID
     * @return 回答レスポンス
     */
    @Transactional
    public SafetyResponseResponse respond(Long safetyCheckId, RespondRequest req, Long userId) {
        SafetyCheckEntity check = findCheckOrThrow(safetyCheckId);
        validateActive(check);

        // 重複回答チェック
        responseRepository.findBySafetyCheckIdAndUserId(safetyCheckId, userId)
                .ifPresent(existing -> {
                    throw new BusinessException(SafetyCheckErrorCode.ALREADY_RESPONDED);
                });

        SafetyResponseStatus status = parseResponseStatus(req.getStatus());
        MessageSource messageSource = req.getMessageSource() != null
                ? MessageSource.valueOf(req.getMessageSource()) : null;

        SafetyResponseEntity entity = SafetyResponseEntity.builder()
                .safetyCheckId(safetyCheckId)
                .userId(userId)
                .status(status)
                .message(req.getMessage())
                .messageSource(messageSource)
                .gpsShared(req.getGpsShared() != null ? req.getGpsShared() : false)
                .gpsLatitude(req.getGpsLatitude())
                .gpsLongitude(req.getGpsLongitude())
                .respondedAt(LocalDateTime.now())
                .build();

        entity = responseRepository.save(entity);

        // NEED_SUPPORT の場合はフォローアップレコードを自動作成
        if (status == SafetyResponseStatus.NEED_SUPPORT) {
            createFollowup(entity.getId());
        }

        log.info("安否確認回答: safetyCheckId={}, userId={}, status={}", safetyCheckId, userId, status);
        return mapper.toSafetyResponseResponse(entity);
    }

    /**
     * 安否確認に一括回答する（管理者用）。
     *
     * @param safetyCheckId 安否確認ID
     * @param req           一括回答リクエスト
     * @return 回答レスポンス一覧
     */
    @Transactional
    public List<SafetyResponseResponse> bulkRespond(Long safetyCheckId, BulkRespondRequest req) {
        SafetyCheckEntity check = findCheckOrThrow(safetyCheckId);
        validateActive(check);

        if (req.getItems().size() > BULK_RESPOND_LIMIT) {
            throw new BusinessException(SafetyCheckErrorCode.BULK_RESPOND_LIMIT_EXCEEDED);
        }

        List<SafetyResponseResponse> results = new ArrayList<>();

        for (BulkRespondRequest.BulkRespondItem item : req.getItems()) {
            // 既存回答がある場合はスキップ
            if (responseRepository.findBySafetyCheckIdAndUserId(safetyCheckId, item.getUserId()).isPresent()) {
                continue;
            }

            SafetyResponseStatus status = parseResponseStatus(item.getStatus());

            SafetyResponseEntity entity = SafetyResponseEntity.builder()
                    .safetyCheckId(safetyCheckId)
                    .userId(item.getUserId())
                    .status(status)
                    .message(item.getMessage())
                    .respondedAt(LocalDateTime.now())
                    .build();

            entity = responseRepository.save(entity);

            if (status == SafetyResponseStatus.NEED_SUPPORT) {
                createFollowup(entity.getId());
            }

            results.add(mapper.toSafetyResponseResponse(entity));
        }

        log.info("安否確認一括回答: safetyCheckId={}, 登録数={}", safetyCheckId, results.size());
        return results;
    }

    // --- プライベートメソッド ---

    /**
     * 安否確認を取得する。存在しない場合は例外をスローする。
     */
    private SafetyCheckEntity findCheckOrThrow(Long id) {
        return safetyCheckRepository.findById(id)
                .orElseThrow(() -> new BusinessException(SafetyCheckErrorCode.SAFETY_CHECK_NOT_FOUND));
    }

    /**
     * アクティブ状態を検証する。
     */
    private void validateActive(SafetyCheckEntity entity) {
        if (entity.getStatus() == SafetyCheckStatus.CLOSED) {
            throw new BusinessException(SafetyCheckErrorCode.SAFETY_CHECK_ALREADY_CLOSED);
        }
    }

    /**
     * 回答ステータス文字列をEnumに変換する。
     */
    private SafetyResponseStatus parseResponseStatus(String status) {
        try {
            return SafetyResponseStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(SafetyCheckErrorCode.INVALID_RESPONSE_STATUS);
        }
    }

    /**
     * フォローアップレコードを作成する。
     */
    private void createFollowup(Long responseId) {
        SafetyResponseFollowupEntity followup = SafetyResponseFollowupEntity.builder()
                .safetyResponseId(responseId)
                .followupStatus(FollowupStatus.PENDING)
                .build();
        followupRepository.save(followup);
    }
}
