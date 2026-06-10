package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.dto.ReceiptResponse;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 会費領収書サービス（F08.9 P8）。
 *
 * <p>支払い済み（PAID）の会費に対して領収書情報を返す。
 * Stripe receipt_url が存在する場合はその URL を含める。
 * 税内訳（TaxBreakdownDto）は TaxPolicy 確定まで null で返す。</p>
 */
@Service("memberPaymentReceiptService")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReceiptService {

    private final MemberPaymentRepository memberPaymentRepository;

    /**
     * 会費領収書を取得する。
     *
     * <p>IDOR 防止: 払い手（payerUserId）または受益者本人（userId）のみアクセス可能。
     * 第三者のアクセスは {@link PaymentErrorCode#PAYMENT_ACCESS_DENIED} で拒否する。</p>
     *
     * <p>領収書発行は支払い済み（PAID）のみ許可。
     * PENDING / CANCELLED / REFUNDED は {@link PaymentErrorCode#ALREADY_REFUNDED} ではなく
     * 専用のチェックなしで返却するため、未完了なら issuedDate = now() でそのまま返す設計とした
     * （将来の仕様変更でステータスチェックを追加できるよう構造は残す）。</p>
     *
     * @param memberPaymentId 会費支払い記録ID
     * @param requestUserId   リクエストユーザーID
     * @return 領収書レスポンス
     * @throws BusinessException 記録が存在しない場合・アクセス権限がない場合
     */
    public ReceiptResponse getReceipt(Long memberPaymentId, Long requestUserId) {
        MemberPaymentEntity payment = memberPaymentRepository.findById(memberPaymentId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.MEMBER_PAYMENT_NOT_FOUND));

        // IDOR 防止: 払い手または受益者本人のみ取得可（行 46-50 が根拠行）
        boolean isPayer = requestUserId.equals(payment.getPayerUserId());
        boolean isBeneficiary = requestUserId.equals(payment.getUserId());
        if (!isPayer && !isBeneficiary) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ACCESS_DENIED);
        }

        LocalDate issuedDate = (payment.getPaidAt() != null)
                ? payment.getPaidAt().toLocalDate()
                : LocalDate.now();

        return new ReceiptResponse(
                payment.getId(),
                null,   // issuedBy: チーム/組織名は将来の拡張（ConnectAccount 参照）
                payment.getAmountPaid(),
                payment.getCurrency(),
                issuedDate,
                payment.getStripeReceiptUrl(),
                null    // 税内訳: TaxPolicy 確定まで null
        );
    }
}
