package com.mannschaft.app.payment.service;

import com.mannschaft.app.auth.dto.SwitchableChildDto;
import com.mannschaft.app.auth.dto.SwitchableChildrenResponse;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.payment.PayerRelationship;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.payment.dto.BulkCheckoutRequest;
import com.mannschaft.app.payment.dto.BulkCheckoutResponse;
import com.mannschaft.app.payment.dto.BulkCheckoutResultItem;
import com.mannschaft.app.payment.dto.PayableDueItem;
import com.mannschaft.app.payment.dto.PayableDuesResponse;
import com.mannschaft.app.payment.dto.PaymentRequirementResponse;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * F08.9 P2: 後見まとめ払いサービス（払い手 ≠ 受益者の一括決済・設計書 02_api_design §1.2）。
 *
 * <p>保護者（払い手）が「本人＋後見下の子」の未払い会費を 1 画面で把握し、選択分をまとめて決済起票するための
 * サービス。受益者の集約は後見切替の権原と同じ {@link GuardianshipSwitchService#listSwitchableChildren} を再利用し、
 * 各明細の最終的な決済権原は {@link PaymentAuthorizationService#authorizePayment} が毎回実行時評価する
 * （権原のない受益者の明細は一切返さない・IDOR 防止）。</p>
 *
 * <p><b>ドメイン境界:</b> 受益者の集約は auth ドメインの {@link GuardianshipSwitchService} 公開メソッド経由
 * （Entity 直接参照なし）。会費要件・支払い済み判定・決済起票は payment ドメイン自身の Service/Repository に閉じる。
 * 起票本体（Connect 即時 charge・PENDING 起票）は既存の {@link MemberPaymentService#createConnectCheckout} を
 * そのまま再利用し、本サービスは「受益者の集約」と「部分成功のオーケストレーション」のみを担う。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PayableDuesService {

    private final MemberPaymentRepository memberPaymentRepository;
    private final PaymentAuthorizationService paymentAuthorizationService;
    private final PaymentRequirementService paymentRequirementService;
    private final PaymentItemService paymentItemService;
    private final MemberPaymentService memberPaymentService;
    private final GuardianshipSwitchService guardianshipSwitchService;
    private final NameResolverService nameResolverService;

    /**
     * 払い手が払える受益者（本人＋後見下の子）の未払い会費一覧を返す（設計書 02 §1.2）。
     *
     * <p>権原のない受益者の明細は含まない（IDOR 防止）。本人は常に SELF 権原で含まれる。子は後見切替可能な
     * （年齢ゲートを満たす）子のみを対象とする（封印された子は決済代理もできないため除外）。</p>
     *
     * @param payerUserId 払い手（保護者・本人＝認証ユーザー）のユーザーID
     * @return 払える未払い会費明細の一覧
     */
    public PayableDuesResponse getPayableDues(Long payerUserId) {
        List<Long> beneficiaryIds = collectBeneficiaryIds(payerUserId);

        List<PayableDueItem> items = new ArrayList<>();
        for (Long beneficiaryId : beneficiaryIds) {
            items.addAll(buildItemsForBeneficiary(payerUserId, beneficiaryId));
        }
        return new PayableDuesResponse(items);
    }

    /**
     * 選択した複数の会費項目を 1 リクエストでまとめて決済起票する（部分成功・設計書 02 §1.2）。
     *
     * <p>各項目の起票直前に再認可（{@link PaymentAuthorizationService#authorizePayment}）と支払い済み判定を行い、
     * 権原失効・支払い済み・受領口座未 READY はスキップして {@link BulkCheckoutResultItem} に理由を記録する
     * （症状を隠さず可視化する）。起票本体は既存の {@link MemberPaymentService#createConnectCheckout} を再利用し、
     * 受益者ごとの destination 振り分け・冪等性は同メソッドに委譲する。</p>
     *
     * @param payerUserId 払い手（認証ユーザー）のユーザーID
     * @param req         受益者 ID・会費項目 ID 一覧
     * @return 各項目の処理結果（リクエスト順）
     */
    public BulkCheckoutResponse bulkCheckout(Long payerUserId, BulkCheckoutRequest req) {
        Long beneficiaryId = req.beneficiaryUserId();

        List<BulkCheckoutResultItem> results = new ArrayList<>();
        for (Long paymentItemId : req.paymentItemIds()) {
            results.add(processOneItem(payerUserId, beneficiaryId, paymentItemId));
        }
        return new BulkCheckoutResponse(results);
    }

    // ---- private helpers ----

    /**
     * 払い手が払える受益者 ID を集約する（本人＋後見切替可能な子）。
     *
     * <p>本人は常に含む。子は {@link GuardianshipSwitchService#listSwitchableChildren} の
     * {@code children}（年齢ゲートを満たす切替可能な子）のみを採る。封印された子（{@code blockedChildren}）は
     * 決済も代理できないため含めない。順序は本人 → 子（重複は子側を捨てる）。</p>
     */
    private List<Long> collectBeneficiaryIds(Long payerUserId) {
        List<Long> ids = new ArrayList<>();
        ids.add(payerUserId);

        SwitchableChildrenResponse switchable = guardianshipSwitchService.listSwitchableChildren(payerUserId);
        for (SwitchableChildDto child : switchable.children()) {
            if (child.childUserId() != null && !ids.contains(child.childUserId())) {
                ids.add(child.childUserId());
            }
        }
        return ids;
    }

    /**
     * 1 受益者分の払える未払い会費明細を組み立てる。
     *
     * <p>受益者の未払い会費要件（{@link PaymentRequirementService#getPaymentRequirements}）を取得し、
     * 各項目について払い手の権原を {@link PaymentAuthorizationService#authorizePayment} で評価する。
     * 権原が成立しない（403）項目はスキップする（IDOR 防止・他人の子は権原がなければ列挙されない）。</p>
     */
    private List<PayableDueItem> buildItemsForBeneficiary(Long payerUserId, Long beneficiaryId) {
        List<PayableDueItem> items = new ArrayList<>();
        List<PaymentRequirementResponse> requirements =
                paymentRequirementService.getPaymentRequirements(beneficiaryId);

        String beneficiaryName = nameResolverService.resolveUserFullName(beneficiaryId);

        for (PaymentRequirementResponse req : requirements) {
            Long paymentItemId = req.getPaymentItem().getId();

            PayerRelationship relationship;
            try {
                relationship = paymentAuthorizationService.authorizePayment(
                        payerUserId, beneficiaryId, paymentItemId, false);
            } catch (BusinessException e) {
                // 権原なし（MEMBERSHIP_PAYER_NOT_AUTHORIZED 等）。この受益者×項目は払えないので列挙しない。
                continue;
            }

            items.add(toPayableDueItem(beneficiaryId, beneficiaryName, req, relationship));
        }
        return items;
    }

    /**
     * 未払い要件 1 件を {@link PayableDueItem} に変換する。alreadyPaid は false 前提（要件は未払いのみ抽出済み）だが、
     * 念のため有効 PAID を再確認して整合性情報（paidBy/paidAt）も埋められるようにする。
     */
    private PayableDueItem toPayableDueItem(Long beneficiaryId, String beneficiaryName,
                                            PaymentRequirementResponse req, PayerRelationship relationship) {
        PaymentRequirementResponse.PaymentItemRequirement item = req.getPaymentItem();
        Long paymentItemId = item.getId();
        String scopeType = req.getScope().getType();
        Long scopeId = req.getScope().getId();
        String scopeName = nameResolverService.resolveScopeName(scopeType, scopeId);

        int faceAmount = item.getAmount() != null ? item.getAmount().intValue() : 0;
        int payerSurcharge = 0; // 現状は払い手手数料を会費額に上乗せしない（将来 fee policy 連動）。
        int totalCharge = faceAmount + payerSurcharge;

        String kind = resolveKind(item.getType());

        // 整合性のための支払い済み再確認（通常は未払いなので空）。
        List<MemberPaymentEntity> paid =
                memberPaymentRepository.findValidPaidPayments(beneficiaryId, paymentItemId);
        boolean alreadyPaid = !paid.isEmpty();
        Long paidByUserId = null;
        String paidByDisplayName = null;
        java.time.Instant paidAt = null;
        if (alreadyPaid) {
            MemberPaymentEntity latest = paid.get(0);
            paidByUserId = latest.getPayerUserId() != null ? latest.getPayerUserId() : latest.getUserId();
            paidByDisplayName = nameResolverService.resolveUserFullName(paidByUserId);
            paidAt = latest.getPaidAt() != null ? latest.getPaidAt().toInstant(ZoneOffset.UTC) : null;
        }

        return new PayableDueItem(
                beneficiaryId,
                beneficiaryName,
                scopeType,
                scopeId,
                scopeName,
                paymentItemId,
                item.getName(),
                faceAmount,
                payerSurcharge,
                totalCharge,
                req.getOverdueSince(),
                kind,
                relationship.name(),
                alreadyPaid,
                paidByUserId,
                paidByDisplayName,
                paidAt
        );
    }

    /**
     * 会費種別（kind）を payment_item.type から導出する（02 §1.2 の {@code ONE_TIME|RECURRING|TERM}）。
     *
     * <p>ITEM/DONATION は単発（ONE_TIME）、MONTHLY_FEE は継続（RECURRING）、ANNUAL_FEE は期間制（TERM）として扱う。</p>
     */
    private String resolveKind(String type) {
        if (type == null) {
            return "ONE_TIME";
        }
        return switch (PaymentItemType.valueOf(type)) {
            case MONTHLY_FEE -> "RECURRING";
            case ANNUAL_FEE -> "TERM";
            case ITEM, DONATION -> "ONE_TIME";
        };
    }

    /**
     * 1 項目の決済起票を試み、結果（成功 / スキップ理由）を返す。
     *
     * <p>起票直前の再認可・支払い済み判定・受領口座 READY 判定はいずれも {@link MemberPaymentService#createConnectCheckout}
     * が内部で行うため、本メソッドはその {@link BusinessException} を捕捉して理由コードへ写像する（症状を隠さない）。
     * 各項目に冪等キーを発番し、Stripe へ橋渡しする（同一項目の二重起票は Stripe 冪等で吸収）。</p>
     */
    private BulkCheckoutResultItem processOneItem(Long payerUserId, Long beneficiaryId, Long paymentItemId) {
        // 項目存在チェック（無効 ID はスキップ理由を明示）。
        PaymentItemEntity item;
        try {
            item = paymentItemService.findByIdOrThrow(paymentItemId);
        } catch (BusinessException e) {
            return BulkCheckoutResultItem.skipped(paymentItemId, "ITEM_NOT_FOUND");
        }

        try {
            String idempotencyKey = "bulk-" + payerUserId + "-" + beneficiaryId + "-"
                    + paymentItemId + "-" + UUID.randomUUID();
            memberPaymentService.createConnectCheckout(
                    item.getId(), beneficiaryId, payerUserId, idempotencyKey);
            return BulkCheckoutResultItem.checkedOut(paymentItemId);
        } catch (BusinessException e) {
            String reason = mapSkipReason(e);
            log.info("まとめ払いスキップ: payer={}, beneficiary={}, itemId={}, reason={}",
                    payerUserId, beneficiaryId, paymentItemId, reason);
            return BulkCheckoutResultItem.skipped(paymentItemId, reason);
        }
    }

    /**
     * 起票時の {@link BusinessException} を {@link BulkCheckoutResultItem#skipReason()} へ写像する。
     * 未知のエラーは握り潰さず {@code "ERROR"} として可視化する（対処療法禁止・症状を隠さない）。
     */
    private String mapSkipReason(BusinessException e) {
        var code = e.getErrorCode();
        if (code == MembershipBillingErrorCode.MEMBERSHIP_ALREADY_PAID) {
            return "ALREADY_PAID";
        }
        if (code == MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED) {
            return "NOT_AUTHORIZED";
        }
        if (code == ConnectPaymentErrorCode.ONBOARDING_NOT_READY) {
            return "CONNECT_NOT_READY";
        }
        return "ERROR";
    }
}
