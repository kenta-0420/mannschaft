package com.mannschaft.app.loyalty;

import com.mannschaft.app.loyalty.dto.RuleResponse;
import com.mannschaft.app.loyalty.dto.RewardResponse;
import com.mannschaft.app.loyalty.dto.TransactionResponse;
import com.mannschaft.app.loyalty.entity.LoyaltyRuleEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyRewardEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyTransactionEntity;
import com.mannschaft.app.loyalty.dto.AccountDetail;
import com.mannschaft.app.loyalty.dto.AccountSummary;
import com.mannschaft.app.loyalty.dto.AwardRequestResponse;
import com.mannschaft.app.loyalty.dto.RewardProgress;
import com.mannschaft.app.loyalty.dto.ProgramSettings;
import com.mannschaft.app.loyalty.dto.OperationCatalog;
import com.mannschaft.app.loyalty.entity.LoyaltyAccountEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyProgramEntity;
import java.util.List;
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

    @Mapping(source = "entity.id", target = "accountId")
    AccountSummary accountSummary(LoyaltyAccountEntity entity, String teamName);

    @Mapping(source = "entity.id", target = "accountId")
    @Mapping(source = "teamId", target = "teamId")
    AccountDetail accountDetail(LoyaltyAccountEntity entity, Long teamId, String teamName,
            boolean isProgramEnabled, boolean isEligibleForAward, List<RewardProgress> rewards);

    @Mapping(source = "entity.active", target = "isActive")
    RewardProgress rewardProgress(LoyaltyRewardEntity entity, int remainingPoints, boolean canRedeem);

    @Mapping(source = "locationName", target = "locationName")
    @Mapping(source = "status", target = "status")
    @Mapping(source = "reason", target = "reason")
    AwardRequestResponse awardRequest(LoyaltyAwardRequestEntity entity, String teamName,
            String applicantDisplayName, String locationName, RequestStatus status,
            RequestReason reason, boolean canApprove, boolean canCancel);

    @Mapping(source = "program.enabled", target = "isEnabled")
    @Mapping(source = "teamId", target = "teamId")
    ProgramSettings programSettings(LoyaltyProgramEntity program, Long teamId,
            List<RuleResponse> rules, List<RewardResponse> rewards);

    @Mapping(source = "program.version", target = "programVersion")
    @Mapping(source = "teamId", target = "teamId")
    @Mapping(source = "isEnabled", target = "isEnabled")
    OperationCatalog operationCatalog(LoyaltyProgramEntity program, Long teamId, String teamName,
            boolean isEnabled, RuleResponse rule, List<RewardResponse> rewards);
}
