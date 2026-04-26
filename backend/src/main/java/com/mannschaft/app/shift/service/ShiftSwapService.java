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
     * オープンコール交代リクエストを作成する（is_open_call=true で作成）。
     *
     * @param slotId   対象シフト枠ID
     * @param reason   理由
     * @param userId   依頼者ユーザーID
     * @return 作成されたオープンコール交代リクエスト
     */
    @Transactional
    public SwapRequestResponse createOpenCall(Long slotId, String reason, Long userId) {
        ShiftSwapRequestEntity entity = ShiftSwapRequestEntity.builder()
                .slotId(slotId)
                .requesterId(userId)
                .reason(reason)
                .isOpenCall(true)
                .status(SwapRequestStatus.OPEN_CALL)
                .build();

        entity = swapRepository.save(entity);
        log.info("オープンコール作成: id={}, slotId={}, requesterId={}", entity.getId(), slotId, userId);
        return shiftMapper.toSwapResponse(entity);
    }

    /**
     * オープンコールに手を挙げる（先着1名、楽観ロック）。
     *
     * @param swapRequestId オープンコールの交代リクエストID
     * @param userId        手挙げユーザーID
     * @return 更新された交代リクエスト
     */
    @Transactional
    public SwapRequestResponse claimOpenCall(Long swapRequestId, Long userId) {
        ShiftSwapRequestEntity entity = findSwapOrThrow(swapRequestId);

        if (!Boolean.TRUE.equals(entity.getIsOpenCall())) {
            throw new BusinessException(ShiftErrorCode.NOT_OPEN_CALL);
        }

        if (entity.getStatus() != SwapRequestStatus.OPEN_CALL) {
            throw new BusinessException(ShiftErrorCode.OPEN_CALL_ALREADY_CLAIMED);
        }

        if (entity.getRequesterId().equals(userId)) {
            throw new BusinessException(ShiftErrorCode.SWAP_SELF_REQUEST);
        }

        entity.claim(userId);
        entity = swapRepository.save(entity);

        log.info("オープンコール手挙げ: id={}, claimedBy={}", swapRequestId, userId);
        return shiftMapper.toSwapResponse(entity);
    }

    /**
     * オープンコールの候補者を選定して承諾済みにする（申請者または ADMIN のみ）。
     *
     * @param swapRequestId オープンコールの交代リクエストID
     * @param claimedBy     選定する手挙げユーザーID
     * @param actorId       操作者ユーザーID
     * @return 更新された交代リクエスト
     */
    @Transactional
    public SwapRequestResponse selectClaimer(Long swapRequestId, Long claimedBy, Long actorId) {
        ShiftSwapRequestEntity entity = findSwapOrThrow(swapRequestId);

        if (!Boolean.TRUE.equals(entity.getIsOpenCall())) {
            throw new BusinessException(ShiftErrorCode.NOT_OPEN_CALL);
        }

        if (entity.getStatus() != SwapRequestStatus.CLAIMED) {
            throw new BusinessException(ShiftErrorCode.INVALID_SWAP_STATUS);
        }

        // 申請者本人または管理者のみ（権限チェックはコントローラーにも委ねるが二重防御）
        if (!entity.getRequesterId().equals(actorId)) {
            throw new BusinessException(ShiftErrorCode.CLAIMER_SELECT_DENIED);
        }

        entity.selectClaimer(claimedBy);
        entity = swapRepository.save(entity);

        log.info("オープンコール候補者選定: id={}, claimedBy={}, actorId={}", swapRequestId, claimedBy, actorId);
        return shiftMapper.toSwapResponse(entity);
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
