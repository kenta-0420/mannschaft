package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.ContentGateSetResponse;
import com.mannschaft.app.payment.dto.ContentPaymentGateRequest;
import com.mannschaft.app.payment.dto.ContentPaymentGateResponse;
import com.mannschaft.app.payment.entity.ContentPaymentGateEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.ContentPaymentGateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * コンテンツゲートサービス。コンテンツ単位のアクセスゲート設定・取得を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentPaymentGateService {

    private final ContentPaymentGateRepository contentPaymentGateRepository;
    private final PaymentItemService paymentItemService;

    /**
     * チーム内のコンテンツゲート一覧を取得する。
     */
    public Page<ContentPaymentGateResponse> listTeamContentGates(Long teamId, String contentType, Pageable pageable) {
        List<Long> paymentItemIds = paymentItemService.findTeamPaymentItems(teamId).stream()
                .map(PaymentItemEntity::getId)
                .toList();

        if (paymentItemIds.isEmpty()) {
            return Page.empty(pageable);
        }

        Page<ContentPaymentGateEntity> page;
        if (contentType != null) {
            page = contentPaymentGateRepository.findByPaymentItemIdInAndContentType(
                    paymentItemIds, contentType, pageable);
        } else {
            page = contentPaymentGateRepository.findByPaymentItemIdIn(paymentItemIds, pageable);
        }

        return page.map(this::toResponse);
    }

    /**
     * 組織内のコンテンツゲート一覧を取得する。
     */
    public Page<ContentPaymentGateResponse> listOrganizationContentGates(Long organizationId, String contentType,
                                                                         Pageable pageable) {
        List<Long> paymentItemIds = paymentItemService.findOrganizationPaymentItems(organizationId).stream()
                .map(PaymentItemEntity::getId)
                .toList();

        if (paymentItemIds.isEmpty()) {
            return Page.empty(pageable);
        }

        Page<ContentPaymentGateEntity> page;
        if (contentType != null) {
            page = contentPaymentGateRepository.findByPaymentItemIdInAndContentType(
                    paymentItemIds, contentType, pageable);
        } else {
            page = contentPaymentGateRepository.findByPaymentItemIdIn(paymentItemIds, pageable);
        }

        return page.map(this::toResponse);
    }

    /**
     * コンテンツゲートを一括設定する（チーム用）。
     */
    @Transactional
    public ContentGateSetResponse setTeamContentGates(Long teamId, Long userId,
                                                       ContentPaymentGateRequest request) {
        return setContentGates(teamId, null, userId, request);
    }

    /**
     * コンテンツゲートを一括設定する（組織用）。
     */
    @Transactional
    public ContentGateSetResponse setOrganizationContentGates(Long organizationId, Long userId,
                                                               ContentPaymentGateRequest request) {
        return setContentGates(null, organizationId, userId, request);
    }

    private ContentGateSetResponse setContentGates(Long teamId, Long organizationId, Long userId,
                                                    ContentPaymentGateRequest request) {
        // content_type バリデーション
        if (!ContentGateType.isSupported(request.getContentType())) {
            throw new BusinessException(PaymentErrorCode.UNSUPPORTED_CONTENT_TYPE);
        }

        // 各 payment_item_id の検証
        List<PaymentItemEntity> paymentItems = request.getGates().stream()
                .map(gate -> {
                    PaymentItemEntity item = paymentItemService.findByIdOrThrow(gate.getPaymentItemId());

                    // スコープ検証
                    if (teamId != null && !teamId.equals(item.getTeamId())) {
                        throw new BusinessException(PaymentErrorCode.PAYMENT_ITEM_SCOPE_MISMATCH);
                    }
                    if (organizationId != null && !organizationId.equals(item.getOrganizationId())) {
                        throw new BusinessException(PaymentErrorCode.PAYMENT_ITEM_SCOPE_MISMATCH);
                    }

                    // DONATION チェック
                    if (item.getType() == PaymentItemType.DONATION) {
                        throw new BusinessException(PaymentErrorCode.DONATION_NOT_ALLOWED_FOR_ACCESS);
                    }

                    // 論理削除チェック
                    if (item.getDeletedAt() != null) {
                        throw new BusinessException(PaymentErrorCode.PAYMENT_ITEM_DELETED);
                    }

                    return item;
                })
                .toList();

        // 既存を全削除して再作成
        contentPaymentGateRepository.deleteByContentTypeAndContentId(
                request.getContentType(), request.getContentId());

        List<ContentGateSetResponse.GateItem> gateItems = new java.util.ArrayList<>();

        for (int i = 0; i < request.getGates().size(); i++) {
            ContentPaymentGateRequest.GateEntry gate = request.getGates().get(i);
            PaymentItemEntity item = paymentItems.get(i);

            ContentPaymentGateEntity entity = ContentPaymentGateEntity.builder()
                    .paymentItemId(gate.getPaymentItemId())
                    .contentType(request.getContentType())
                    .contentId(request.getContentId())
                    .isTitleHidden(gate.getIsTitleHidden() != null ? gate.getIsTitleHidden() : false)
                    .createdBy(userId)
                    .build();

            ContentPaymentGateEntity saved = contentPaymentGateRepository.save(entity);
            gateItems.add(new ContentGateSetResponse.GateItem(
                    saved.getId(), gate.getPaymentItemId(), item.getName(), saved.getIsTitleHidden()));
        }

        log.info("コンテンツゲート設定: contentType={}, contentId={}, gates={}",
                request.getContentType(), request.getContentId(), gateItems.size());
        return new ContentGateSetResponse(request.getContentType(), request.getContentId(), gateItems);
    }

    private ContentPaymentGateResponse toResponse(ContentPaymentGateEntity entity) {
        PaymentItemEntity item = paymentItemService.findByIdOrThrow(entity.getPaymentItemId());
        return new ContentPaymentGateResponse(
                entity.getId(),
                entity.getContentType(),
                entity.getContentId(),
                entity.getIsTitleHidden(),
                new ContentPaymentGateResponse.PaymentItemDetail(
                        item.getId(), item.getName(), item.getType().name(),
                        item.getAmount(), item.getCurrency()),
                entity.getCreatedBy(),
                entity.getCreatedAt()
        );
    }
}
