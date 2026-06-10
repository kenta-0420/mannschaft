package com.mannschaft.app.tournament.fee;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.payment.dto.ConnectCheckoutResponse;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.PaymentItemService;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.fee.dto.MyTournamentFeeItem;
import com.mannschaft.app.tournament.fee.dto.MyTournamentFeesResponse;
import com.mannschaft.app.tournament.fee.dto.TournamentFeeCheckoutResponse;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 大会参加費 Connect 決済サービス（F08.7.1 Connect 決済連携）。
 *
 * <p>認証ユーザーが対象となっている未払い／済みの大会参加費を一覧し、
 * {@link MemberPaymentService#createConnectCheckout} へ委譲して Connect 決済を実行する。</p>
 *
 * <p><strong>越境（原則5）TODO:</strong> tournament ドメインから membership・payment ドメインを
 * 直接参照している。将来はイベント駆動化を検討する。</p>
 */
@Slf4j
@Service("tournamentFeePaymentService")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TournamentFeePaymentService {

    private final TournamentFeeRepository tournamentFeeRepository;
    private final TournamentFeeTargetRepository tournamentFeeTargetRepository;
    private final MemberPaymentService memberPaymentService;
    private final PaymentItemService paymentItemService;
    private final MemberPaymentRepository memberPaymentRepository;
    private final MembershipRepository membershipRepository;
    private final TournamentRepository tournamentRepository;

    /**
     * 認証ユーザーが対象の大会参加費一覧を返す。
     *
     * <p>ALL_TEAMS: ユーザーが所属する組織に紐付く fee が対象。<br>
     * SPECIFIC_TEAMS: ユーザーが所属するチームが {@code tournament_fee_target} に含まれる fee のみ対象。</p>
     *
     * @param userId 認証ユーザー ID
     * @return 対象の参加費一覧（支払い済みフラグ付き）
     */
    public MyTournamentFeesResponse getMyTournamentFees(Long userId) {
        // 1. ユーザーが所属する組織 ID 一覧を取得（アクティブな ORGANIZATION スコープのメンバーシップ）
        List<Long> orgIds = membershipRepository
                .findActiveByUserAndScopeType(userId, ScopeType.ORGANIZATION)
                .stream()
                .map(MembershipEntity::getScopeId)
                .distinct()
                .toList();

        if (orgIds.isEmpty()) {
            return new MyTournamentFeesResponse(List.of());
        }

        // 2. ユーザーが所属するチーム ID 一覧を取得（SPECIFIC_TEAMS の eligibility チェック用）
        List<Long> teamIds = membershipRepository
                .findActiveByUserAndScopeType(userId, ScopeType.TEAM)
                .stream()
                .map(MembershipEntity::getScopeId)
                .distinct()
                .toList();

        // 3. 各組織の参加費を一括取得（@SQLRestriction により削除済みは自動除外）
        List<TournamentFeeEntity> allFees = new ArrayList<>();
        for (Long orgId : orgIds) {
            allFees.addAll(tournamentFeeRepository.findByOrganizationId(orgId));
        }

        if (allFees.isEmpty()) {
            return new MyTournamentFeesResponse(List.of());
        }

        // 4. 対象トーナメントを一括取得してマップ化（名前解決）
        List<Long> tournamentIds = allFees.stream()
                .map(TournamentFeeEntity::getTournamentId)
                .distinct()
                .toList();
        Map<Long, TournamentEntity> tournamentMap = tournamentRepository.findAllById(tournamentIds)
                .stream()
                .collect(Collectors.toMap(TournamentEntity::getId, Function.identity()));

        // 5. payment_items を一括取得
        List<Long> paymentItemIds = allFees.stream()
                .map(TournamentFeeEntity::getPaymentItemId)
                .distinct()
                .toList();

        List<MyTournamentFeeItem> result = new ArrayList<>();
        for (TournamentFeeEntity fee : allFees) {
            // 6. SPECIFIC_TEAMS の場合は eligibility チェック
            if (fee.getTargetScope() == TournamentFeeTargetScope.SPECIFIC_TEAMS) {
                boolean eligible = teamIds.stream()
                        .anyMatch(teamId ->
                                tournamentFeeTargetRepository.existsByFeeIdAndTeamId(fee.getId(), teamId));
                if (!eligible) {
                    continue;
                }
            }

            // 7. payment_item から金額取得
            PaymentItemEntity paymentItem = paymentItemService.findByIdOrThrow(fee.getPaymentItemId());
            int faceAmount = paymentItem.getAmount().intValue();

            // 8. 支払い済みチェック（自分個人の member_payments で PAID を確認）
            boolean alreadyPaid = memberPaymentRepository.existsValidPaidPayment(userId, fee.getPaymentItemId());

            // 9. paidAt 解決（支払い済みの場合のみ）
            java.time.LocalDateTime paidAt = null;
            if (alreadyPaid) {
                List<MemberPaymentEntity> paidPayments = memberPaymentRepository
                        .findValidPaidPayments(userId, fee.getPaymentItemId());
                if (!paidPayments.isEmpty()) {
                    paidAt = paidPayments.get(0).getPaidAt();
                }
            }

            // 10. トーナメント名解決（取得できない場合は fee.title で代替）
            TournamentEntity tournament = tournamentMap.get(fee.getTournamentId());
            String tournamentName = tournament != null ? tournament.getName() : fee.getTitle();

            result.add(new MyTournamentFeeItem(
                    fee.getId(),
                    fee.getTournamentId(),
                    tournamentName,
                    fee.getDivisionId(),
                    null,  // divisionName: 現時点未実装
                    fee.getTitle(),
                    fee.getPaymentItemId(),
                    faceAmount,
                    0,          // payerSurcharge: PaymentFeeCalculator 連携は今後対応
                    faceAmount, // totalCharge = faceAmount + surcharge
                    fee.getPaymentDue(),
                    alreadyPaid,
                    paidAt
            ));
        }

        return new MyTournamentFeesResponse(result);
    }

    /**
     * 大会参加費の Connect 決済チェックアウトを実行する。
     *
     * <p>fee の存在確認後、{@link MemberPaymentService#createConnectCheckout} に委譲する。
     * 受益者・払い手ともに認証ユーザー本人（SELF）とする（F08.7.1 §6 ・選手自払い）。</p>
     *
     * @param feeId          参加費 ID
     * @param payerUserId    払い手ユーザー ID（SecurityUtils.getCurrentUserId() で解決済み）
     * @param idempotencyKey 冪等性キー（省略可能・null の場合は自動生成）
     * @return チェックアウトレスポンス
     */
    @Transactional
    public TournamentFeeCheckoutResponse checkoutFee(UUID feeId, Long payerUserId, String idempotencyKey) {
        // 1. fee 存在確認（@SQLRestriction により削除済みは自動除外）
        TournamentFeeEntity fee = tournamentFeeRepository.findById(feeId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.FEE_NOT_FOUND));

        // 2. idempotencyKey 補完
        String key = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyKey
                : "tournament-fee-" + feeId + "-" + payerUserId + "-" + UUID.randomUUID();

        // 3. Connect 決済実行（beneficiary = payer = 払い手本人・SELF）
        // TODO: 越境（原則5）: tournament ドメインから payment ドメインを直接呼ぶ。
        //       将来は TournamentFeeCheckoutRequestedEvent によるイベント駆動化を検討する。
        ConnectCheckoutResponse resp = memberPaymentService.createConnectCheckout(
                fee.getPaymentItemId(),
                payerUserId,    // beneficiary = 払い手本人
                payerUserId,    // payer
                key
        );

        log.info("大会参加費 Connect 決済チェックアウト: feeId={}, payerUserId={}, memberPaymentId={}",
                feeId, payerUserId, resp.getMemberPaymentId());

        return new TournamentFeeCheckoutResponse(
                resp.getClientSecret(),
                resp.getMemberPaymentId(),
                resp.getEscrowTransactionId()
        );
    }
}
