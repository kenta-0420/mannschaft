package com.mannschaft.app.loyalty;

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

/**
 * AC03/07/08/11/12/13の書込試練。実Bean/実MySQL/commitを観測する。
 * <p>生成列・CHECK・複合FKによる競合/rollbackを検証するため、Hibernate生成schemaではなく専用MySQLに実Flywayを適用する。
 * 共有基底と異なるschema契約なので専用contextを使い、クラス終了時に閉じる。</p>
 * <p>「事後補強」で始まる設定試験はSettingsWriter実装後の追加であり、TDDのred証拠とは扱わない。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@org.springframework.test.context.ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class LoyaltyPointsWriterIT {
    @SuppressWarnings("resource")
    static final org.testcontainers.containers.MySQLContainer<?> MYSQL =
            new org.testcontainers.containers.MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("loyalty_writer_flyway")
                    .withUsername("test").withPassword("test")
                    .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
                    .withCommand("--log_bin_trust_function_creators=1");
    static {
        if (AbstractMySqlIntegrationTest.isDockerAvailable()) {
            MYSQL.start();
        }
    }
    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Autowired private LoyaltyStore store;
    @Autowired private LoyaltyPointsWriter writer;
    @Autowired private LoyaltySettingsWriter settings;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Long teamId;
    private UUID ruleId;

    @BeforeEach
    void fixture() {
        teamId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1000000000L) + 1000000000L;
        store.getPrograms().saveAndFlush(LoyaltyTestFixture.program(teamId));
        ruleId = store.getRules().saveAndFlush(LoyaltyTestFixture.rule(teamId)).getId();
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

    @Test
    void AC22_承認モードの送信だけでは口座も台帳も作らない() {
        LoyaltyWriteCommand input = new LoyaltyWriteCommand(teamId, 20L, 20L, ruleId, 0, 0,
                RequestOperation.SELF_SUBMIT, UUID.randomUUID(), new byte[32], 30L, "受付");
        LoyaltySubmissionOutcome outcome = writer.submit(input);
        assertThat(outcome).isNotNull();
        assertThat(outcome.transaction()).isNull();
        assertThat(outcome.request()).isNotNull();
        assertThat(outcome.request().getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(outcome.request().getVerificationCode()).matches("[A-Z2-7]{8}");
        assertThat(store.getAccounts().findByTeamIdAndUserId(teamId, 20L)).isEmpty();
        assertThat(store.getTransactions().existsByRuleId(ruleId)).isFalse();
    }

    @Test
    void AC26_終端後の送信キー再送も元申請を返す() {
        LoyaltyWriteCommand input = new LoyaltyWriteCommand(teamId, 20L, 20L, ruleId, 0, 0,
                RequestOperation.SELF_SUBMIT, UUID.randomUUID(), new byte[32], 30L, "受付");
        LoyaltySubmissionOutcome submitted = writer.submit(input);
        assertThat(submitted).isNotNull();
        var request = submitted.request();
        LoyaltyRequestActionOutcome cancelled = writer.act(new LoyaltyRequestActionCommand(teamId, 20L,
                request.getId(), 0, null, RequestOperation.CANCEL, UUID.randomUUID(), new byte[32], null));
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.request().getStatus()).isEqualTo(RequestStatus.CANCELLED);
        LoyaltySubmissionOutcome replay = writer.submit(input);
        assertThat(replay.request().getId()).isEqualTo(request.getId());
        assertThat(replay.request().getStatus()).isEqualTo(RequestStatus.CANCELLED);
        assertThat(store.getAccounts().findByTeamIdAndUserId(teamId, 20L)).isEmpty();
    }

    @Test
    void AC23_承認は保存加算数で申請と残高と台帳を同時確定する() {
        LoyaltySubmissionOutcome submitted = writer.submit(new LoyaltyWriteCommand(teamId, 20L, 20L, ruleId, 0, 0,
                RequestOperation.SELF_SUBMIT, UUID.randomUUID(), new byte[32], 30L, "受付"));
        assertThat(submitted).isNotNull();
        var request = submitted.request();
        LoyaltyRequestActionOutcome approved = writer.act(new LoyaltyRequestActionCommand(teamId, 10L,
                request.getId(), 0, request.getVerificationCode(), RequestOperation.APPROVE,
                UUID.randomUUID(), new byte[32], null));
        assertThat(approved).isNotNull();
        assertThat(approved.errorCode()).isNull();
        assertThat(approved.request().getStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(approved.transaction()).isNotNull();
        assertThat(approved.request().getTransactionId()).isEqualTo(approved.transaction().id());
        assertThat(approved.transaction().channel()).isEqualTo(TransactionChannel.SELF_APPROVED);
        assertThat(approved.transaction().balanceAfter()).isEqualTo(1);
    }

    @Test
    void AC23_自己承認は状態不変で403業務エラー() {
        LoyaltySubmissionOutcome submitted = writer.submit(new LoyaltyWriteCommand(teamId, 20L, 20L, ruleId, 0, 0,
                RequestOperation.SELF_SUBMIT, UUID.randomUUID(), new byte[32], 30L, "受付"));
        assertThat(submitted).isNotNull();
        var request = submitted.request();
        assertThatThrownBy(() -> writer.act(new LoyaltyRequestActionCommand(teamId, 20L,
                request.getId(), 0, request.getVerificationCode(), RequestOperation.APPROVE,
                UUID.randomUUID(), new byte[32], null)))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class)
                .extracting("errorCode").isEqualTo(LoyaltyErrorCode.LOYALTY_015);
        assertThat(store.getRequests().findById(request.getId())).get().extracting("status").isEqualTo(RequestStatus.PENDING);
    }

    @Test
    void AC28_先行スタッフ加算は同日申請をSUPERSEDEDへ閉じる() {
        LoyaltySubmissionOutcome submitted = writer.submit(new LoyaltyWriteCommand(teamId, 20L, 20L, ruleId, 0, 0,
                RequestOperation.SELF_SUBMIT, UUID.randomUUID(), new byte[32], 30L, "受付"));
        assertThat(submitted).isNotNull();
        writer.award(command(UUID.randomUUID(), 0, new byte[32]));
        assertThat(store.getRequests().findById(submitted.request().getId())).get()
                .extracting("status", "reason").containsExactly(RequestStatus.SUPERSEDED, RequestReason.DAILY_AWARDED);
    }

    @Test
    void AC25_版変更による終端化は例外ではなくcommit済outcomeを返す() {
        LoyaltySubmissionOutcome submitted = writer.submit(new LoyaltyWriteCommand(teamId, 20L, 20L, ruleId, 0, 0,
                RequestOperation.SELF_SUBMIT, UUID.randomUUID(), new byte[32], 30L, "受付"));
        assertThat(submitted).isNotNull();
        var program = store.getPrograms().findByTeamId(teamId).orElseThrow();
        program.update(true, Audience.ALL_AUTHENTICATED, SelfAwardMode.AUTO, LocalDateTime.now());
        store.getPrograms().saveAndFlush(program);
        var request = submitted.request();
        LoyaltyRequestActionOutcome invalidated = writer.act(new LoyaltyRequestActionCommand(teamId, 10L,
                request.getId(), 0, request.getVerificationCode(), RequestOperation.APPROVE,
                UUID.randomUUID(), new byte[32], null));
        assertThat(invalidated).isNotNull();
        assertThat(invalidated.errorCode()).isEqualTo(LoyaltyErrorCode.LOYALTY_014);
        assertThat(store.getRequests().findById(request.getId())).get()
                .extracting("status", "reason").containsExactly(RequestStatus.INVALIDATED, RequestReason.VERSION_CHANGED);
        assertThat(store.getAccounts().findByTeamIdAndUserId(teamId, 20L)).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {19, 20, 25})
    void AC11_20ポイント交換の境界は19なら不足20なら0で25なら5(int balance) {
        var reward = store.getRewards().saveAndFlush(LoyaltyTestFixture.reward(teamId, 20));
        store.getAccounts().saveAndFlush(com.mannschaft.app.loyalty.entity.LoyaltyAccountEntity.builder()
                .teamId(teamId).userId(20L).balance(balance).lifetimeEarned(balance)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        LoyaltyWriteCommand input = new LoyaltyWriteCommand(teamId, 10L, 20L, reward.getId(), 0, 0,
                RequestOperation.REDEEM, UUID.randomUUID(), new byte[32], null, null);
        if (balance < 20) {
            assertThatThrownBy(() -> writer.redeem(input)).isInstanceOf(com.mannschaft.app.common.BusinessException.class)
                    .extracting("errorCode").isEqualTo(LoyaltyErrorCode.LOYALTY_008);
        } else {
            TransactionResponse redeemed = writer.redeem(input);
            assertThat(redeemed).isNotNull();
            assertThat(redeemed.delta()).isEqualTo(-20);
            assertThat(redeemed.balanceAfter()).isEqualTo(balance - 20);
        }
    }

    @Test
    void AC07_同じキーの内容相違は残高不変の409業務エラー() {
        UUID key = UUID.randomUUID();
        assertThat(writer.award(command(key, 0, new byte[32]))).isNotNull();
        byte[] changed = new byte[32];
        changed[0] = 1;
        assertThatThrownBy(() -> writer.award(command(key, 0, changed)))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class)
                .extracting("errorCode").isEqualTo(LoyaltyErrorCode.LOYALTY_006);
        assertThat(store.getAccounts().findByTeamIdAndUserId(teamId, 20L)).get().extracting("balance").isEqualTo(1);
    }

    @Test
    void AC13_台帳insert失敗は作成した口座も残さず全rollback() {
        String trigger = "loyalty_failure_" + teamId;
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON team_loyalty_transactions FOR EACH ROW "
                + "BEGIN IF NEW.team_id = " + teamId + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'loyalty-injected-ledger'; END IF; END");
        try {
            assertThatThrownBy(() -> writer.award(command(UUID.randomUUID(), 0, new byte[32])))
                    .hasStackTraceContaining("loyalty-injected-ledger");
            assertThat(store.getAccounts().findByTeamIdAndUserId(teamId, 20L)).isEmpty();
            assertThat(store.getTransactions().existsByRuleId(ruleId)).isFalse();
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
        }
    }

    @Test
    void AC13_commit直前の障害でも口座と台帳は全rollback() {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            throw new IllegalStateException("loyalty-injected-before-commit");
                        }
                    });
            assertThat(writer.award(command(UUID.randomUUID(), 0, new byte[32]))).isNotNull();
        })).hasMessage("loyalty-injected-before-commit");
        assertThat(store.getAccounts().findByTeamIdAndUserId(teamId, 20L)).isEmpty();
        assertThat(store.getTransactions().existsByRuleId(ruleId)).isFalse();
    }

    @Test
    void 事後補強_AC14_未作成設定の作成はexpectedVersionがnullで版0となる() {
        Long newTeam = teamId + 10000000000L;
        try {
            var result = settings.updateProgram(newTeam, new com.mannschaft.app.loyalty.dto.UpdateProgramRequest(
                    null, false, Audience.MEMBERS_AND_ABOVE, SelfAwardMode.STAFF_APPROVAL));
            assertThat(result.id()).isNotNull();
            assertThat(result.version()).isZero();
            assertThat(result.isEnabled()).isFalse();
            assertThat(result.targetAudience()).isEqualTo(Audience.MEMBERS_AND_ABOVE);
            assertThat(result.selfAwardMode()).isEqualTo(SelfAwardMode.STAFF_APPROVAL);
            assertThat(result.rules()).isEmpty();
            assertThat(result.rewards()).isEmpty();
        } finally {
            store.getPrograms().findByTeamId(newTeam).ifPresent(store.getPrograms()::delete);
        }
    }

    @Test
    void 事後補強_AC12_設定の楽観競合はCOMMON003で保存値不変() {
        assertThatThrownBy(() -> settings.updateProgram(teamId, new com.mannschaft.app.loyalty.dto.UpdateProgramRequest(
                1L, false, Audience.MEMBERS_AND_ABOVE, SelfAwardMode.AUTO)))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class)
                .extracting("errorCode").isEqualTo(com.mannschaft.app.common.CommonErrorCode.COMMON_003);
        assertThat(store.getPrograms().findByTeamId(teamId)).get()
                .extracting("enabled", "version", "selfAwardMode").containsExactly(true, 0L, SelfAwardMode.STAFF_APPROVAL);
    }

    @Test
    void 事後補強_AC21_mode変更はprogram版を増やす() {
        var updated = settings.updateProgram(teamId, new com.mannschaft.app.loyalty.dto.UpdateProgramRequest(
                0L, true, Audience.ALL_AUTHENTICATED, SelfAwardMode.AUTO));
        assertThat(updated.version()).isEqualTo(1L);
        assertThat(updated.selfAwardMode()).isEqualTo(SelfAwardMode.AUTO);
    }

    @Test
    void 事後補強_AC14_来店ルール複製は400で元ルール1件を保持する() {
        assertThatThrownBy(() -> settings.createRule(teamId, new com.mannschaft.app.loyalty.dto.CreateRuleRequest(
                "別来店", 2, Frequency.ONCE_PER_JST_DAY, true)))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class)
                .extracting("errorCode").isEqualTo(LoyaltyErrorCode.LOYALTY_009);
        assertThat(store.getRules().findByTeamId(teamId)).get().extracting("id").isEqualTo(ruleId);
    }

    @Test
    void 事後補強_AC14_都度ルールの本人読取許可との不正組合せを拒否する() {
        assertThatThrownBy(() -> settings.updateRule(teamId, ruleId, new com.mannschaft.app.loyalty.dto.UpdateRuleRequest(
                "都度", 1, Frequency.EACH_STAFF_CONFIRMATION, true, 0L, true)))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class)
                .extracting("errorCode").isEqualTo(LoyaltyErrorCode.LOYALTY_009);
        assertThat(store.getRules().findById(ruleId)).get().extracting("frequency").isEqualTo(Frequency.ONCE_PER_JST_DAY);
    }

    @Test
    void 事後補強_AC14_停止済みを含む特典上限20件と説明の空白正規化() {
        for (int i = 0; i < 20; i++) {
            var reward = settings.createReward(teamId, new com.mannschaft.app.loyalty.dto.CreateRewardRequest(
                    "  特典" + i + "  ", "   ", 20));
            assertThat(reward.name()).isEqualTo("特典" + i);
            assertThat(reward.description()).isNull();
            settings.updateReward(teamId, reward.id(), new com.mannschaft.app.loyalty.dto.UpdateRewardRequest(
                    reward.name(), null, 20, 0L, false));
        }
        assertThatThrownBy(() -> settings.createReward(teamId, new com.mannschaft.app.loyalty.dto.CreateRewardRequest(
                "21件目", null, 20))).isInstanceOf(com.mannschaft.app.common.BusinessException.class)
                .extracting("errorCode").isEqualTo(LoyaltyErrorCode.LOYALTY_009);
        assertThat(store.getRewards().countByTeamId(teamId)).isEqualTo(20);
    }

    @org.junit.jupiter.api.AfterEach
    void removeOnlyOwnedRows() {
        if (teamId == null) return;
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var page = org.springframework.data.domain.PageRequest.of(0, 100);
            store.getRequests().deleteAll(store.getRequests().teamFirstPage(teamId, page));
            store.getTransactions().deleteAll(store.getTransactions().firstPage(teamId, 20L, page));
            store.getAccounts().findByTeamIdAndUserId(teamId, 20L).ifPresent(store.getAccounts()::delete);
            store.getRewards().deleteAll(store.getRewards().findByTeamIdOrderById(teamId));
            store.getRules().findByTeamId(teamId).ifPresent(store.getRules()::delete);
            store.getPrograms().findByTeamId(teamId).ifPresent(store.getPrograms()::delete);
        });
    }
}
