package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.dto.MyPaymentResponse;
import com.mannschaft.app.payment.dto.PaymentRequirementResponse;
import com.mannschaft.app.payment.entity.OrganizationAccessRequirementEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.entity.TeamAccessRequirementEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.OrganizationAccessRequirementRepository;
import com.mannschaft.app.payment.repository.TeamAccessRequirementRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
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
    private final UserRoleRepository userRoleRepository;

    /**
     * ユーザーに課されている未払い要件一覧を取得する。
     * ユーザーが所属する全チーム・全組織のアクセス要件を横断的に確認する。
     */
    public List<PaymentRequirementResponse> getPaymentRequirements(Long userId) {
        List<PaymentRequirementResponse> requirements = new ArrayList<>();

        // ユーザーの所属チーム一覧（CMP-027: user_roles ∪ memberships の在籍チーム）ごとに access_requirements を確認
        for (Long teamId : userRoleRepository.findTeamIdsByUserId(userId)) {
            requirements.addAll(getTeamPaymentRequirements(userId, teamId));
        }

        // ユーザーの所属組織一覧（CMP-027: user_roles ∪ memberships の在籍組織）ごとに access_requirements を確認
        for (Long orgId : userRoleRepository.findOrganizationIdsByUserId(userId)) {
            requirements.addAll(getOrganizationPaymentRequirements(userId, orgId));
        }

        // NOTE: content_payment_gates の未払い確認はコンテンツ種別ごとの統合が必要なため、
        // コンテンツ閲覧時に個別チェックする設計とする（ここでは対象外）

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

    /**
     * 指定組織に対するユーザーの未払い要件を確認する。
     */
    private List<PaymentRequirementResponse> getOrganizationPaymentRequirements(Long userId, Long organizationId) {
        List<OrganizationAccessRequirementEntity> requirements =
                organizationAccessRequirementRepository.findByOrganizationId(organizationId);
        List<PaymentRequirementResponse> result = new ArrayList<>();

        for (OrganizationAccessRequirementEntity req : requirements) {
            if (!memberPaymentRepository.existsValidPaidPayment(userId, req.getPaymentItemId())) {
                PaymentItemEntity item = paymentItemService.findByIdOrThrow(req.getPaymentItemId());
                result.add(new PaymentRequirementResponse(
                        new MyPaymentResponse.ScopeInfo("ORGANIZATION", organizationId, null),
                        "ORGANIZATION_ACCESS",
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
