package com.mannschaft.app.loyalty;

import com.mannschaft.app.loyalty.dto.RuleResponse;
import com.mannschaft.app.loyalty.dto.RewardResponse;
import com.mannschaft.app.loyalty.dto.TransactionResponse;
import com.mannschaft.app.loyalty.entity.LoyaltyRuleEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyRewardEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyTransactionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** ポイントEntityから公開DTOへの一方向変換。 */
@Mapper(componentModel = "spring")
public interface LoyaltyMapper {
    @Mapping(source = "selfAwardEnabled", target = "isSelfAwardEnabled")
    @Mapping(source = "active", target = "isActive")
    RuleResponse rule(LoyaltyRuleEntity entity);

    @Mapping(source = "active", target = "isActive")
    RewardResponse reward(LoyaltyRewardEntity entity);

    TransactionResponse transaction(LoyaltyTransactionEntity entity);
}
