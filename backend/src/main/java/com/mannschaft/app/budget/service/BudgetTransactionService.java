package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetApprovalStatus;
import com.mannschaft.app.budget.BudgetErrorCode;
import com.mannschaft.app.budget.BudgetFiscalYearStatus;
import com.mannschaft.app.budget.BudgetMapper;
import com.mannschaft.app.budget.BudgetPaymentMethod;
import com.mannschaft.app.budget.BudgetTransactionType;
import com.mannschaft.app.budget.dto.AttachmentResponse;
import com.mannschaft.app.budget.dto.CreateTransactionRequest;
import com.mannschaft.app.budget.dto.RegisterAttachmentRequest;
import com.mannschaft.app.budget.dto.ReverseTransactionRequest;
import com.mannschaft.app.budget.dto.TransactionDetailResponse;
import com.mannschaft.app.budget.dto.TransactionResponse;
import com.mannschaft.app.budget.dto.UploadUrlResponse;
import com.mannschaft.app.budget.dto.UserSummary;
import com.mannschaft.app.budget.entity.BudgetTransactionAttachmentEntity;
import com.mannschaft.app.budget.entity.BudgetCategoryEntity;
import com.mannschaft.app.budget.entity.BudgetConfigEntity;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.budget.event.BudgetTransactionCreatedEvent;
import com.mannschaft.app.budget.event.BudgetTransactionReversedEvent;
import com.mannschaft.app.budget.repository.BudgetTransactionAttachmentRepository;
import com.mannschaft.app.budget.repository.BudgetConfigRepository;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * 予算取引サービス。取引のCRUD・承認・取消・自動記帳を担当する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BudgetTransactionService {

    private final BudgetTransactionRepository transactionRepository;
    private final BudgetTransactionAttachmentRepository attachmentRepository;
    private final BudgetConfigRepository configRepository;
    private final BudgetFiscalYearService fiscalYearService;
    private final BudgetCategoryService categoryService;
    private final BudgetMapper budgetMapper;
    private final AccessControlService accessControlService;
    private final DomainEventPublisher domainEventPublisher;
    private final StorageService storageService;

    private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(15);

    /**
     * 取引を作成する。承認閾値を超える支出の場合はPENDING_APPROVALとなる。
     */
    @Transactional
    public TransactionResponse create(CreateTransactionRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        BudgetFiscalYearEntity fy = fiscalYearService.findById(request.fiscalYearId());
        accessControlService.checkMembership(currentUserId, fy.getScopeId(), fy.getScopeType());

        if (fy.getStatus() != BudgetFiscalYearStatus.OPEN) {
            throw new BusinessException(BudgetErrorCode.BUDGET_004);
        }

        BudgetCategoryEntity category = categoryService.findById(request.categoryId());
        BudgetTransactionType txType = BudgetTransactionType.valueOf(request.transactionType());

        // 承認閾値チェック（支出のみ）
        BudgetApprovalStatus approvalStatus = determineApprovalStatus(
                txType, request.amount(), fy.getScopeId(), fy.getScopeType());

        BudgetTransactionEntity entity = BudgetTransactionEntity.builder()
                .fiscalYearId(request.fiscalYearId())
                .categoryId(request.categoryId())
                .scopeType(fy.getScopeType())
                .scopeId(fy.getScopeId())
                .transactionType(txType)
                .amount(request.amount())
                .transactionDate(request.transactionDate())
                .title(request.description())
                .description(request.memo())
                .paymentMethod(request.paymentMethod())
                .referenceNumber(request.reference())
                .approvalStatus(approvalStatus)
                .recordedBy(currentUserId)
                .build();

        BudgetTransactionEntity saved = transactionRepository.save(entity);

        // ドメインイベント発行
        domainEventPublisher.publish(new BudgetTransactionCreatedEvent(
                saved.getId(), saved.getFiscalYearId(), saved.getCategoryId(),
                saved.getAmount(), saved.getTransactionType().name(),
                fy.getScopeId(), fy.getScopeType(), currentUserId));

        log.info("取引を作成しました: id={}, type={}, amount={}, status={}",
                saved.getId(), txType, request.amount(), approvalStatus);

        return toTransactionResponse(saved, category);
    }

    /**
     * 取引をIDで取得する。
     */
    public TransactionDetailResponse getById(Long id, Long scopeId, String scopeType) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(currentUserId, scopeId, scopeType);

        BudgetTransactionEntity entity = findById(id);
        BudgetCategoryEntity category = categoryService.findById(entity.getCategoryId());
        List<AttachmentResponse> attachments = attachmentRepository.findByTransactionId(id)
                .stream()
                .map(budgetMapper::toAttachmentResponse)
                .toList();

        return new TransactionDetailResponse(
                entity.getId(),
                entity.getFiscalYearId(),
                entity.getCategoryId(),
                category.getName(),
                entity.getTransactionType().name(),
                entity.getAmount(),
                entity.getTransactionDate(),
                entity.getTitle(),
                entity.getPaymentMethod(),
                entity.getReferenceNumber(),
                entity.getDescription(),
                entity.getApprovalStatus().name(),
                entity.getReversalOfId(),
                new UserSummary(entity.getRecordedBy(), null),
                null,
                attachments,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    /**
     * スコープ内の取引一覧をページング取得する。
     */
    public PagedResponse<TransactionResponse> listByScope(Long fiscalYearId, Pageable pageable) {
        BudgetFiscalYearEntity fy = fiscalYearService.findById(fiscalYearId);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(currentUserId, fy.getScopeId(), fy.getScopeType());

        Page<BudgetTransactionEntity> page = transactionRepository.findByFiscalYearId(fiscalYearId, pageable);

        List<TransactionResponse> data = page.getContent().stream()
                .map(entity -> {
                    BudgetCategoryEntity category = categoryService.findById(entity.getCategoryId());
                    return toTransactionResponse(entity, category);
                })
                .toList();

        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return PagedResponse.of(data, meta);
    }

    /**
     * 取引を取消（反転仕訳）する。
     */
    @Transactional
    public TransactionResponse reverse(Long id, ReverseTransactionRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        BudgetTransactionEntity original = findById(id);
        BudgetFiscalYearEntity fy = fiscalYearService.findById(original.getFiscalYearId());
        accessControlService.checkAdminOrAbove(currentUserId, fy.getScopeId(), fy.getScopeType());

        if (fy.getStatus() != BudgetFiscalYearStatus.OPEN) {
            throw new BusinessException(BudgetErrorCode.BUDGET_004);
        }
        if (original.getReversalOfId() != null) {
            throw new BusinessException(BudgetErrorCode.BUDGET_007);
        }

        // 反転仕訳を作成
        BudgetTransactionEntity reversal = BudgetTransactionEntity.builder()
                .fiscalYearId(original.getFiscalYearId())
                .categoryId(original.getCategoryId())
                .scopeType(original.getScopeType())
                .scopeId(original.getScopeId())
                .transactionType(
                        original.getTransactionType() == BudgetTransactionType.INCOME
                                ? BudgetTransactionType.EXPENSE : BudgetTransactionType.INCOME)
                .amount(original.getAmount())
                .transactionDate(LocalDate.now())
                .title("[取消] " + original.getTitle() + " - " + request.reason())
                .approvalStatus(BudgetApprovalStatus.APPROVED)
                .reversalOfId(original.getId())
                .recordedBy(currentUserId)
                .build();

        BudgetTransactionEntity savedReversal = transactionRepository.save(reversal);

        // ドメインイベント発行
        domainEventPublisher.publish(new BudgetTransactionReversedEvent(
                original.getId(), savedReversal.getId(), fy.getId(),
                original.getAmount(), fy.getScopeId(), fy.getScopeType(), currentUserId));

        BudgetCategoryEntity category = categoryService.findById(reversal.getCategoryId());
        log.info("取引を取消しました: originalId={}, reversalId={}", id, savedReversal.getId());
        return toTransactionResponse(savedReversal, category);
    }

    /**
     * 取引を削除する。
     */
    @Transactional
    public void delete(Long id, Long scopeId, String scopeType) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, scopeId, scopeType);

        BudgetTransactionEntity entity = findById(id);
        transactionRepository.delete(entity);
        log.info("取引を削除しました: id={}", id);
    }

    /**
     * 取引を承認する（ワークフローからの呼出し）。
     */
    @Transactional
    public void approveTransaction(Long transactionId, Long approvedByUserId) {
        BudgetTransactionEntity entity = findById(transactionId);
        if (entity.getApprovalStatus() != BudgetApprovalStatus.PENDING_APPROVAL) {
            throw new BusinessException(BudgetErrorCode.BUDGET_008);
        }

        entity.approve();
        transactionRepository.save(entity);
        log.info("取引を承認しました: id={}, approvedBy={}", transactionId, approvedByUserId);
    }

    /**
     * 取引を却下する（ワークフローからの呼出し）。
     */
    @Transactional
    public void rejectTransaction(Long transactionId, Long rejectedByUserId) {
        BudgetTransactionEntity entity = findById(transactionId);
        if (entity.getApprovalStatus() != BudgetApprovalStatus.PENDING_APPROVAL) {
            throw new BusinessException(BudgetErrorCode.BUDGET_008);
        }

        entity.reject();
        transactionRepository.save(entity);
        log.info("取引を却下しました: id={}, rejectedBy={}", transactionId, rejectedByUserId);
    }

    /**
     * 決済完了時に収入を自動記帳する（F08.2 連携）。
     * source_type + source_id による冪等性を保証する。
     */
    @Transactional
    public void autoRecordPaymentIncome(Long scopeId, String scopeType,
                                         BigDecimal amount, String description, String paymentMethod,
                                         Long paymentId) {
        // 冪等性チェック: 同一決済からの重複記帳を防止
        if (!transactionRepository.findBySourceTypeAndSourceId("MEMBER_PAYMENT", paymentId).isEmpty()) {
            log.info("同一決済からの記帳済み、スキップ: paymentId={}", paymentId);
            return;
        }

        // スコープに紐づくOPENの会計年度を取得
        List<BudgetFiscalYearEntity> fiscalYears = fiscalYearService.listByScope(scopeType, scopeId).stream()
                .map(fy -> fiscalYearService.findById(fy.id()))
                .filter(fy -> fy.getStatus() == BudgetFiscalYearStatus.OPEN)
                .toList();

        if (fiscalYears.isEmpty()) {
            log.warn("自動記帳対象のOPEN会計年度が見つかりません: scopeId={}, scopeType={}", scopeId, scopeType);
            return;
        }

        BudgetFiscalYearEntity fy = fiscalYears.getFirst();

        // 収入カテゴリの最初のものに記帳（設定に応じて変更可能）
        List<BudgetCategoryEntity> incomeCategories = categoryService.listFlatByFiscalYear(fy.getId()).stream()
                .map(c -> categoryService.findById(c.id()))
                .filter(c -> c.getCategoryType() == com.mannschaft.app.budget.BudgetCategoryType.INCOME)
                .toList();

        if (incomeCategories.isEmpty()) {
            log.warn("自動記帳対象の収入カテゴリが見つかりません: fiscalYearId={}", fy.getId());
            return;
        }

        BudgetCategoryEntity category = incomeCategories.getFirst();

        BudgetTransactionEntity entity = BudgetTransactionEntity.builder()
                .fiscalYearId(fy.getId())
                .categoryId(category.getId())
                .scopeType(scopeType)
                .scopeId(scopeId)
                .transactionType(BudgetTransactionType.INCOME)
                .amount(amount)
                .transactionDate(LocalDate.now())
                .title("[自動記帳] " + description)
                .approvalStatus(BudgetApprovalStatus.APPROVED)
                .paymentMethod(paymentMethod)
                .isAutoRecorded(true)
                .sourceType("MEMBER_PAYMENT")
                .sourceId(paymentId)
                .recordedBy(0L)
                .build();

        transactionRepository.save(entity);
        log.info("決済収入を自動記帳しました: fiscalYearId={}, paymentId={}, amount={}", fy.getId(), paymentId, amount);
    }

    /**
     * 添付ファイルアップロードURLを生成する。
     */
    public UploadUrlResponse generateUploadUrl(Long transactionId, String fileName, String contentType) {
        String s3Key = "budget/attachments/" + transactionId + "/" + System.currentTimeMillis() + "_" + fileName;
        PresignedUploadResult result = storageService.generateUploadUrl(s3Key, contentType, UPLOAD_URL_TTL);
        return new UploadUrlResponse(result.uploadUrl(), result.s3Key(), result.expiresInSeconds());
    }

    /**
     * 添付ファイルメタデータを登録する。
     */
    @Transactional
    public AttachmentResponse registerAttachment(RegisterAttachmentRequest request) {
        findById(request.transactionId()); // 存在確認

        BudgetTransactionAttachmentEntity entity = BudgetTransactionAttachmentEntity.builder()
                .transactionId(request.transactionId())
                .originalFilename(request.fileName())
                .mimeType(request.fileType())
                .fileSize(request.fileSize())
                .fileKey(request.s3Key())
                .build();

        BudgetTransactionAttachmentEntity saved = attachmentRepository.save(entity);
        return budgetMapper.toAttachmentResponse(saved);
    }

    // ========================================
    // ヘルパー
    // ========================================

    BudgetTransactionEntity findById(Long id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(BudgetErrorCode.BUDGET_009));
    }

    private BudgetApprovalStatus determineApprovalStatus(BudgetTransactionType txType,
                                                          BigDecimal amount,
                                                          Long scopeId, String scopeType) {
        // 収入は即時承認
        if (txType == BudgetTransactionType.INCOME) {
            return BudgetApprovalStatus.APPROVED;
        }

        // 設定から承認閾値を取得
        return configRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .map(config -> {
                    if (config.getAutoRecordPayments()
                            && amount.compareTo(config.getApprovalThreshold()) <= 0) {
                        return BudgetApprovalStatus.APPROVED;
                    }
                    return BudgetApprovalStatus.PENDING_APPROVAL;
                })
                .orElse(BudgetApprovalStatus.APPROVED); // 設定なしの場合は自動承認
    }

    private TransactionResponse toTransactionResponse(BudgetTransactionEntity entity,
                                                       BudgetCategoryEntity category) {
        return new TransactionResponse(
                entity.getId(),
                entity.getFiscalYearId(),
                entity.getCategoryId(),
                category.getName(),
                entity.getTransactionType().name(),
                entity.getAmount(),
                entity.getTransactionDate(),
                entity.getTitle(),
                entity.getPaymentMethod(),
                entity.getReferenceNumber(),
                entity.getApprovalStatus().name(),
                new UserSummary(entity.getRecordedBy(), null),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
