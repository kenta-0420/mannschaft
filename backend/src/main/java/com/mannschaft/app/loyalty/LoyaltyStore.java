package com.mannschaft.app.loyalty;

import com.mannschaft.app.loyalty.repository.LoyaltyAccountRepository;
import com.mannschaft.app.loyalty.repository.LoyaltyAwardRequestRepository;
import com.mannschaft.app.loyalty.repository.LoyaltyProgramRepository;
import com.mannschaft.app.loyalty.repository.LoyaltyRewardRepository;
import com.mannschaft.app.loyalty.repository.LoyaltyRuleRepository;
import com.mannschaft.app.loyalty.repository.LoyaltyTransactionRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 同じloyaltyシャードに閉じた永続化部品をまとめる。 */
@Component
@Getter
@RequiredArgsConstructor
public class LoyaltyStore {
    private final LoyaltyProgramRepository programs;
    private final LoyaltyRuleRepository rules;
    private final LoyaltyRewardRepository rewards;
    private final LoyaltyAccountRepository accounts;
    private final LoyaltyTransactionRepository transactions;
    private final LoyaltyAwardRequestRepository requests;
}
