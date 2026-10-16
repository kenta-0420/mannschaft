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
@org.springframework.boot.test.context.SpringBootTest(classes = LoyaltyPointsWriterIT.PersistenceConfiguration.class, properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@org.springframework.test.context.ActiveProfiles("test")
@org.springframework.context.annotation.Import(LoyaltyPointsWriterIT.FixedClockConfiguration.class)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class LoyaltyPointsWriterIT {
    /** 他ドメインへの境界はHTTP契約試練で確認し、ここでは実Writer/TX/DDLを限定起動する。 */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.boot.autoconfigure.domain.EntityScan(basePackageClasses = com.mannschaft.app.loyalty.entity.LoyaltyProgramEntity.class)
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(basePackageClasses = com.mannschaft.app.loyalty.repository.LoyaltyProgramRepository.class)
    @org.springframework.context.annotation.Import({LoyaltyStore.class, LoyaltyPointsWriter.class,
            LoyaltySettingsWriter.class, LoyaltyTime.class, LoyaltyMapperImpl.class,
            LoyaltyRequestStatePolicy.class, com.mannschaft.app.config.TimeZoneConfig.class})
    static class PersistenceConfiguration { }

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

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        java.time.Clock loyaltyFixtureClock() {
            return java.time.Clock.fixed(java.time.Instant.parse("2026-10-16T14:50:00Z"), java.time.ZoneOffset.UTC);
        }
    }
    @Autowired private LoyaltyStore store;
    @Autowired private LoyaltyPointsWriter writer;
    @Autowired private LoyaltySettingsWriter settings;
    @Autowired private LoyaltyRequestStatePolicy statePolicy;
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

    @Test
    void AC12_初回program作成2並列は一件成功と正確な設定版競合になる() throws Exception {
        // 独立した開始を繰り返し、存在しないprogramへのgap/unique競合も実MySQLへ通す。
        for (int round = 0; round < 10; round++) {
            jdbc.update("DELETE FROM team_loyalty_programs WHERE team_id=?", teamId);
            var outcomes = parallel(2, ignored -> {
                try {
                    return (Object) settings.updateProgram(teamId,
                            new com.mannschaft.app.loyalty.dto.UpdateProgramRequest(null, true,
                                    Audience.ALL_AUTHENTICATED, SelfAwardMode.STAFF_APPROVAL));
                } catch (RuntimeException failure) {
                    // 未変換のDB障害を成功扱いせず、後段で正確なBusinessException/codeを要求する。
                    return (Object) failure;
                }
            });
            assertThat(outcomes.stream().filter(value -> value instanceof com.mannschaft.app.loyalty.dto.ProgramSettings)).hasSize(1);
            assertThat(outcomes.stream().filter(value -> !(value instanceof com.mannschaft.app.loyalty.dto.ProgramSettings)))
                    .singleElement().isInstanceOf(com.mannschaft.app.common.BusinessException.class)
                    .extracting("errorCode").isEqualTo(com.mannschaft.app.common.CommonErrorCode.COMMON_003);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM team_loyalty_programs WHERE team_id=?", Long.class, teamId)).isEqualTo(1L);
            assertThat(store.getPrograms().findByTeamId(teamId)).get().extracting("version").isEqualTo(0L);
        }
    }

    @org.junit.jupiter.api.AfterEach
    void removeOnlyOwnedRows() {
        if (teamId == null) return;
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // 専用Testcontainerで当試練のteamだけを片付ける。200人fixtureも残さない。
            jdbc.update("DELETE FROM team_loyalty_award_requests WHERE team_id=?", teamId);
            jdbc.update("DELETE FROM team_loyalty_transactions WHERE team_id=?", teamId);
            jdbc.update("DELETE FROM team_loyalty_accounts WHERE team_id=?", teamId);
            jdbc.update("DELETE FROM team_loyalty_rewards WHERE team_id=?", teamId);
            jdbc.update("DELETE FROM team_loyalty_rules WHERE team_id=?", teamId);
            jdbc.update("DELETE FROM team_loyalty_programs WHERE team_id=?", teamId);
        });
    }
    @Test
    @org.junit.jupiter.api.parallel.ResourceLock("java.util.TimeZone.default")
    void AC08_接続作成後のJVM時差に影響されずSQL_DATEのJST日付が往復する() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        jdbc.queryForObject("SELECT 1", Integer.class); // 先に接続を作る。
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Honolulu"));
            TransactionResponse result = writer.award(command(UUID.randomUUID(), 0, new byte[32]));
            assertThat(result).isNotNull();
            assertThat(result.awardOn()).isEqualTo(java.time.LocalDate.of(2026, 10, 16));
            assertThat(jdbc.queryForObject("SELECT CAST(award_on AS CHAR) FROM team_loyalty_transactions WHERE team_id=?", String.class, teamId))
                    .isEqualTo("2026-10-16");
            assertThat(store.getTransactions().findById(result.id())).get()
                    .extracting("awardOn").isEqualTo(java.time.LocalDate.of(2026, 10, 16));
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    @Test
    void AC24_日付境界900ミリ秒の申請をDATETIME精度0でも翌日へ丸めない() {
        var fractional = new LoyaltyTime(java.time.Clock.fixed(
                java.time.Instant.parse("2026-10-16T14:59:59.900Z"), java.time.ZoneOffset.UTC));
        var created = fractional.now();
        var row = com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity.builder()
                .teamId(teamId).userId(20L).ruleId(ruleId).sourceLocationId(30L)
                .programVersion(0).ruleVersion(0).points(1).ruleName("来店").locationName("受付")
                .awardOn(fractional.today()).expiresAt(fractional.expiresAt(created))
                .status(RequestStatus.PENDING).version(0).verificationCode("ABCDEFGH")
                .submitKey(UUID.randomUUID()).submitPayloadHash(new byte[32])
                .createdAt(created).updatedAt(created).build();
        org.assertj.core.api.Assertions.assertThatCode(() -> store.getRequests().saveAndFlush(row))
                .as("端数が日付境界へ丸まりcreated_at=expires_atになる保存失敗を起こさない")
                .doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') FROM team_loyalty_award_requests WHERE team_id=?", String.class, teamId))
                .isEqualTo("2026-10-16 14:59:59");
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(expires_at,'%Y-%m-%d %H:%i:%s') FROM team_loyalty_award_requests WHERE team_id=?", String.class, teamId))
                .isEqualTo("2026-10-16 15:00:00");
        assertThat(jdbc.queryForObject("SELECT CAST(award_on AS CHAR) FROM team_loyalty_award_requests WHERE team_id=?", String.class, teamId))
                .isEqualTo("2026-10-16");
        assertThat(store.getRequests().findById(row.getId())).get()
                .extracting("createdAt", "expiresAt")
                .containsExactly(LocalDateTime.of(2026, 10, 16, 23, 59, 59), LocalDateTime.of(2026, 10, 17, 0, 0));
    }

    @Test
    void AC24_23時50分申請は翌日0時で失効し30分後に延長しない() {
        LoyaltyWriteCommand submit = new LoyaltyWriteCommand(teamId, 20L, 20L, ruleId, 0, 0,
                RequestOperation.SELF_SUBMIT, UUID.randomUUID(), new byte[32], 1L, "受付");
        LoyaltySubmissionOutcome result = writer.submit(submit);
        assertThat(result).isNotNull();
        assertThat(result.request()).isNotNull();
        assertThat(result.request().getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 10, 17, 0, 0));
        assertThat(result.request().getAwardOn()).isEqualTo(java.time.LocalDate.of(2026, 10, 16));
    }
    @Test
    void AC07_同じkey20並列は一つの確定取引と残高1を返す() throws Exception {
        LoyaltyWriteCommand input = command(UUID.randomUUID(), 0, new byte[32]);
        var results = parallel(20, ignored -> attempt(() -> writer.award(input)));
        assertThat(results).allSatisfy(result -> {
            assertThat(result.error()).isNull(); assertThat(result.value()).isInstanceOf(TransactionResponse.class);
        });
        assertThat(results.stream().map(result -> ((TransactionResponse) result.value()).id()).distinct()).hasSize(1);
        assertThat(store.getAccounts().findByTeamIdAndUserId(teamId, 20L)).get()
                .extracting("balance", "lifetimeEarned").containsExactly(1, 1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM team_loyalty_transactions WHERE team_id=?", Long.class, teamId)).isEqualTo(1L);
    }

    @Test
    void AC08_同一口座別key20並列の日次枠は一つだけ() throws Exception {
        var results = parallel(20, ignored -> attempt(() -> writer.award(command(UUID.randomUUID(), 0, new byte[32]))));
        assertThat(results.stream().filter(result -> result.value() instanceof TransactionResponse)).hasSize(1);
        assertThat(results.stream().filter(result -> result.error() == LoyaltyErrorCode.LOYALTY_005)).hasSize(19);
        assertThat(store.getAccounts().findByTeamIdAndUserId(teamId, 20L)).get()
                .extracting("balance", "lifetimeEarned").containsExactly(1, 1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM team_loyalty_transactions WHERE team_id=?", Long.class, teamId)).isEqualTo(1L);
    }

    @Test
    void AC19_別人初回200並列はprogram共有lock下で全員一回だけ作成する() throws Exception {
        var results = parallel(200, index -> attempt(() -> writer.award(new LoyaltyWriteCommand(
                teamId, 10L, 1000L + index, ruleId, 0, 0, RequestOperation.STAFF_AWARD,
                UUID.randomUUID(), new byte[32], null, null))));
        assertThat(results).allSatisfy(result -> {
            assertThat(result.error()).isNull();
            assertThat(result.value()).isInstanceOf(TransactionResponse.class);
            assertThat(((TransactionResponse) result.value()).balanceAfter()).isEqualTo(1);
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM team_loyalty_accounts WHERE team_id=?", Long.class, teamId)).isEqualTo(200L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM team_loyalty_transactions WHERE team_id=?", Long.class, teamId)).isEqualTo(200L);
        assertThat(jdbc.queryForObject("SELECT SUM(balance) FROM team_loyalty_accounts WHERE team_id=?", Long.class, teamId)).isEqualTo(200L);
    }

    @Test
    void AC28_申請insert対staff先行でも有効な二重加算を生まない() throws Exception {
        LoyaltyWriteCommand submit = new LoyaltyWriteCommand(teamId, 20L, 20L, ruleId, 0, 0,
                RequestOperation.SELF_SUBMIT, UUID.randomUUID(), new byte[32], 30L, "受付");
        var results = parallel(2, index -> index == 0 ? attempt(() -> writer.submit(submit))
                : attempt(() -> writer.award(command(UUID.randomUUID(), 0, new byte[32]))));
        assertThat(results.get(1).value()).isInstanceOf(TransactionResponse.class);
        assertThat(results.get(1).error()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM team_loyalty_transactions WHERE team_id=?", Long.class, teamId)).isEqualTo(1L);
        assertThat(store.getAccounts().findByTeamIdAndUserId(teamId, 20L)).get().extracting("balance").isEqualTo(1);
        if (results.get(0).value() instanceof LoyaltySubmissionOutcome submitted) {
            assertThat(submitted.request()).isNotNull();
            var saved = store.getRequests().findById(submitted.request().getId()).orElseThrow();
            assertThat(statePolicy.evaluate(saved, store.getPrograms().findByTeamId(teamId).orElseThrow(),
                    store.getRules().findById(ruleId).orElseThrow(), null, true, 1, LocalDateTime.of(2026, 10, 16, 23, 50)))
                    .isEqualTo(new LoyaltyEffectiveRequestState(RequestStatus.SUPERSEDED, RequestReason.DAILY_AWARDED));
        } else {
            assertThat(results.get(0).error()).isEqualTo(LoyaltyErrorCode.LOYALTY_005);
        }
    }

    @Test
    void AC30_mode更新と同一SELF_SUBMITkeyが競合しても台帳と申請を両方作らない() throws Exception {
        UUID key = UUID.randomUUID(); byte[] nextHash = new byte[32]; nextHash[0] = 1;
        LoyaltyWriteCommand oldMode = new LoyaltyWriteCommand(teamId, 20L, 20L, ruleId, 0, 0,
                RequestOperation.SELF_SUBMIT, key, new byte[32], 30L, "受付");
        LoyaltyWriteCommand newMode = new LoyaltyWriteCommand(teamId, 20L, 20L, ruleId, 0, 1,
                RequestOperation.SELF_SUBMIT, key, nextHash, 30L, "受付");
        var results = parallel(3, index -> switch (index) {
            case 0 -> attempt(() -> settings.updateProgram(teamId, new com.mannschaft.app.loyalty.dto.UpdateProgramRequest(
                    0L, true, Audience.ALL_AUTHENTICATED, SelfAwardMode.AUTO)));
            case 1 -> attempt(() -> writer.submit(oldMode));
            default -> attempt(() -> writer.submit(newMode));
        });
        assertThat(results.get(0).error()).isNull();
        for (int index = 1; index < results.size(); index++) {
            assertThat(results.get(index).error()).isIn(null, LoyaltyErrorCode.LOYALTY_006, LoyaltyErrorCode.LOYALTY_007);
        }
        WriteAttempt retry = attempt(() -> writer.submit(newMode));
        if (retry.error() == null) assertThat(retry.value()).isInstanceOf(LoyaltySubmissionOutcome.class);
        else assertThat(retry.error()).isEqualTo(LoyaltyErrorCode.LOYALTY_006);
        long transactions = jdbc.queryForObject("SELECT COUNT(*) FROM team_loyalty_transactions WHERE team_id=?", Long.class, teamId);
        long requests = jdbc.queryForObject("SELECT COUNT(*) FROM team_loyalty_award_requests WHERE team_id=?", Long.class, teamId);
        assertThat(transactions + requests).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM team_loyalty_accounts WHERE team_id=?", Long.class, teamId)).isEqualTo(transactions);
    }

    private WriteAttempt attempt(java.util.function.Supplier<Object> work) {
        try { return new WriteAttempt(work.get(), null); }
        catch (com.mannschaft.app.common.BusinessException exception) { return new WriteAttempt(null, exception.getErrorCode()); }
    }
    private record WriteAttempt(Object value, com.mannschaft.app.common.ErrorCode error) { }

    private <T> java.util.List<T> parallel(int count, java.util.function.IntFunction<T> action) throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(count);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<T>>();
            for (int index = 0; index < count; index++) {
                final int task = index;
                futures.add(workers.submit(() -> { ready.countDown(); start.await(); return action.apply(task); }));
            }
            try { assertThat(ready.await(30, java.util.concurrent.TimeUnit.SECONDS)).as("全要求を同時開始前に準備").isTrue(); }
            finally { start.countDown(); }
            var results = new java.util.ArrayList<T>();
            for (var future : futures) results.add(future.get(120, java.util.concurrent.TimeUnit.SECONDS));
            return results;
        }
    }
    @Test
    void AC08_規約JVM_JSTで固定Clockのアプリ時刻とDB_UTC時刻が往復する() {
        assertThat(java.util.TimeZone.getDefault().toZoneId())
                .isEqualTo(com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE);
        TransactionResponse result = writer.award(command(UUID.randomUUID(), 0, new byte[32]));
        assertThat(result).isNotNull();
        assertThat(result.createdAt()).isEqualTo(LocalDateTime.of(2026, 10, 16, 23, 50));
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') FROM team_loyalty_transactions WHERE team_id=?", String.class, teamId))
                .isEqualTo("2026-10-16 14:50:00");
        assertThat(store.getTransactions().findById(result.id())).get()
                .extracting("createdAt").isEqualTo(LocalDateTime.of(2026, 10, 16, 23, 50));
    }
}
