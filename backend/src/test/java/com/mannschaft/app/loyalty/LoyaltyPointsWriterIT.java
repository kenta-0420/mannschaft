package com.mannschaft.app.loyalty;

import com.mannschaft.app.loyalty.entity.LoyaltyProgramEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyRuleEntity;
import com.mannschaft.app.loyalty.dto.TransactionResponse;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AC03/07/08/11/12/13の書込試練。実Bean/実MySQL/commitを観測する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class LoyaltyPointsWriterIT extends AbstractMySqlIntegrationTest {
    @Autowired private LoyaltyStore store;
    @Autowired private LoyaltyPointsWriter writer;
    private Long teamId;
    private UUID ruleId;

    @BeforeEach
    void fixture() {
        teamId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1000000000L) + 1000000000L;
        LocalDateTime now = LocalDateTime.of(2026, 10, 16, 12, 0);
        store.getPrograms().saveAndFlush(LoyaltyProgramEntity.builder().teamId(teamId).isEnabled(true)
                .targetAudience(Audience.ALL_AUTHENTICATED).selfAwardMode(SelfAwardMode.STAFF_APPROVAL)
                .createdAt(now).updatedAt(now).build());
        ruleId = store.getRules().saveAndFlush(LoyaltyRuleEntity.builder().teamId(teamId).name("来店")
                .points(1).frequency(Frequency.ONCE_PER_JST_DAY).isSelfAwardEnabled(true).isActive(true)
                .createdAt(now).updatedAt(now).build()).getId();
    }

    private LoyaltyWriteCommand command(UUID key, long version, byte[] hash) {
        return new LoyaltyWriteCommand(teamId, 10L, 20L, ruleId, version, 0,
                RequestOperation.STAFF_AWARD, key, hash, null, null);
    }

    @Test
    void AC03_付与は同じcommitで本人残高と台帳を1件作る() {
        TransactionResponse result = writer.award(command(UUID.randomUUID(), 0, new byte[32]));
        assertThat(result).isNotNull();
        assertThat(result.balanceAfter()).isEqualTo(1);
        assertThat(store.getAccounts().findByTeamIdAndUserId(teamId, 20L)).get()
                .extracting("balance", "lifetimeEarned").containsExactly(1, 1L);
        assertThat(store.getTransactions().existsByRuleId(ruleId)).isTrue();
    }

    @Test
    void AC07_同じキーの再送は同じ取引と確定時残高を返す() {
        LoyaltyWriteCommand command = command(UUID.randomUUID(), 0, new byte[32]);
        TransactionResponse original = writer.award(command);
        TransactionResponse resent = writer.award(command);
        assertThat(original).isNotNull();
        assertThat(resent).isEqualTo(original);
        assertThat(store.getAccounts().findByTeamIdAndUserId(teamId, 20L)).get().extracting("balance").isEqualTo(1);
    }

    @Test
    void AC08_別キーでも同日日次枠は一つだけ() {
        writer.award(command(UUID.randomUUID(), 0, new byte[32]));
        assertThatThrownBy(() -> writer.award(command(UUID.randomUUID(), 0, new byte[32])))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class)
                .extracting("errorCode").isEqualTo(LoyaltyErrorCode.LOYALTY_005);
    }

    @Test
    void AC12_確認時とルール版が違えば残高を作らず拒否する() {
        assertThatThrownBy(() -> writer.award(command(UUID.randomUUID(), 1, new byte[32])))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class)
                .extracting("errorCode").isEqualTo(LoyaltyErrorCode.LOYALTY_007);
        assertThat(store.getAccounts().findByTeamIdAndUserId(teamId, 20L)).isEmpty();
    }
}
