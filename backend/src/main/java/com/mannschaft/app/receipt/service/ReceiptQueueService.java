package com.mannschaft.app.receipt.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.receipt.ReceiptErrorCode;
import com.mannschaft.app.receipt.ReceiptMapper;
import com.mannschaft.app.receipt.ReceiptQueueStatus;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.dto.ApproveQueueRequest;
import com.mannschaft.app.receipt.dto.BulkApproveQueueRequest;
import com.mannschaft.app.receipt.dto.BulkResultResponse;
import com.mannschaft.app.receipt.dto.QueueItemResponse;
import com.mannschaft.app.receipt.dto.ReceiptResponse;
import com.mannschaft.app.receipt.entity.ReceiptQueueEntity;
import com.mannschaft.app.receipt.repository.ReceiptQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mannschaft.app.receipt.dto.CreateReceiptRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * 領収書キューサービス。発行待ちキューの管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReceiptQueueService {

    private final ReceiptQueueRepository queueRepository;
    private final ReceiptMapper receiptMapper;
    private final ReceiptService receiptService;
    private final AccessControlService accessControlService;

    /**
     * 発行待ちキュー一覧を取得する。
     * 認可: 指定スコープの ADMIN/DEPUTY_ADMIN のみ閲覧可能（発行前の受領者PII・金額を含む内部ワークリストのため）。
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param status      ステータスフィルタ（NULL の場合は全件）
     * @param page        ページ番号
     * @param size        取得件数
     * @param actorUserId 操作者ユーザーID
     * @return ページネーション付きキューアイテム一覧
     */
    public PagedResponse<QueueItemResponse> listQueue(ReceiptScopeType scopeType, Long scopeId,
                                                       ReceiptQueueStatus status, int page, int size,
                                                       Long actorUserId) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType.name());

        Pageable pageable = PageRequest.of(page, size);
        Page<ReceiptQueueEntity> queuePage;

        if (status != null) {
            queuePage = queueRepository.findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                    scopeType, scopeId, status, pageable);
        } else {
            queuePage = queueRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    scopeType, scopeId, pageable);
        }

        List<QueueItemResponse> data = receiptMapper.toQueueItemResponseList(queuePage.getContent());
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                queuePage.getTotalElements(), page, size, queuePage.getTotalPages());

        return PagedResponse.of(data, meta);
    }

    /**
     * キューアイテムを承認して領収書を発行する。
     * 認可: キューアイテムが実在するスコープ（entity由来）の ADMIN/DEPUTY_ADMIN のみ承認可能。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param queueId   キューアイテムID
     * @param userId    承認者ユーザーID
     * @param request   承認リクエスト
     * @return 発行された領収書レスポンス
     */
    @Transactional
    public ReceiptResponse approveQueueItem(ReceiptScopeType scopeType, Long scopeId,
                                             Long queueId, Long userId, ApproveQueueRequest request) {
        ReceiptQueueEntity queueItem = findQueueItemOrThrow(scopeType, scopeId, queueId);
        accessControlService.checkAdminOrAbove(userId, queueItem.getScopeId(), queueItem.getScopeType().name());

        if (queueItem.getStatus() != ReceiptQueueStatus.PENDING) {
            throw new BusinessException(ReceiptErrorCode.QUEUE_NOT_PENDING);
        }

        CreateReceiptRequest createRequest = buildCreateRequestFromQueue(queueItem, request);
        ReceiptResponse receiptResponse = receiptService.createReceipt(scopeType, scopeId, userId, createRequest);

        queueItem.approve(receiptResponse.getId());
        queueRepository.save(queueItem);

        log.info("キューアイテム承認: queueId={}, receiptId={}", queueId, receiptResponse.getId());
        return receiptResponse;
    }

    /**
     * キューアイテムを一括承認する。
     * 認可: 指定スコープの ADMIN/DEPUTY_ADMIN のみ承認可能。
     *
     * <p><b>BOLA根治（根治治療）</b>: 旧実装は {@code queueRepository.findByIdIn(...)} で
     * スコープを一切考慮せずキューアイテムを取得していたため、あるスコープの ADMIN が
     * 別スコープのキューIDを紛れ込ませると、認可チェックは自スコープに対してのみ行われる一方、
     * 実際の発行処理は各アイテム自身が持つ別スコープに対して実行されてしまう BOLA（越境発行）が
     * 可能だった。{@code findByIdInAndScopeTypeAndScopeId} で取得段階からスコープ外を排除し、
     * 取得できたアイテムは全て指定スコープに属することを保証する。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    承認者ユーザーID
     * @param request   一括承認リクエスト
     * @return 一括承認結果レスポンス
     */
    @Transactional
    public BulkResultResponse bulkApproveQueue(ReceiptScopeType scopeType, Long scopeId,
                                                Long userId, BulkApproveQueueRequest request) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());

        if (request.getQueueIds().size() > 50) {
            throw new BusinessException(ReceiptErrorCode.BULK_LIMIT_EXCEEDED);
        }

        List<ReceiptQueueEntity> items = queueRepository.findByIdInAndScopeTypeAndScopeId(
                request.getQueueIds(), scopeType, scopeId);
        List<BulkResultResponse.IssuedReceipt> receipts = new ArrayList<>();
        int approvedCount = 0;
        int skippedCount = 0;

        for (ReceiptQueueEntity item : items) {
            if (item.getStatus() != ReceiptQueueStatus.PENDING) {
                skippedCount++;
                continue;
            }
            CreateReceiptRequest createRequest = buildCreateRequestFromQueue(item, null);
            ReceiptResponse receiptResponse = receiptService.createReceipt(
                    item.getScopeType(), item.getScopeId(), userId, createRequest);
            item.approve(receiptResponse.getId());
            queueRepository.save(item);
            receipts.add(BulkResultResponse.IssuedReceipt.builder()
                    .id(receiptResponse.getId())
                    .receiptNumber(receiptResponse.getReceiptNumber())
                    .recipientName(receiptResponse.getRecipientName())
                    .amount(receiptResponse.getAmount())
                    .build());
            approvedCount++;
        }

        log.info("キュー一括承認: scopeType={}, scopeId={}, approved={}, skipped={}",
                scopeType, scopeId, approvedCount, skippedCount);

        return BulkResultResponse.builder()
                .issuedCount(approvedCount)
                .skippedCount(skippedCount)
                .receipts(receipts)
                .build();
    }

    /**
     * キューアイテムをスキップする。
     * 認可: キューアイテムが実在するスコープ（entity由来）の ADMIN/DEPUTY_ADMIN のみ可能。
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param queueId     キューアイテムID
     * @param actorUserId 操作者ユーザーID
     */
    @Transactional
    public void skipQueueItem(ReceiptScopeType scopeType, Long scopeId, Long queueId, Long actorUserId) {
        ReceiptQueueEntity queueItem = findQueueItemOrThrow(scopeType, scopeId, queueId);
        accessControlService.checkAdminOrAbove(actorUserId, queueItem.getScopeId(), queueItem.getScopeType().name());

        if (queueItem.getStatus() != ReceiptQueueStatus.PENDING) {
            throw new BusinessException(ReceiptErrorCode.QUEUE_NOT_PENDING);
        }

        queueItem.skip();
        queueRepository.save(queueItem);
        log.info("キューアイテムスキップ: queueId={}", queueId);
    }

    /**
     * キューアイテムエンティティを取得する。存在しない場合は例外をスローする。
     */
    ReceiptQueueEntity findQueueItemOrThrow(ReceiptScopeType scopeType, Long scopeId, Long queueId) {
        return queueRepository.findByIdAndScopeTypeAndScopeId(queueId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ReceiptErrorCode.QUEUE_ITEM_NOT_FOUND));
    }

    /**
     * キューアイテムから領収書発行リクエストを構築する。
     * 承認リクエストで上書きされたフィールドがあればそちらを優先する。
     */
    private CreateReceiptRequest buildCreateRequestFromQueue(ReceiptQueueEntity queueItem,
                                                              ApproveQueueRequest approveRequest) {
        String description = queueItem.getSuggestedDescription();
        java.math.BigDecimal amount = queueItem.getSuggestedAmount();
        Boolean sealStamp = null;

        if (approveRequest != null) {
            if (approveRequest.getDescription() != null) {
                description = approveRequest.getDescription();
            }
            if (approveRequest.getAmount() != null) {
                amount = approveRequest.getAmount();
            }
            sealStamp = approveRequest.getSealStamp();
        }

        return new CreateReceiptRequest(
                queueItem.getPresetId(),
                null,
                queueItem.getMemberPaymentId(),
                queueItem.getRecipientUserId(),
                null,
                null,
                null,
                description,
                amount,
                null,
                null,
                null,
                null,
                sealStamp,
                null,
                null
        );
    }
}
