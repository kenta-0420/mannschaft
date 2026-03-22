package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.dto.MyPaymentResponse;
import com.mannschaft.app.payment.dto.PaymentRequirementResponse;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.entity.TeamAccessRequirementEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.OrganizationAccessRequirementRepository;
import com.mannschaft.app.payment.repository.TeamAccessRequirementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 未払い要件サービス。ログインユーザーに課されている未払い項目一覧を提供する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentRequirementService {

    private final TeamAccessRequirementRepository teamAccessRequirementRepository;
    private final OrganizationAccessRequirementRepository organizationAccessRequirementRepository;
    private final PaymentItemService paymentItemService;
    private final MemberPaymentRepository memberPaymentRepository;

    /**
     * ユーザーに課されている未払い要件一覧を取得する。
     * <p>
     * TODO: ユーザーが所属するチーム/組織の一覧を取得する機能と連携後に完全実装する。
     * 現在はプレースホルダーとして空リストを返す。
     */
    public List<PaymentRequirementResponse> getPaymentRequirements(Long userId) {
        List<PaymentRequirementResponse> requirements = new ArrayList<>();

        // TODO: ユーザーの所属チーム一覧を取得し、各チームの access_requirements を確認
        // TODO: ユーザーの所属組織一覧を取得し、各組織の access_requirements を確認
        // TODO: content_payment_gates の未払い確認

        return requirements;
    }

    /**
     * 指定チームに対するユーザーの未払い要件を確認する。
     */
    public List<PaymentRequirementResponse> getTeamPaymentRequirements(Long userId, Long teamId) {
        List<TeamAccessRequirementEntity> requirements = teamAccessRequirementRepository.findByTeamId(teamId);
        List<PaymentRequirementResponse> result = new ArrayList<>();

        for (TeamAccessRequirementEntity req : requirements) {
            if (!memberPaymentRepository.existsValidPaidPayment(userId, req.getPaymentItemId())) {
                PaymentItemEntity item = paymentItemService.findByIdOrThrow(req.getPaymentItemId());
                result.add(new PaymentRequirementResponse(
                        new MyPaymentResponse.ScopeInfo("TEAM", teamId, null),
                        "TEAM_ACCESS",
                        new PaymentRequirementResponse.PaymentItemRequirement(
                                item.getId(), item.getName(), item.getType().name(),
                                item.getAmount(), item.getCurrency(),
                                item.getStripePriceId(), item.getGracePeriodDays()),
                        false, null
                ));
            }
        }

        return result;
    }
}
