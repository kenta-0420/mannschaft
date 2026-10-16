package com.mannschaft.app.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.loyalty.entity.LoyaltyAccountEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyTransactionEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WriterITの実Flyway/MySQLへ相乗りするReader試練。新しいcontextは作らない。
 * <p>実装後の読取・SQL補強であり、未実装時のTDD red証拠には数えない。
 * Entityで所有行だけを作成し、finallyの自TEAM限定JPQLで片付ける。</p>
 */
final class LoyaltyQueryReaderFixture {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 23, 50);
    private static final String INDEX = "idx_tlar_pending_current";
    private static final String PENDING_SQL = "SELECT r.* FROM team_loyalty_award_requests r "
            + "WHERE r.team_id=? AND r.status='PENDING' AND r.program_version=1 AND r.rule_version=1 "
            + "AND r.award_on=? AND r.expires_at>? ";
    private static final String SEEK_SQL = "AND (r.expires_at>? OR (r.expires_at=? AND r.id>?)) ";
    private static final String ORDER_SQL = "ORDER BY r.expires_at ASC,r.id ASC LIMIT 201";
    private final LoyaltyQueryReader reader;
    private final TransactionTemplate transaction;
    private final EntityManagerFactory factory;
    private final JdbcTemplate jdbc;
    private final List<Long> teams = new ArrayList<>();
    private final Long actor = identity();
    private final Long other = actor + 2_000_000;
    private int code;

    private LoyaltyQueryReaderFixture(LoyaltyQueryReader reader, PlatformTransactionManager manager,
            JdbcTemplate jdbc) {
        this.reader = reader;
        this.transaction = new TransactionTemplate(manager);
        assertThat(manager).isInstanceOf(JpaTransactionManager.class);
        this.factory = Objects.requireNonNull(((JpaTransactionManager) manager).getEntityManagerFactory());
        this.jdbc = jdbc;
    }

    static void verifyScopeAndSeek(LoyaltyQueryReader reader, PlatformTransactionManager manager, JdbcTemplate jdbc) {
        var fixture = new LoyaltyQueryReaderFixture(reader, manager, jdbc);
        try { fixture.scopeAndSeek(); }
        finally { fixture.cleanup(); }
    }

    static void verifyPendingIndexWithOldVersions(LoyaltyQueryReader reader, PlatformTransactionManager manager,
            JdbcTemplate jdbc) {
        var fixture = new LoyaltyQueryReaderFixture(reader, manager, jdbc);
        try { fixture.pendingIndexWithOldVersions(); }
        finally { fixture.cleanup(); }
    }

    private void scopeAndSeek() {
        Long team = team(); Long anotherTeam = team();
        var ownedAccounts = new ArrayList<LoyaltyAccountEntity>();
        var ownedTransactions = new ArrayList<LoyaltyTransactionEntity>();
        var personal = new ArrayList<LoyaltyAwardRequestEntity>();
        var pending = new ArrayList<LoyaltyAwardRequestEntity>();
        var rules = new ArrayList<UUID>();
        inTransaction(em -> {
            rules.add(seedTeam(em, team)); rules.add(seedTeam(em, anotherTeam));
            var account = account(team, actor); var another = account(anotherTeam, actor);
            ownedAccounts.add(account); ownedAccounts.add(another);
            em.persist(account); em.persist(another);
            var foreign = account(team, other); em.persist(foreign); em.flush();
            for (int i = 0; i < 3; i++) {
                var row = transaction(team, actor, account.getId(), rules.getFirst(), i);
                em.persist(row); ownedTransactions.add(row);
            }
            em.persist(transaction(team, other, foreign.getId(), rules.getFirst(), 0));
            em.persist(transaction(anotherTeam, actor, another.getId(), rules.getLast(), 0));
            for (int i = 0; i < 21; i++) {
                var row = request(team, i == 0 ? actor : actor + i + 100, rules.getFirst(), 1,
                        NOW.plusMinutes(1 + i / 3));
                em.persist(row); pending.add(row); if (i == 0) personal.add(row);
            }
            for (Long ownerTeam : List.of(team, anotherTeam)) {
                UUID rule = ownerTeam.equals(team) ? rules.getFirst() : rules.getLast();
                var row = request(ownerTeam, actor, rule, 1, NOW.minusSeconds(1));
                row.close(RequestStatus.EXPIRED, RequestReason.TTL_EXPIRED, null, null, null, null, null, NOW);
                em.persist(row); personal.add(row);
            }
            // 各WHERE条件を単独で外す保存行を置き、候補SQLの絞込自体を確認する。
            em.persist(request(team, other + 100, rules.getFirst(), 0, NOW.plusMinutes(5)));
            var yesterday = request(team, other + 101, rules.getFirst(), 1, NOW.plusMinutes(5))
                    .toBuilder().awardOn(NOW.toLocalDate().minusDays(1)).build();
            em.persist(yesterday);
            em.persist(request(team, other + 102, rules.getFirst(), 1, NOW.minusSeconds(1)));
            em.flush(); em.clear();
        });
        ownedAccounts.sort(Comparator.comparing(LoyaltyAccountEntity::getId));
        assertThat(measured(1, () -> reader.accounts(actor, null, 1))).extracting(LoyaltyAccountEntity::getId)
                .containsExactly(ownedAccounts.getFirst().getId());
        assertThat(measured(1, () -> reader.accounts(actor, ownedAccounts.getFirst().getId(), 2)))
                .extracting(LoyaltyAccountEntity::getId).containsExactly(ownedAccounts.getLast().getId());
        ownedTransactions.sort(Comparator.comparing(LoyaltyTransactionEntity::getId).reversed());
        var firstTransactions = measured(1, () -> reader.transactions(actor, team, null, 2));
        assertThat(firstTransactions).extracting(LoyaltyTransactionEntity::getId)
                .containsExactly(ownedTransactions.get(0).getId(), ownedTransactions.get(1).getId());
        assertThat(measured(1, () -> reader.transactions(actor, team, firstTransactions.getLast().getId(), 2)))
                .extracting(LoyaltyTransactionEntity::getId).containsExactly(ownedTransactions.getLast().getId());

        personal.sort(Comparator.comparing(LoyaltyAwardRequestEntity::getId).reversed());
        var myFirst = measured(5, () -> reader.personal(actor, null, 2, NOW));
        assertThat(myFirst.rows()).extracting(LoyaltyAwardRequestEntity::getId)
                .containsExactly(personal.get(0).getId(), personal.get(1).getId());
        assertThat(measured(5, () -> reader.personal(actor, myFirst.rows().getLast().getId(), 2, NOW)).rows())
                .extracting(LoyaltyAwardRequestEntity::getId).containsExactly(personal.getLast().getId());
        assertThatThrownBy(() -> reader.personalRequest(other, personal.getFirst().getId(), NOW))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(LoyaltyErrorCode.LOYALTY_001);
        assertThatThrownBy(() -> reader.staffRequest(anotherTeam, pending.getFirst().getId(), NOW))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(LoyaltyErrorCode.LOYALTY_001);

        var staffFirst = measured(5, () -> reader.staff(team, false, null, 1, NOW));
        var staffAll = measured(5, () -> reader.staff(team, false, null, 201, NOW));
        assertThat(staffAll.rows()).allMatch(r -> r.getTeamId().equals(team));
        assertThat(staffAll.rows()).hasSize(25);
        var staffNext = measured(5, () -> reader.staff(team, false,
                new LoyaltyCursorCodec.Position(staffFirst.rows().getFirst().getId(), null), 201, NOW));
        assertThat(staffNext.rows()).extracting(LoyaltyAwardRequestEntity::getId)
                .containsExactlyElementsOf(staffAll.rows().subList(1, staffAll.rows().size()).stream()
                        .map(LoyaltyAwardRequestEntity::getId).toList());
        pending.sort(Comparator.comparing(LoyaltyAwardRequestEntity::getExpiresAt)
                .thenComparing(LoyaltyAwardRequestEntity::getId));
        var pendingFirst = measured(7, () -> reader.staff(team, true, null, 1, NOW));
        var pendingAll = measured(7, () -> reader.staff(team, true, null, 201, NOW));
        assertThat(pendingAll.rows()).extracting(LoyaltyAwardRequestEntity::getId)
                .containsExactlyElementsOf(pending.stream().map(LoyaltyAwardRequestEntity::getId).toList());
        var last = pendingFirst.rows().getFirst();
        assertThat(measured(7, () -> reader.staff(team, true,
                new LoyaltyCursorCodec.Position(last.getId(), last.getExpiresAt()), 201, NOW)).rows())
                .extracting(LoyaltyAwardRequestEntity::getId)
                .containsExactlyElementsOf(pending.subList(1,pending.size()).stream().map(LoyaltyAwardRequestEntity::getId).toList());
        assertThat(pendingAll.awarded()).contains(new LoyaltyQueryReader.DailyKey(team, actor, rules.getFirst(), NOW.toLocalDate()));
        assertThat(pendingAll.balances()).containsEntry(new LoyaltyQueryReader.AccountKey(team, actor), 7);
    }

    /** 旧版10万件を現行候補SQLから除外し、先頭/seek双方の索引とsort非発生を実測する。 */
    private void pendingIndexWithOldVersions() {
        Long team = team(); var rule = new UUID[1];
        inTransaction(em -> {
            rule[0] = seedTeam(em, team);
            em.unwrap(Session.class).setJdbcBatchSize(100);
            for (int i = 0; i < 100_000; i++) {
                em.persist(request(team, actor + i, rule[0], 0, NOW.plusMinutes(5)));
                if ((i + 1) % 500 == 0) { em.flush(); em.clear(); }
            }
            em.flush(); em.clear();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM team_loyalty_award_requests WHERE team_id=? AND program_version=0",
                Long.class, team)).isEqualTo(100_000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM team_loyalty_award_requests WHERE team_id=? AND award_on=?",
                Long.class, team, NOW.toLocalDate().toString())).isEqualTo(100_000L);
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(expires_at,'%Y-%m-%d %H:%i:%s') "
                + "FROM team_loyalty_award_requests WHERE team_id=? LIMIT 1", String.class, team))
                .as("EXPLAINのbind時刻と実JPAのUTC格納値が一致する")
                .isEqualTo(databaseTime(NOW.plusMinutes(5)));
        assertThat(measured(3, () -> reader.staff(team, true, null, 201, NOW)).rows()).isEmpty();
        List<Object> arguments = List.of(team, NOW.toLocalDate().toString(), databaseTime(NOW));
        verifyPlan(PENDING_SQL + ORDER_SQL, arguments.toArray());
        var seekArguments = new ArrayList<Object>(arguments);
        seekArguments.add(databaseTime(NOW.plusMinutes(1)));
        seekArguments.add(databaseTime(NOW.plusMinutes(1)));
        seekArguments.add(uuidBytes(new UUID(0, 0)));
        verifyPlan(PENDING_SQL + SEEK_SQL + ORDER_SQL, seekArguments.toArray());
        // 件数制約も実データで確認する。旧版集合は残したまま現版202件を同じ索引へ追加する。
        inTransaction(em -> {
            for (int i = 0; i < 202; i++) em.persist(request(team, actor + 200_000 + i, rule[0], 1, NOW.plusMinutes(5)));
            em.flush(); em.clear();
        });
        assertThat(measured(7, () -> reader.staff(team, true, null, 201, NOW)).rows()).hasSize(201);
    }

    private void verifyPlan(String sql, Object[] arguments) {
        var plans = jdbc.queryForList("EXPLAIN " + sql, arguments);
        assertThat(plans).hasSize(1);
        var plan = plans.getFirst();
        assertThat(String.valueOf(plan.get("key"))).isEqualTo(INDEX);
        assertThat(String.valueOf(plan.get("type"))).isIn("range", "ref");
        assertThat(String.valueOf(plan.get("Extra")).toLowerCase(java.util.Locale.ROOT)).doesNotContain("filesort");
        System.out.println("AC30 Reader EXPLAIN key=" + plan.get("key") + " type=" + plan.get("type")
                + " rows=" + plan.get("rows") + " Extra=" + plan.get("Extra"));
    }

    private <T> T measured(long expected, Supplier<T> action) {
        Statistics statistics = factory.unwrap(SessionFactory.class).getStatistics();
        boolean previous = statistics.isStatisticsEnabled(); statistics.setStatisticsEnabled(true);
        try {
            long before = statistics.getPrepareStatementCount(); T result = action.get();
            assertThat(statistics.getPrepareStatementCount() - before).as("Reader JDBC statement数").isEqualTo(expected);
            return result;
        } finally { statistics.setStatisticsEnabled(previous); }
    }

    private UUID seedTeam(EntityManager em, Long team) {
        em.persist(LoyaltyTestFixture.program(team).toBuilder().version(1).build());
        var rule = LoyaltyTestFixture.rule(team).toBuilder().version(1).build();
        em.persist(rule); em.flush(); return rule.getId();
    }
    private LoyaltyAccountEntity account(Long team, Long user) {
        return LoyaltyAccountEntity.builder().teamId(team).userId(user).balance(7).lifetimeEarned(7)
                .createdAt(NOW.minusHours(1)).updatedAt(NOW).build();
    }
    private LoyaltyTransactionEntity transaction(Long team, Long user, UUID account, UUID rule, int day) {
        return LoyaltyTransactionEntity.builder().teamId(team).userId(user).actorUserId(other).accountId(account)
                .kind(TransactionKind.AWARD).channel(TransactionChannel.STAFF).requestOperation(RequestOperation.STAFF_AWARD)
                .delta(1).balanceAfter(7).ruleId(rule).awardOn(NOW.toLocalDate().minusDays(day)).label("確定ラベル")
                .ruleOrRewardVersion(1).programVersion(1).requestKey(UUID.randomUUID()).payloadHash(new byte[32])
                .createdAt(NOW.minusHours(1)).updatedAt(NOW).build();
    }
    private LoyaltyAwardRequestEntity request(Long team, Long user, UUID rule, long version, LocalDateTime expiry) {
        return LoyaltyAwardRequestEntity.builder().teamId(team).userId(user).ruleId(rule).sourceLocationId(other)
                .programVersion(version).ruleVersion(version).points(1).ruleName("来店").locationName("試験受付")
                .awardOn(NOW.toLocalDate()).expiresAt(expiry).status(RequestStatus.PENDING).verificationCode(code(++code))
                .submitKey(UUID.randomUUID()).submitPayloadHash(new byte[32]).createdAt(NOW.minusHours(1)).updatedAt(NOW).build();
    }
    private String code(int number) {
        char[] value = "AAAAAAAA".toCharArray(); String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        for (int i = value.length - 1; i >= 0; i--) { value[i] = alphabet.charAt(number & 31); number >>>= 5; }
        return new String(value);
    }
    private Long team() { Long id = identity(); teams.add(id); return id; }
    private static Long identity() { return 10_000_000_000L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 1_000_000_000_000L); }
    private void inTransaction(Consumer<EntityManager> action) {
        transaction.executeWithoutResult(status -> action.accept(Objects.requireNonNull(
                EntityManagerFactoryUtils.getTransactionalEntityManager(factory))));
    }
    private String databaseTime(LocalDateTime value) {
        // UTC JDBC格納と同じ壁時計値を文字列で渡し、JdbcTemplateのTimestamp再変換を避ける。
        return value.atZone(com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE)
                .withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
    private byte[] uuidBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    private void cleanup() {
        if (teams.isEmpty()) return;
        inTransaction(em -> {
            for (String entity : List.of("LoyaltyAwardRequestEntity", "LoyaltyTransactionEntity", "LoyaltyAccountEntity",
                    "LoyaltyRewardEntity", "LoyaltyRuleEntity", "LoyaltyProgramEntity")) {
                em.createQuery("delete from " + entity + " e where e.teamId in :teams").setParameter("teams", teams).executeUpdate();
            }
        });
    }
}
