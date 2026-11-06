package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.MembershipScopeQueryService;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.dto.MyPaymentResponse;
import com.mannschaft.app.payment.dto.PaymentRequirementResponse;
import com.mannschaft.app.payment.entity.OrganizationAccessRequirementEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.entity.TeamAccessRequirementEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.OrganizationAccessRequirementRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import com.mannschaft.app.payment.repository.TeamAccessRequirementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 未払い要件サービス。ログインユーザーに課されている未払い項目一覧を提供する。
 *
 * <p>N+1 回避（CMP-261103-1225 / #4270）: 要件は scope IN で一括取得し、有効支払済みの項目 ID 集合と
 * 項目本体もそれぞれ IN で一括取得する。SQL 数は所属数・要件数に依存しない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentRequirementService {

    private final TeamAccessRequirementRepository teamAccessRequirementRepository;
    private final OrganizationAccessRequirementRepository organizationAccessRequirementRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final MemberPaymentRepository memberPaymentRepository;
    private final MembershipScopeQueryService membershipScopeQueryService;

    /**
     * ユーザーに課されている未払い要件一覧を取得する。
     * ユーザーが所属する全チーム・全組織のアクセス要件を横断的に確認する。
     *
     * <p>応答順は「チーム（所属順）→ 組織（所属順）→ 各スコープ内は要件 ID 昇順」。</p>
     */
    public List<PaymentRequirementResponse> getPaymentRequirements(Long userId) {
        // CMP-027: user_roles ∪ memberships の在籍チーム／組織
        List<Long> teamIds = membershipScopeQueryService.findActiveTeamIds(userId);
        List<Long> orgIds = membershipScopeQueryService.findActiveOrganizationIds(userId);

        Map<Long, List<Long>> teamItemIds = teamIds.isEmpty() ? Map.of() : groupItemIds(
                teamAccessRequirementRepository.findByTeamIdIn(new LinkedHashSet<>(teamIds)),
                TeamAccessRequirementEntity::getId,
                TeamAccessRequirementEntity::getTeamId,
                TeamAccessRequirementEntity::getPaymentItemId);
        Map<Long, List<Long>> orgItemIds = orgIds.isEmpty() ? Map.of() : groupItemIds(
                organizationAccessRequirementRepository.findByOrganizationIdIn(new LinkedHashSet<>(orgIds)),
                OrganizationAccessRequirementEntity::getId,
                OrganizationAccessRequirementEntity::getOrganizationId,
                OrganizationAccessRequirementEntity::getPaymentItemId);

        // NOTE: content_payment_gates の未払い確認はコンテンツ種別ごとの統合が必要なため、
        // コンテンツ閲覧時に個別チェックする設計とする（ここでは対象外）
        return assemble(userId, teamIds, teamItemIds, orgIds, orgItemIds);
    }

    /**
     * 指定チームに対するユーザーの未払い要件を確認する。
     */
    public List<PaymentRequirementResponse> getTeamPaymentRequirements(Long userId, Long teamId) {
        Map<Long, List<Long>> itemIds = groupItemIds(
                teamAccessRequirementRepository.findByTeamId(teamId),
                TeamAccessRequirementEntity::getId,
                TeamAccessRequirementEntity::getTeamId,
                TeamAccessRequirementEntity::getPaymentItemId);
        return assemble(userId, List.of(teamId), itemIds, List.of(), Map.of());
    }

    /** 要件を scope ごと（要件 ID 昇順）の支払い項目 ID 列へ畳む。 */
    private <T> Map<Long, List<Long>> groupItemIds(
            List<T> requirements,
            Function<T, Long> id,
            Function<T, Long> scopeId,
            Function<T, Long> itemId) {
        Map<Long, List<Long>> grouped = new LinkedHashMap<>();
        requirements.stream()
                .sorted(Comparator.comparing(id, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(req -> grouped.computeIfAbsent(scopeId.apply(req), k -> new ArrayList<>())
                        .add(itemId.apply(req)));
        return grouped;
    }

    private List<PaymentRequirementResponse> assemble(
            Long userId,
            List<Long> teamIds, Map<Long, List<Long>> teamItemIds,
            List<Long> orgIds, Map<Long, List<Long>> orgItemIds) {
        Set<Long> itemIdSet = new LinkedHashSet<>();
        teamItemIds.values().forEach(itemIdSet::addAll);
        orgItemIds.values().forEach(itemIdSet::addAll);
        itemIdSet.remove(null);

        // 有効支払済みの項目 ID（1クエリ）と、未払い項目の本体（1クエリ）。空 IN は発行しない。
        Set<Long> paid = itemIdSet.isEmpty() ? Set.of()
                : new HashSet<>(memberPaymentRepository.findValidPaidPaymentItemIds(userId, List.copyOf(itemIdSet)));
        Set<Long> unpaid = new LinkedHashSet<>(itemIdSet);
        unpaid.removeAll(paid);
        Map<Long, PaymentItemEntity> items = new LinkedHashMap<>();
        if (!unpaid.isEmpty()) {
            for (PaymentItemEntity item : paymentItemRepository.findAllById(unpaid)) {
                items.put(item.getId(), item);
            }
        }

        List<PaymentRequirementResponse> result = new ArrayList<>();
        for (Long teamId : teamIds) {
            for (Long itemId : teamItemIds.getOrDefault(teamId, List.of())) {
                addIfUnpaid(result, paid, items, itemId, "TEAM", teamId, "TEAM_ACCESS");
            }
        }
        for (Long orgId : orgIds) {
            for (Long itemId : orgItemIds.getOrDefault(orgId, List.of())) {
                addIfUnpaid(result, paid, items, itemId, "ORGANIZATION", orgId, "ORGANIZATION_ACCESS");
            }
        }
        return result;
    }

    private void addIfUnpaid(List<PaymentRequirementResponse> result, Set<Long> paid,
                             Map<Long, PaymentItemEntity> items, Long itemId,
                             String scopeType, Long scopeId, String requirementType) {
        if (itemId != null && paid.contains(itemId)) {
            return;
        }
        // 従前の findByIdOrThrow と同じ扱い: 項目が無い（論理削除含む）/ null FK は PAYMENT_ITEM_NOT_FOUND。
        PaymentItemEntity item = itemId == null ? null : items.get(itemId);
        if (item == null) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ITEM_NOT_FOUND);
        }
        result.add(new PaymentRequirementResponse(
                new MyPaymentResponse.ScopeInfo(scopeType, scopeId, null),
                requirementType,
                new PaymentRequirementResponse.PaymentItemRequirement(
                        item.getId(), item.getName(), item.getType().name(),
                        item.getAmount(), item.getCurrency(),
                        item.getStripePriceId(), item.getGracePeriodDays()),
                false, null
        ));
    }
}
