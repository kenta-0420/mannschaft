package com.mannschaft.app.receipt.service;

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

    /**
     * 発行待ちキュー一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param status    ステータスフィルタ（NULL の場合は全件）
     * @param page      ページ番号
     * @param size      取得件数
     * @return ページネーション付きキューアイテム一覧
     */
    public PagedResponse<QueueItemResponse> listQueue(ReceiptScopeType scopeType, Long scopeId,
                                                       ReceiptQueueStatus status, int page, int size) {
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

        if (queueItem.getStatus() != ReceiptQueueStatus.PENDING) {
            throw new BusinessException(ReceiptErrorCode.QUEUE_NOT_PENDING);
        }

        // TODO: ReceiptService.createReceipt を呼び出して領収書を発行
        // 承認リクエストのフィールドで suggested_description / suggested_amount を上書き
        queueItem.approve(null); // processedReceiptId は発行後に設定
        queueRepository.save(queueItem);

        log.info("キューアイテム承認: queueId={}", queueId);

        // TODO: 発行された領収書のレスポンスを返す
        return ReceiptResponse.builder().build();
    }

    /**
     * キューアイテムを一括承認する。
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
        if (request.getQueueIds().size() > 50) {
            throw new BusinessException(ReceiptErrorCode.BULK_LIMIT_EXCEEDED);
        }

        List<ReceiptQueueEntity> items = queueRepository.findByIdIn(request.getQueueIds());
        int approvedCount = 0;
        int skippedCount = 0;

        for (ReceiptQueueEntity item : items) {
            if (item.getStatus() != ReceiptQueueStatus.PENDING) {
                skippedCount++;
                continue;
            }
            // TODO: 領収書発行処理
            item.approve(null);
            queueRepository.save(item);
            approvedCount++;
        }

        log.info("キュー一括承認: scopeType={}, scopeId={}, approved={}, skipped={}",
                scopeType, scopeId, approvedCount, skippedCount);

        return BulkResultResponse.builder()
                .issuedCount(approvedCount)
                .skippedCount(skippedCount)
                .receipts(new ArrayList<>())
                .build();
    }

    /**
     * キューアイテムをスキップする。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param queueId   キューアイテムID
     */
    @Transactional
    public void skipQueueItem(ReceiptScopeType scopeType, Long scopeId, Long queueId) {
        ReceiptQueueEntity queueItem = findQueueItemOrThrow(scopeType, scopeId, queueId);

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
}
