package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.SwapRequestStatus;
import com.mannschaft.app.shift.dto.CreateSwapRequestRequest;
import com.mannschaft.app.shift.dto.ResolveSwapRequestRequest;
import com.mannschaft.app.shift.dto.SwapRequestResponse;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * シフト交代リクエストサービス。メンバー間のシフト交代申請・承認フローを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftSwapService {

    private static final String ACTION_APPROVE = "APPROVE";
    private static final String ACTION_REJECT = "REJECT";

    private final ShiftSwapRequestRepository swapRepository;
    private final ShiftMapper shiftMapper;

    /**
     * 交代リクエスト一覧を取得する（管理者用）。
     *
     * @param status ステータスフィルタ（省略時は全件）
     * @return 交代リクエスト一覧
     */
    public List<SwapRequestResponse> listSwapRequests(String status) {
        List<ShiftSwapRequestEntity> entities;
        if (status != null) {
            entities = swapRepository.findByStatusOrderByCreatedAtAsc(SwapRequestStatus.valueOf(status));
        } else {
            entities = swapRepository.findAll();
        }
        return shiftMapper.toSwapResponseList(entities);
    }

    /**
     * 自分の交代リクエスト一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 交代リクエスト一覧
     */
    public List<SwapRequestResponse> listMySwapRequests(Long userId) {
        List<ShiftSwapRequestEntity> entities = swapRepository.findByRequesterIdOrderByCreatedAtDesc(userId);
        return shiftMapper.toSwapResponseList(entities);
    }

    /**
     * 交代リクエストを作成する。
     *
     * @param req    作成リクエスト
     * @param userId リクエスターID
     * @return 作成された交代リクエスト
     */
    @Transactional
    public SwapRequestResponse createSwapRequest(CreateSwapRequestRequest req, Long userId) {
        ShiftSwapRequestEntity entity = ShiftSwapRequestEntity.builder()
                .slotId(req.getSlotId())
                .requesterId(userId)
                .reason(req.getReason())
                .build();

        entity = swapRepository.save(entity);
        log.info("交代リクエスト作成: id={}, slotId={}, requesterId={}", entity.getId(), req.getSlotId(), userId);
        return shiftMapper.toSwapResponse(entity);
    }

    /**
     * 交代リクエストを承諾する（交代相手）。
     *
     * @param swapId     交代リクエストID
     * @param accepterId 承諾者ID
     * @return 更新された交代リクエスト
     */
    @Transactional
    public SwapRequestResponse acceptSwapRequest(Long swapId, Long accepterId) {
        ShiftSwapRequestEntity entity = findSwapOrThrow(swapId);
        validatePendingStatus(entity);

        if (entity.getRequesterId().equals(accepterId)) {
            throw new BusinessException(ShiftErrorCode.SWAP_SELF_REQUEST);
        }

        entity.accept(accepterId);
        entity = swapRepository.save(entity);

        log.info("交代リクエスト承諾: id={}, accepterId={}", swapId, accepterId);
        return shiftMapper.toSwapResponse(entity);
    }

    /**
     * 交代リクエストを承認・却下する（管理者）。
     *
     * @param swapId  交代リクエストID
     * @param req     承認・却下リクエスト
     * @param adminId 管理者ID
     * @return 更新された交代リクエスト
     */
    @Transactional
    public SwapRequestResponse resolveSwapRequest(Long swapId, ResolveSwapRequestRequest req, Long adminId) {
        ShiftSwapRequestEntity entity = findSwapOrThrow(swapId);

        if (entity.getStatus() != SwapRequestStatus.ACCEPTED) {
            throw new BusinessException(ShiftErrorCode.INVALID_SWAP_STATUS);
        }

        switch (req.getAction()) {
            case ACTION_APPROVE -> entity.approve(adminId, req.getAdminNote());
            case ACTION_REJECT -> entity.reject(adminId, req.getAdminNote());
            default -> throw new BusinessException(ShiftErrorCode.INVALID_SWAP_STATUS);
        }

        entity = swapRepository.save(entity);
        log.info("交代リクエスト処理: id={}, action={}", swapId, req.getAction());
        return shiftMapper.toSwapResponse(entity);
    }

    /**
     * 交代リクエストをキャンセルする。
     *
     * @param swapId 交代リクエストID
     * @param userId 操作者ID
     */
    @Transactional
    public void cancelSwapRequest(Long swapId, Long userId) {
        ShiftSwapRequestEntity entity = findSwapOrThrow(swapId);
        validatePendingStatus(entity);

        entity.cancel();
        swapRepository.save(entity);
        log.info("交代リクエストキャンセル: id={}", swapId);
    }

    /**
     * 交代リクエストを取得する。存在しない場合は例外をスローする。
     */
    private ShiftSwapRequestEntity findSwapOrThrow(Long id) {
        return swapRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SWAP_REQUEST_NOT_FOUND));
    }

    /**
     * PENDINGステータスであることを検証する。
     */
    private void validatePendingStatus(ShiftSwapRequestEntity entity) {
        if (entity.getStatus() != SwapRequestStatus.PENDING) {
            throw new BusinessException(ShiftErrorCode.INVALID_SWAP_STATUS);
        }
    }
}
