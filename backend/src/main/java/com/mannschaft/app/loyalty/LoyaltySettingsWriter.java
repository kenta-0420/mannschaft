package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.loyalty.dto.CreateRewardRequest;
import com.mannschaft.app.loyalty.dto.CreateRuleRequest;
import com.mannschaft.app.loyalty.dto.ProgramSettings;
import com.mannschaft.app.loyalty.dto.RewardResponse;
import com.mannschaft.app.loyalty.dto.RuleResponse;
import com.mannschaft.app.loyalty.dto.UpdateProgramRequest;
import com.mannschaft.app.loyalty.dto.UpdateRewardRequest;
import com.mannschaft.app.loyalty.dto.UpdateRuleRequest;
import com.mannschaft.app.loyalty.entity.LoyaltyProgramEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyRewardEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyRuleEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 設定変更をprogram→rule/rewardの排他ロック順に閉じる。 */
@Service
@RequiredArgsConstructor
public class LoyaltySettingsWriter {
    private static final int MAX_REWARDS = 20;
    private final LoyaltyStore store;
    private final LoyaltyTime time;
    private final LoyaltyMapper mapper;

    @Transactional
    public ProgramSettings updateProgram(Long teamId, UpdateProgramRequest input) {
        LoyaltyProgramEntity program = store.getPrograms().lockExclusive(teamId).orElse(null);
        if (program == null) {
            if (input.expectedVersion() != null) {
                throw new BusinessException(CommonErrorCode.COMMON_003);
            }
            program = LoyaltyProgramEntity.builder().teamId(teamId).isEnabled(input.isEnabled())
                    .targetAudience(input.targetAudience()).selfAwardMode(input.selfAwardMode())
                    .createdAt(time.now()).updatedAt(time.now()).build();
            store.getPrograms().saveAndFlush(program);
        } else {
            checkVersion(program.getVersion(), input.expectedVersion());
            program.update(input.isEnabled(), input.targetAudience(), input.selfAwardMode(), time.now());
        }
        return settings(program);
    }

    @Transactional
    public RuleResponse createRule(Long teamId, CreateRuleRequest input) {
        store.getPrograms().lockExclusive(teamId).orElseThrow(() -> new BusinessException(LoyaltyErrorCode.LOYALTY_004));
        validateRule(input.frequency(), input.isSelfAwardEnabled());
        if (store.getRules().findByTeamId(teamId).isPresent()) {
            throw new BusinessException(LoyaltyErrorCode.LOYALTY_009);
        }
        LoyaltyRuleEntity rule = LoyaltyRuleEntity.builder().teamId(teamId).name(normalizeName(input.name()))
                .points(input.points()).frequency(input.frequency()).isSelfAwardEnabled(input.isSelfAwardEnabled())
                .isActive(true).createdAt(time.now()).updatedAt(time.now()).build();
        return mapper.rule(store.getRules().saveAndFlush(rule));
    }

    @Transactional
    public RuleResponse updateRule(Long teamId, java.util.UUID id, UpdateRuleRequest input) {
        store.getPrograms().lockExclusive(teamId).orElseThrow(() -> new BusinessException(LoyaltyErrorCode.LOYALTY_004));
        LoyaltyRuleEntity rule = store.getRules().lockExclusive(teamId, id)
                .orElseThrow(() -> new BusinessException(LoyaltyErrorCode.LOYALTY_001));
        checkVersion(rule.getVersion(), input.expectedVersion());
        validateRule(input.frequency(), input.isSelfAwardEnabled());
        if (rule.getFrequency() != input.frequency()
                && (store.getTransactions().existsByRuleId(id) || store.getRequests().existsByRuleId(id))) {
            throw new BusinessException(LoyaltyErrorCode.LOYALTY_009);
        }
        rule.update(normalizeName(input.name()), input.points(), input.frequency(), input.isSelfAwardEnabled(), input.isActive(), time.now());
        return mapper.rule(rule);
    }

    @Transactional
    public RewardResponse createReward(Long teamId, CreateRewardRequest input) {
        store.getPrograms().lockExclusive(teamId).orElseThrow(() -> new BusinessException(LoyaltyErrorCode.LOYALTY_004));
        if (store.getRewards().countByTeamId(teamId) >= MAX_REWARDS) {
            throw new BusinessException(LoyaltyErrorCode.LOYALTY_009);
        }
        LoyaltyRewardEntity reward = LoyaltyRewardEntity.builder().teamId(teamId).name(normalizeName(input.name()))
                .description(normalizeDescription(input.description())).requiredPoints(input.requiredPoints())
                .isActive(true).createdAt(time.now()).updatedAt(time.now()).build();
        return mapper.reward(store.getRewards().saveAndFlush(reward));
    }

    @Transactional
    public RewardResponse updateReward(Long teamId, java.util.UUID id, UpdateRewardRequest input) {
        store.getPrograms().lockExclusive(teamId).orElseThrow(() -> new BusinessException(LoyaltyErrorCode.LOYALTY_004));
        LoyaltyRewardEntity reward = store.getRewards().lockExclusive(teamId, id)
                .orElseThrow(() -> new BusinessException(LoyaltyErrorCode.LOYALTY_001));
        checkVersion(reward.getVersion(), input.expectedVersion());
        reward.update(normalizeName(input.name()), normalizeDescription(input.description()), input.requiredPoints(), input.isActive(), time.now());
        return mapper.reward(reward);
    }

    private ProgramSettings settings(LoyaltyProgramEntity p) {
        return new ProgramSettings(p.getId(), p.getTeamId(), p.isEnabled(), p.getTargetAudience(), p.getSelfAwardMode(), p.getVersion(),
                store.getRules().findByTeamId(p.getTeamId()).stream().map(mapper::rule).toList(),
                store.getRewards().findByTeamIdOrderById(p.getTeamId()).stream().map(mapper::reward).toList());
    }

    private void checkVersion(long actual, Long expected) {
        if (expected == null || expected != actual) {
            throw new BusinessException(CommonErrorCode.COMMON_003);
        }
    }

    private void validateRule(Frequency frequency, boolean selfEnabled) {
        if (selfEnabled && frequency != Frequency.ONCE_PER_JST_DAY) {
            throw new BusinessException(LoyaltyErrorCode.LOYALTY_009);
        }
    }

    private String normalizeName(String raw) {
        String normalized = raw.strip();
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new BusinessException(LoyaltyErrorCode.LOYALTY_009);
        }
        return normalized;
    }

    private String normalizeDescription(String raw) {
        return raw == null || raw.isBlank() ? null : raw.strip();
    }
}
