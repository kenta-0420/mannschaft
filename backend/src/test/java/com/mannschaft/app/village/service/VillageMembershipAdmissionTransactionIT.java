package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.persistence.probe.UnsignedIdProbeRepository;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.JoinRequestReviewRequest;
import com.mannschaft.app.village.dto.MembershipJoinRequest;
import com.mannschaft.app.village.dto.VillageCreationRequestCreateRequest;
import com.mannschaft.app.village.dto.VillageCreationRequestReviewRequest;
import com.mannschaft.app.village.entity.VillageCreationRequestEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageInvitationEntity;
import com.mannschaft.app.village.entity.VillageJoinRequestEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageCreationRequestRepository;
import com.mannschaft.app.village.repository.VillageInvitationRepository;
import com.mannschaft.app.village.repository.VillageJoinRequestRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.MappedSuperclass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP1808: 五つの入村writerが実Flyway制約と全業務TXを通る最初の試練。
 *
 * <p>共有ddl-auto=createスキーマではCHECK/生成列一意を証明できないため、
 * BACKEND_CODING_CONVENTIONの専用実スキーマ金型を使う。実DB・認可・サービスは差し替えない。
 * 初波20件と後続ケースが一つの専用コンテナ/コンテキストを共有し、テスト全体TXは置かない。
 * 初波20件は五入口の容量と原子性、追加23件は限定した並行参加・外側TX rollback・RR再判定を扱う。
 * 全並行経路・HTTP認可・TEAM/ORG認可・一般DB競合の解消は、この試練の合格に代用しない。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.docker.compose.enabled=false"
})
@Import(VillageMembershipAdmissionTransactionIT.ProductionJpaConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.village.service.VillageMembershipAdmissionTransactionIT#isDockerAvailable")
@DisplayName("CMP1808 五経路のUSER枠と業務原子性（実Flyway/MySQL）")
@Timeout(120)
class VillageMembershipAdmissionTransactionIT {

    /** 実Flywayが持たないtest専用型を、本番source setの検証対象へ混ぜない。 */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackages = "com.mannschaft.app",
            excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = UnsignedIdProbeRepository.class))
    static class ProductionJpaConfiguration {

        @Bean
        PersistenceManagedTypes persistenceManagedTypes() throws ClassNotFoundException {
            // 既Flyway金型と同じ四種を保持し、abstractな基底型も落とさない。
            ClassPathScanningCandidateComponentProvider scanner =
                    new ClassPathScanningCandidateComponentProvider(false) {
                        @Override
                        protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                            return true;
                        }
                    };
            scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
            scanner.addIncludeFilter(new AnnotationTypeFilter(MappedSuperclass.class));
            scanner.addIncludeFilter(new AnnotationTypeFilter(Embeddable.class));
            scanner.addIncludeFilter(new AnnotationTypeFilter(Converter.class));

            List<String> names = new ArrayList<>();
            for (BeanDefinition definition : scanner.findCandidateComponents("com.mannschaft.app")) {
                String className = definition.getBeanClassName();
                if (className != null && !isFromTestSourceSet(Class.forName(className))) {
                    names.add(className);
                }
            }
            assertThat(names).as("本番source setのJPA管理型").isNotEmpty();
            return PersistenceManagedTypes.of(names.toArray(String[]::new));
        }

        /** 出所不明は既Flyway金型と同じくvalidate側へ残す。 */
        private static boolean isFromTestSourceSet(Class<?> clazz) {
            java.security.ProtectionDomain domain = clazz.getProtectionDomain();
            if (domain == null || domain.getCodeSource() == null
                    || domain.getCodeSource().getLocation() == null) {
                return false;
            }
            return domain.getCodeSource().getLocation().getPath().contains("/classes/java/test");
        }
    }

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("cmp1808_admission_contract")
            .withUsername("test").withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw,size=512m"))
            .withCommand("--log_bin_trust_function_creators=1",
                    "--innodb_buffer_pool_size=64M", "--max_connections=24")
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withMemory(1024L * 1024 * 1024).withMemorySwap(1024L * 1024 * 1024))
            .withStartupTimeout(Duration.ofSeconds(120))
            .withReuse(false);

    static {
        if (isDockerAvailable()) {
            MYSQL.start();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception unavailable) {
            return false;
        }
    }

    /** 外部キャッシュ境界のみ。DB/認可/業務Beanは実物。 */
    @MockitoBean private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Autowired private VillageMembershipService memberships;
    @Autowired private VillageJoinRequestService joins;
    @Autowired private VillageInvitationService invitations;
    @Autowired private VillageCreationRequestService creations;
    @Autowired private VillageRepository villageRepository;
    @Autowired private VillageJoinRequestRepository joinRepository;
    @Autowired private VillageInvitationRepository invitationRepository;
    @Autowired private VillageCreationRequestRepository creationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SecretTokenVault tokenVault;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private enum Writer { FREE, JOIN_APPROVAL, INVITATION, CREATION_AUTO, CREATION_APPROVAL }

    private record Fixture(Writer writer, long subjectId, long reviewerId, UUID villageId,
                           UUID joinId, UUID invitationId, String token, UUID creationId,
                           String slug) { }

    @BeforeEach
    @Timeout(120)
    void verifyRealSchemaAndProxy() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        for (Object service : List.of(memberships, joins, invitations, creations)) {
            assertThat(AopUtils.isAopProxy(service)).as("実Spring TX proxy").isTrue();
        }
        List<String> managedClasses = entityManagerFactory.getMetamodel().getManagedTypes().stream()
                .map(type -> type.getJavaType().getName()).toList();
        assertThat(managedClasses).as("実JPA metamodelの本番管理型とtest専用型の境界")
                .contains(UserEntity.class.getName(), VillageEntity.class.getName(),
                        "com.mannschaft.app.village.entity.VillageMembershipEntity",
                        VillageJoinRequestEntity.class.getName(), VillageInvitationEntity.class.getName(),
                        VillageCreationRequestEntity.class.getName())
                .doesNotContain("com.mannschaft.app.common.architecture.fixtures.DummyD6ExposedEntity",
                        "com.mannschaft.app.common.persistence.probe.UnsignedIdProbeEntity");
        String ddl = jdbc.queryForObject("SHOW CREATE TABLE village_memberships",
                (rs, row) -> rs.getString(2));
        assertThat(ddl).contains("ck_vm_active_user_slot", "uk_vm_user_active_slot", "uk_vm_active_subject");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE success=1 AND script='V255.20261031062322__enforce_village_membership_slots.sql'",
                Integer.class)).isEqualTo(1);
    }

    static Stream<Arguments> availableSlots() {
        return Stream.of(Writer.values()).flatMap(writer -> Stream.of(0, 99)
                .map(count -> Arguments.of(writer, count)));
    }

    static Stream<Arguments> fullSlots() {
        return Stream.of(Writer.values()).flatMap(writer -> Stream.of(false, true)
                .map(banned -> Arguments.of(writer, banned)));
    }

    @ParameterizedTest(name = "{0}: 既所在籍{1}件")
    @MethodSource("availableSlots")
    void 入村_五経路で空枠がある_本人slotと全業務が確定する(Writer writer, int existingCount) {
        Fixture fixture = prepare(writer);
        fillSlots(fixture.subjectId(), existingCount, false);

        UUID createdVillage = invoke(fixture);

        assertThat(activeCount(fixture.subjectId())).isEqualTo(existingCount + 1);
        assertThat(jdbc.queryForObject("SELECT user_slot FROM village_memberships "
                + "WHERE village_id=UUID_TO_BIN(?) AND subject_type='USER' AND subject_id=? AND left_at IS NULL",
                Integer.class, createdVillage.toString(), fixture.subjectId())).isEqualTo(existingCount + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT user_slot) FROM village_memberships "
                + "WHERE subject_type='USER' AND subject_id=? AND left_at IS NULL",
                Integer.class, fixture.subjectId())).isEqualTo(existingCount + 1);
        assertCommittedSideEffects(fixture, createdVillage);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @ParameterizedTest(name = "{0}: BAN在籍含む={1}")
    @MethodSource("fullSlots")
    void 入村_百枠占有_全経路で上限拒否し業務が不変(Writer writer, boolean includeBanned) {
        Fixture fixture = prepare(writer);
        fillSlots(fixture.subjectId(), 100, includeBanned);
        List<Map<String, Object>> before = actorMemberships(fixture.subjectId());
        List<Map<String, Object>> sideEffects = sideEffectSnapshot(fixture);

        assertThatThrownBy(() -> invoke(fixture))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(VillageErrorCode.PARTICIPATION_LIMIT_EXCEEDED));

        assertThat(actorMemberships(fixture.subjectId())).isEqualTo(before);
        assertThat(sideEffectSnapshot(fixture)).isEqualTo(sideEffects);
        assertThat(activeCount(fixture.subjectId())).isEqualTo(100);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }


    private enum SlotShape { HOLE, LEFT_HISTORY, BANNED_OCCUPANT }
    private enum ReviewAction { REJECT, WITHDRAW }
    private record InvocationOutcome(UUID villageId, RuntimeException failure) { }

    static Stream<Arguments> allWriters() {
        return Stream.of(Writer.values()).map(Arguments::of);
    }

    static Stream<Arguments> slotShapes() {
        return Stream.of(SlotShape.values()).map(Arguments::of);
    }

    static Stream<Arguments> cachedRequests() {
        return Stream.of(Writer.JOIN_APPROVAL, Writer.CREATION_APPROVAL)
                .flatMap(writer -> Stream.of(ReviewAction.values())
                        .map(action -> Arguments.of(writer, action)));
    }

    static Stream<Arguments> crossEntryPairs() {
        return Stream.of(
                new Writer[]{Writer.FREE, Writer.INVITATION},
                new Writer[]{Writer.JOIN_APPROVAL, Writer.CREATION_AUTO},
                new Writer[]{Writer.CREATION_APPROVAL, Writer.FREE})
                .flatMap(pair -> Stream.of(98, 99)
                        .map(count -> Arguments.of(pair[0], pair[1], count)));
    }

    @ParameterizedTest(name = "{0}: 外側TX rollback")
    @MethodSource("allWriters")
    void 入村_外側REQUIREDのrollbackで全業務が戻る(Writer writer) {
        Fixture fixture = prepare(writer);
        List<Map<String, Object>> membershipsBefore = actorMemberships(fixture.subjectId());
        List<Map<String, Object>> effectsBefore = sideEffectSnapshot(fixture);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setTimeout(20);

        outer.executeWithoutResult(status -> {
            UUID createdVillage = invoke(fixture);
            villageRepository.flush();
            assertCommittedSideEffects(fixture, createdVillage);
            status.setRollbackOnly();
        });

        assertThat(actorMemberships(fixture.subjectId())).isEqualTo(membershipsBefore);
        assertThat(sideEffectSnapshot(fixture)).isEqualTo(effectsBefore);
    }

    @ParameterizedTest(name = "最小空き: {0}")
    @MethodSource("slotShapes")
    void 入村_穴と退村履歴とBANを区別して最小空きを使う(SlotShape shape) {
        Fixture fixture = prepare(Writer.FREE);
        UUID firstVillage = newVillage("first-" + UUID.randomUUID(), VillageJoinPolicy.FREE, fixture.subjectId());
        insertMembership(firstVillage, fixture.subjectId(), 1,
                shape == SlotShape.BANNED_OCCUPANT, "VILLAGER");
        UUID thirdVillage = newVillage("third-" + UUID.randomUUID(), VillageJoinPolicy.FREE, fixture.subjectId());
        insertMembership(thirdVillage, fixture.subjectId(), 3, false, "VILLAGER");
        if (shape == SlotShape.LEFT_HISTORY) {
            UUID secondVillage = newVillage("left-" + UUID.randomUUID(), VillageJoinPolicy.FREE, fixture.subjectId());
            UUID leaving = insertMembership(secondVillage, fixture.subjectId(), 2, false, "VILLAGER");
            memberships.leave(secondVillage, leaving, fixture.subjectId());
            assertThat(jdbc.queryForObject("SELECT user_slot FROM village_memberships WHERE id=UUID_TO_BIN(?)",
                    Integer.class, leaving.toString())).isEqualTo(2);
        }

        UUID createdVillage = invoke(fixture);

        assertThat(jdbc.queryForObject("SELECT user_slot FROM village_memberships "
                + "WHERE village_id=UUID_TO_BIN(?) AND subject_type='USER' AND subject_id=? AND left_at IS NULL",
                Integer.class, createdVillage.toString(), fixture.subjectId())).isEqualTo(2);
        assertThat(activeCount(fixture.subjectId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT user_slot) FROM village_memberships "
                + "WHERE subject_type='USER' AND subject_id=? AND left_at IS NULL",
                Integer.class, fixture.subjectId())).isEqualTo(3);
        if (shape == SlotShape.BANNED_OCCUPANT) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM village_memberships "
                    + "WHERE subject_type='USER' AND subject_id=? AND left_at IS NULL AND banned_at IS NOT NULL",
                    Integer.class, fixture.subjectId())).isEqualTo(1);
        }
    }

    @Test
    void 入村_RRで先にsnapshotを作っても別TXの百枠目を読む() {
        Fixture fixture = prepare(Writer.FREE);
        fillSlots(fixture.subjectId(), 99, false);
        List<Map<String, Object>> effectsBefore = sideEffectSnapshot(fixture);

        assertThatThrownBy(() -> isolated(() -> {
            assertThat(activeCount(fixture.subjectId())).isEqualTo(99);
            isolated(() -> {
                UUID lastVillage = newVillage("last-" + UUID.randomUUID(), VillageJoinPolicy.FREE, fixture.subjectId());
                insertMembership(lastVillage, fixture.subjectId(), 100, false, "VILLAGER");
                return null;
            });
            return invoke(fixture);
        })).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(VillageErrorCode.PARTICIPATION_LIMIT_EXCEEDED));

        assertThat(activeCount(fixture.subjectId())).isEqualTo(100);
        assertThat(sideEffectSnapshot(fixture)).isEqualTo(effectsBefore);
    }

    @ParameterizedTest(name = "{0}: cached PENDING後 {1}")
    @MethodSource("cachedRequests")
    void 承認_外側のcached申請を別TXの最新statusで再判定する(Writer writer, ReviewAction action) {
        Fixture fixture = prepare(writer);
        VillageErrorCode expected = writer == Writer.JOIN_APPROVAL
                ? VillageErrorCode.VILLAGE_JOIN_REQUEST_ALREADY_REVIEWED
                : action == ReviewAction.REJECT ? VillageErrorCode.CREATION_REQUEST_REJECTED
                : VillageErrorCode.CREATION_REQUEST_ALREADY_REVIEWED;

        assertThatThrownBy(() -> isolated(() -> {
            if (writer == Writer.JOIN_APPROVAL) {
                assertThat(joinRepository.findById(fixture.joinId()).orElseThrow().getStatus())
                        .isEqualTo(VillageRequestStatus.PENDING);
            } else {
                assertThat(creationRepository.findById(fixture.creationId()).orElseThrow().getStatus())
                        .isEqualTo(VillageRequestStatus.PENDING);
            }
            isolated(() -> {
                if (writer == Writer.JOIN_APPROVAL) {
                    if (action == ReviewAction.REJECT) {
                        joins.reject(fixture.villageId(), fixture.joinId(), fixture.reviewerId(),
                                new JoinRequestReviewRequest("契約試練"));
                    } else {
                        joins.withdraw(fixture.villageId(), fixture.joinId(), fixture.subjectId());
                    }
                } else if (action == ReviewAction.REJECT) {
                    creations.reject(fixture.creationId(), fixture.reviewerId(),
                            new VillageCreationRequestReviewRequest("契約試練"));
                } else {
                    creations.withdraw(fixture.creationId(), fixture.subjectId());
                }
                return null;
            });
            return invoke(fixture);
        })).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(expected));

        VillageRequestStatus status = action == ReviewAction.REJECT
                ? VillageRequestStatus.REJECTED : VillageRequestStatus.WITHDRAWN;
        if (writer == Writer.JOIN_APPROVAL) {
            assertThat(joinRepository.findById(fixture.joinId()).orElseThrow().getStatus()).isEqualTo(status);
        } else {
            VillageCreationRequestEntity fresh = creationRepository.findById(fixture.creationId()).orElseThrow();
            assertThat(fresh.getStatus()).isEqualTo(status);
            assertThat(fresh.getCreatedVillageId()).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM villages WHERE slug=?",
                    Integer.class, fixture.slug())).isZero();
        }
        assertThat(activeCount(fixture.subjectId())).isZero();
    }

    @Test
    void 招待_外側のcachedtokenを別TXの失効後に受諾しない() {
        Fixture fixture = prepare(Writer.INVITATION);

        assertThatThrownBy(() -> isolated(() -> {
            assertThat(invitationRepository.findById(fixture.invitationId()).orElseThrow().getRevokedAt()).isNull();
            isolated(() -> {
                invitations.revoke(fixture.villageId(), fixture.invitationId(), fixture.reviewerId());
                return null;
            });
            return invoke(fixture);
        })).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND));

        VillageInvitationEntity fresh = invitationRepository.findById(fixture.invitationId()).orElseThrow();
        assertThat(fresh.getRevokedAt()).isNotNull();
        assertThat(fresh.getUsedCount()).isZero();
        assertThat(activeCount(fixture.subjectId())).isZero();
    }

    @ParameterizedTest(name = "{0} + {1}: 同USER既所在籍{2}")
    @MethodSource("crossEntryPairs")
    void 入村_同USERの異なる入口を独立TXで競合させる(Writer firstWriter, Writer secondWriter,
                                                      int existingCount) throws Exception {
        long subject = newUser();
        List<Fixture> fixtures = List.of(prepare(firstWriter, subject), prepare(secondWriter, subject));
        fillSlots(subject, existingCount, false);
        List<List<Map<String, Object>>> effectsBefore = fixtures.stream().map(this::sideEffectSnapshot).toList();

        List<InvocationOutcome> results = concurrentInvoke(fixtures, existingCount);

        assertThat(results.stream().filter(result -> result.failure() == null).count())
                .isEqualTo(existingCount == 98 ? 2 : 1);
        assertThat(activeCount(subject)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT user_slot) FROM village_memberships "
                + "WHERE subject_type='USER' AND subject_id=? AND left_at IS NULL",
                Integer.class, subject)).isEqualTo(100);
        for (int index = 0; index < results.size(); index++) {
            InvocationOutcome result = results.get(index);
            if (result.failure() == null) {
                assertCommittedSideEffects(fixtures.get(index), result.villageId());
            } else {
                assertThat(result.failure()).isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(VillageErrorCode.PARTICIPATION_LIMIT_EXCEEDED));
                assertThat(sideEffectSnapshot(fixtures.get(index))).isEqualTo(effectsBefore.get(index));
            }
        }
    }

    @Test
    void 入村_異なるUSERの空枠への同時参加は双方確定する() throws Exception {
        // 二人を先に作り、その間へ他USERの在籍索引を挟まない。
        long firstSubject = newUser();
        long secondSubject = newUser();
        List<Fixture> fixtures = List.of(prepare(Writer.FREE, firstSubject), prepare(Writer.FREE, secondSubject));

        List<InvocationOutcome> results = concurrentInvoke(fixtures, 0);

        for (int index = 0; index < results.size(); index++) {
            assertThat(results.get(index).failure()).isNull();
            assertThat(activeCount(fixtures.get(index).subjectId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT user_slot FROM village_memberships "
                    + "WHERE subject_type='USER' AND subject_id=? AND left_at IS NULL",
                    Integer.class, fixtures.get(index).subjectId())).isEqualTo(1);
        }
    }

    /** 独立connectionのRR TX。SQLの待機を5秒、TXを20秒へ有界化しsession値を戻す。 */
    private <T> T isolated(Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(20);
        return transaction.execute(status -> {
            Integer previous = jdbc.queryForObject("SELECT @@session.innodb_lock_wait_timeout", Integer.class);
            jdbc.execute("SET SESSION innodb_lock_wait_timeout=5");
            try {
                return work.get();
            } finally {
                jdbc.execute("SET SESSION innodb_lock_wait_timeout=" + previous);
            }
        });
    }

    /** 両TXのsnapshot成立後に開始する。失敗はrollback完了後に親threadへ返す。 */
    private List<InvocationOutcome> concurrentInvoke(List<Fixture> fixtures, int existingCount) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch snapshotsReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<InvocationOutcome>> futures = new ArrayList<>();
        try {
            for (Fixture fixture : fixtures) {
                futures.add(executor.submit(() -> {
                    try {
                        UUID village = isolated(() -> {
                            assertThat(activeCount(fixture.subjectId())).isEqualTo(existingCount);
                            snapshotsReady.countDown();
                            try {
                                assertThat(start.await(10, TimeUnit.SECONDS)).as("開始latch").isTrue();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("開始待機を中断", interrupted);
                            }
                            return invoke(fixture);
                        });
                        return new InvocationOutcome(village, null);
                    } catch (RuntimeException failure) {
                        return new InvocationOutcome(null, failure);
                    }
                }));
            }
            assertThat(snapshotsReady.await(10, TimeUnit.SECONDS)).as("両RR snapshot成立").isTrue();
            start.countDown();
            List<InvocationOutcome> results = new ArrayList<>();
            for (Future<InvocationOutcome> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("worker終了").isTrue();
        }
    }


    static Stream<Arguments> sameVillageStates() {
        return Stream.of(false, true).map(Arguments::of);
    }

    @ParameterizedTest(name = "同村current-read: BAN={0}")
    @MethodSource("sameVillageStates")
    void 入村_RRの同村不存在snapshotより別TXの在籍とBANを優先する(boolean banned) {
        Fixture fixture = prepare(Writer.FREE);
        List<Map<String, Object>> effectsBefore = sideEffectSnapshot(fixture);

        assertThatThrownBy(() -> isolated(() -> {
            assertThat(activeCount(fixture.subjectId())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM village_memberships "
                    + "WHERE village_id=UUID_TO_BIN(?) AND subject_type='USER' AND subject_id=? AND left_at IS NULL",
                    Integer.class, fixture.villageId().toString(), fixture.subjectId())).isZero();
            isolated(() -> {
                insertMembership(fixture.villageId(), fixture.subjectId(), 1, banned, "VILLAGER");
                return null;
            });
            return invoke(fixture);
        })).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(
                        banned ? VillageErrorCode.MEMBER_BANNED : VillageErrorCode.ALREADY_MEMBER));

        assertThat(activeCount(fixture.subjectId())).isEqualTo(1);
        assertThat(actorMemberships(fixture.subjectId())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT user_slot FROM village_memberships "
                + "WHERE village_id=UUID_TO_BIN(?) AND subject_type='USER' AND subject_id=? AND left_at IS NULL",
                Integer.class, fixture.villageId().toString(), fixture.subjectId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM village_memberships "
                + "WHERE village_id=UUID_TO_BIN(?) AND subject_type='USER' AND subject_id=? "
                + "AND left_at IS NULL AND banned_at IS NOT NULL", Integer.class,
                fixture.villageId().toString(), fixture.subjectId())).isEqualTo(banned ? 1 : 0);
        assertThat(sideEffectSnapshot(fixture)).isEqualTo(effectsBefore);
    }

    static Stream<Arguments> sameVillagePairs() {
        return Stream.of(
                Arguments.of(Writer.FREE, Writer.FREE),
                Arguments.of(Writer.FREE, Writer.INVITATION),
                Arguments.of(Writer.JOIN_APPROVAL, Writer.INVITATION));
    }

    /** AC-1808-2: 同じUSERが同じ村へ別TXで同時に入村しても、現役所属は1件だけになる。 */
    @ParameterizedTest(name = "同一村 {0} + {1}")
    @MethodSource("sameVillagePairs")
    void 入村_同一村への同時入村は経路を問わず現役1件(Writer firstWriter, Writer secondWriter) throws Exception {
        long subject = newUser();
        long reviewer = newUser();
        String slug = "same-" + UUID.randomUUID().toString().replace("-", "");
        VillageJoinPolicy policy = firstWriter == Writer.JOIN_APPROVAL || secondWriter == Writer.JOIN_APPROVAL
                ? VillageJoinPolicy.APPROVAL : VillageJoinPolicy.FREE;
        UUID villageId = newVillage(slug, policy, reviewer);
        UUID headman = insertMembership(villageId, reviewer, 1, false, "HEADMAN");
        List<Fixture> fixtures = List.of(
                sameVillageFixture(firstWriter, subject, reviewer, villageId, headman, slug),
                sameVillageFixture(secondWriter, subject, reviewer, villageId, headman, slug));

        List<InvocationOutcome> results = concurrentInvoke(fixtures, 0);

        assertThat(results.stream().filter(result -> result.failure() == null).count()).isEqualTo(1);
        for (int index = 0; index < results.size(); index++) {
            InvocationOutcome result = results.get(index);
            Fixture fixture = fixtures.get(index);
            if (result.failure() == null) {
                assertCommittedSideEffects(fixture, villageId);
            } else {
                assertThat(result.failure()).isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(VillageErrorCode.ALREADY_MEMBER));
                if (fixture.invitationId() != null) {
                    assertThat(invitationRepository.findById(fixture.invitationId()).orElseThrow().getUsedCount())
                            .as("失敗側の招待使用回数は巻き戻る").isZero();
                }
                if (fixture.joinId() != null) {
                    assertThat(joinRepository.findById(fixture.joinId()).orElseThrow().getStatus())
                            .as("失敗側の申請はPENDINGのまま").isEqualTo(VillageRequestStatus.PENDING);
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM village_memberships WHERE village_id=UUID_TO_BIN(?) "
                + "AND subject_type='USER' AND subject_id=? AND left_at IS NULL",
                Integer.class, villageId.toString(), subject)).isEqualTo(1);
        assertThat(activeCount(subject)).isEqualTo(1);
    }

    /** AC-1808-5: TEAM/ORG の所属は USER 枠を消費しない（同じ数値IDでも別枠）。 */
    @Test
    void 入村_TEAMとORGの所属はUSER枠を消費しない() {
        Fixture fixture = prepare(Writer.FREE);
        fillSlots(fixture.subjectId(), 99, false);
        for (String type : List.of("TEAM", "ORGANIZATION")) {
            UUID other = newVillage("subject-" + UUID.randomUUID().toString().replace("-", ""),
                    VillageJoinPolicy.FREE, fixture.subjectId());
            jdbc.update("INSERT INTO village_memberships "
                    + "(id,village_id,subject_type,subject_id,role,joined_at,created_at,updated_at,version,profile_public) "
                    + "VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,'VILLAGER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,"
                    + "CURRENT_TIMESTAMP,0,1)", UUID.randomUUID().toString(), other.toString(), type, fixture.subjectId());
        }

        UUID joined = invoke(fixture);

        assertThat(activeCount(fixture.subjectId())).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT user_slot FROM village_memberships WHERE village_id=UUID_TO_BIN(?) "
                + "AND subject_type='USER' AND subject_id=? AND left_at IS NULL",
                Integer.class, joined.toString(), fixture.subjectId())).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM village_memberships WHERE subject_type<>'USER' "
                + "AND subject_id=? AND user_slot IS NOT NULL", Integer.class, fixture.subjectId())).isZero();
    }

    /** AC-1808-5: 退村した村へ再加入すると、空いた枠を再利用し履歴は残る。 */
    @Test
    void 入村_退村後の同村再加入は枠を再利用する() {
        Fixture fixture = prepare(Writer.FREE);
        fillSlots(fixture.subjectId(), 99, false);
        invoke(fixture);
        UUID membershipId = jdbc.queryForObject("SELECT BIN_TO_UUID(id) FROM village_memberships "
                + "WHERE village_id=UUID_TO_BIN(?) AND subject_type='USER' AND subject_id=? AND left_at IS NULL",
                (rs, row) -> UUID.fromString(rs.getString(1)), fixture.villageId().toString(), fixture.subjectId());
        memberships.leave(fixture.villageId(), membershipId, fixture.subjectId());
        assertThat(activeCount(fixture.subjectId())).isEqualTo(99);

        invoke(fixture);

        assertThat(activeCount(fixture.subjectId())).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM village_memberships WHERE village_id=UUID_TO_BIN(?) "
                + "AND subject_type='USER' AND subject_id=?", Integer.class,
                fixture.villageId().toString(), fixture.subjectId())).as("退村履歴を残して新行で再加入").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT user_slot FROM village_memberships WHERE village_id=UUID_TO_BIN(?) "
                + "AND subject_type='USER' AND subject_id=? AND left_at IS NULL",
                Integer.class, fixture.villageId().toString(), fixture.subjectId())).isEqualTo(100);
    }

    /** AC-1808-6: 他人の subjectId を指定した参加は REPRESENT_FORBIDDEN（HTTP 403）で、何も作らない。 */
    @Test
    void 入村_他人のsubjectIdでの参加はIDORとして拒否する() {
        Fixture fixture = prepare(Writer.FREE);
        long attacker = newUser();

        assertThatThrownBy(() -> memberships.join(fixture.villageId(), attacker,
                new MembershipJoinRequest(VillageSubjectType.USER, fixture.subjectId())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(VillageErrorCode.REPRESENT_FORBIDDEN));

        assertThat(activeCount(fixture.subjectId())).isZero();
        assertThat(activeCount(attacker)).isZero();
    }

    /** AC-1808-6: 現役BAN中の主体は、どの入口からも再参加できない。 */
    @ParameterizedTest(name = "{0}: BAN済み再参加")
    @MethodSource("bannedRejoinWriters")
    void 入村_BAN済み主体の再参加は拒否する(Writer writer) {
        Fixture fixture = prepare(writer);
        insertMembership(fixture.villageId(), fixture.subjectId(), 1, true, "VILLAGER");
        List<Map<String, Object>> effectsBefore = sideEffectSnapshot(fixture);

        assertThatThrownBy(() -> invoke(fixture))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(VillageErrorCode.MEMBER_BANNED));

        assertThat(sideEffectSnapshot(fixture)).isEqualTo(effectsBefore);
        assertThat(activeCount(fixture.subjectId())).isEqualTo(1);
    }

    static Stream<Arguments> bannedRejoinWriters() {
        return Stream.of(Writer.FREE, Writer.JOIN_APPROVAL, Writer.INVITATION).map(Arguments::of);
    }

    /**
     * AC-1808-7: Service を経由しない独立接続（JdbcTemplate 直）からの現役重複・101村目を DB 制約が拒否し、
     * 既存行を変えない。アプリのロックが漏れても DB が不変条件を守る証明。
     */
    @Test
    void DB制約_Service非経由の現役重複と101村目の直INSERTを拒否し既存行を保全する() {
        long subject = newUser();
        fillSlots(subject, 100, false);
        UUID existingVillage = jdbc.queryForObject("SELECT BIN_TO_UUID(village_id) FROM village_memberships "
                + "WHERE subject_type='USER' AND subject_id=? AND user_slot=1",
                (rs, row) -> UUID.fromString(rs.getString(1)), subject);
        List<Map<String, Object>> before = actorMemberships(subject);
        UUID overflowVillage = newVillage("direct-" + UUID.randomUUID().toString().replace("-", ""),
                VillageJoinPolicy.FREE, subject);

        assertThatThrownBy(() -> insertMembership(existingVillage, subject, 1, false, "VILLAGER"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("uk_vm_");
        assertThatThrownBy(() -> insertMembership(overflowVillage, subject, 101, false, "VILLAGER"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("ck_vm_active_user_slot");
        assertThatThrownBy(() -> insertMembership(overflowVillage, subject, 50, false, "VILLAGER"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("uk_vm_user_active_slot");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO village_memberships "
                        + "(id,village_id,subject_type,subject_id,role,joined_at,created_at,updated_at,version,profile_public) "
                        + "VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'USER',?,'VILLAGER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,"
                        + "CURRENT_TIMESTAMP,0,1)", UUID.randomUUID().toString(), overflowVillage.toString(), subject))
                .as("slot無しの現役USER行").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("ck_vm_active_user_slot");

        assertThat(actorMemberships(subject)).isEqualTo(before);
    }

    /** AC-1808-7: 独立接続の同一村への並行 INSERT は、片方だけ確定し既存行を保全する。 */
    @Test
    void DB制約_独立接続の同一村並行INSERTは片方だけ確定する() throws Exception {
        long subject = newUser();
        UUID village = newVillage("race-" + UUID.randomUUID().toString().replace("-", ""),
                VillageJoinPolicy.FREE, subject);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Throwable>> futures = new ArrayList<>();
        try {
            for (int slot = 1; slot <= 2; slot++) {
                int ownSlot = slot;
                futures.add(executor.submit(() -> {
                    try {
                        isolated(() -> {
                            ready.countDown();
                            try {
                                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            }
                            return insertMembership(village, subject, ownSlot, false, "VILLAGER");
                        });
                        return null;
                    } catch (RuntimeException failure) {
                        return failure;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> future : futures) {
                failures.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(failures.stream().filter(java.util.Objects::isNull).count()).isEqualTo(1);
            assertThat(failures.stream().filter(java.util.Objects::nonNull).findFirst().orElseThrow())
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                    .hasMessageContaining("uk_vm_active_subject");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(activeCount(subject)).isEqualTo(1);
    }

    private Fixture sameVillageFixture(Writer writer, long subject, long reviewer, UUID villageId,
                                       UUID headman, String slug) {
        UUID joinId = null;
        UUID invitationId = null;
        String token = null;
        if (writer == Writer.JOIN_APPROVAL) {
            joinId = joinRepository.saveAndFlush(VillageJoinRequestEntity.builder()
                    .villageId(villageId).subjectType(VillageSubjectType.USER).subjectId(subject)
                    .requesterUserId(subject).status(VillageRequestStatus.PENDING).build()).getId();
        } else if (writer == Writer.INVITATION) {
            token = "cmp1808-" + UUID.randomUUID();
            invitationId = invitationRepository.saveAndFlush(VillageInvitationEntity.builder()
                    .villageId(villageId).tokenHash(tokenVault.hash(token)).targetUserId(subject)
                    .expiresAt(Instant.now().plusSeconds(3600)).maxUses(2).usedCount(0)
                    .createdByMembershipId(headman).build()).getId();
        }
        return new Fixture(writer, subject, reviewer, villageId, joinId, invitationId, token, null, slug);
    }

    private Fixture prepare(Writer writer) {
        return prepare(writer, newUser());
    }

    private Fixture prepare(Writer writer, long subjectId) {
        long reviewerId = newUser();
        String slug = "slot-" + UUID.randomUUID().toString().replace("-", "");
        UUID villageId = null;
        UUID joinId = null;
        UUID invitationId = null;
        UUID creationId = null;
        String token = null;
        if (writer == Writer.FREE || writer == Writer.JOIN_APPROVAL || writer == Writer.INVITATION) {
            villageId = newVillage(slug, writer == Writer.JOIN_APPROVAL
                    ? VillageJoinPolicy.APPROVAL : VillageJoinPolicy.FREE, reviewerId);
            UUID headman = insertMembership(villageId, reviewerId, 1, false, "HEADMAN");
            if (writer == Writer.JOIN_APPROVAL) {
                joinId = joinRepository.saveAndFlush(VillageJoinRequestEntity.builder()
                        .villageId(villageId).subjectType(VillageSubjectType.USER).subjectId(subjectId)
                        .requesterUserId(subjectId).status(VillageRequestStatus.PENDING).build()).getId();
            } else if (writer == Writer.INVITATION) {
                token = "cmp1808-" + UUID.randomUUID();
                invitationId = invitationRepository.saveAndFlush(VillageInvitationEntity.builder()
                        .villageId(villageId).tokenHash(tokenVault.hash(token)).targetUserId(subjectId)
                        .expiresAt(Instant.now().plusSeconds(3600)).maxUses(2).usedCount(0)
                        .createdByMembershipId(headman).build()).getId();
            }
        } else if (writer == Writer.CREATION_APPROVAL) {
            creationId = creationRepository.saveAndFlush(VillageCreationRequestEntity.builder()
                    .requesterUserId(subjectId).proposedName("枠試練村" + slug).proposedSlug(slug)
                    .purpose("CMP1808専用試練").status(VillageRequestStatus.PENDING).build()).getId();
        }
        return new Fixture(writer, subjectId, reviewerId, villageId, joinId,
                invitationId, token, creationId, slug);
    }

    private UUID invoke(Fixture fixture) {
        return switch (fixture.writer()) {
            case FREE -> {
                memberships.join(fixture.villageId(), fixture.subjectId(),
                        new MembershipJoinRequest(VillageSubjectType.USER, fixture.subjectId()));
                yield fixture.villageId();
            }
            case JOIN_APPROVAL -> {
                joins.approve(fixture.villageId(), fixture.joinId(), fixture.reviewerId(), null);
                yield fixture.villageId();
            }
            case INVITATION -> invitations.accept(fixture.token(), fixture.subjectId()).villageId();
            case CREATION_AUTO -> creations.createRequest(fixture.subjectId(),
                    new VillageCreationRequestCreateRequest("枠試練村" + fixture.slug(), fixture.slug(), "試練", "CMP1808専用試練",
                            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), VillageType.COMMUNITY, null))
                    .createdVillageId();
            case CREATION_APPROVAL -> creations.approve(fixture.creationId(), fixture.reviewerId(), null)
                    .createdVillageId();
        };
    }

    private void assertCommittedSideEffects(Fixture fixture, UUID villageId) {
        if (fixture.joinId() != null) {
            assertThat(joinRepository.findById(fixture.joinId()).orElseThrow().getStatus())
                    .isEqualTo(VillageRequestStatus.APPROVED);
        }
        if (fixture.invitationId() != null) {
            assertThat(invitationRepository.findById(fixture.invitationId()).orElseThrow().getUsedCount())
                    .isEqualTo(1);
        }
        if (fixture.writer() == Writer.CREATION_AUTO || fixture.writer() == Writer.CREATION_APPROVAL) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM village_creation_requests "
                    + "WHERE proposed_slug=? AND status='APPROVED' AND created_village_id=UUID_TO_BIN(?) "
                    + "AND requester_user_id=?", Integer.class, fixture.slug(), villageId.toString(), fixture.subjectId()))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT role FROM village_memberships "
                    + "WHERE village_id=UUID_TO_BIN(?) AND subject_id=? AND left_at IS NULL",
                    String.class, villageId.toString(), fixture.subjectId())).isEqualTo("HEADMAN");
        }
    }

    private List<Map<String, Object>> sideEffectSnapshot(Fixture fixture) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(snapshot("SELECT 'village' AS snapshot_kind,v.* FROM villages v WHERE slug=? ORDER BY id", fixture.slug()));
        rows.addAll(snapshot("SELECT 'creation' AS snapshot_kind,c.* FROM village_creation_requests c "
                + "WHERE proposed_slug=? ORDER BY id", fixture.slug()));
        if (fixture.joinId() != null) {
            rows.addAll(snapshot("SELECT 'join' AS snapshot_kind,j.* FROM village_join_requests j "
                    + "WHERE id=UUID_TO_BIN(?)", fixture.joinId().toString()));
        }
        if (fixture.invitationId() != null) {
            rows.addAll(snapshot("SELECT 'invitation' AS snapshot_kind,i.* FROM village_invitations i "
                    + "WHERE id=UUID_TO_BIN(?)", fixture.invitationId().toString()));
        }
        return rows;
    }

    private long newUser() {
        return userRepository.saveAndFlush(UserEntity.builder()
                .email("slot-" + UUID.randomUUID() + "@example.invalid")
                .lastName("枠試練").firstName("本人").displayName("枠試練" + UUID.randomUUID().toString().substring(0, 8))
                .isSearchable(false).locale("ja").timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE).contactApprovalRequired(false).build()).getId();
    }

    private UUID newVillage(String slug, VillageJoinPolicy policy, long creator) {
        return villageRepository.saveAndFlush(VillageEntity.builder().slug(slug).name("枠試練村" + slug)
                .type(VillageType.COMMUNITY).joinPolicy(policy).visibility(VillageVisibility.PUBLIC)
                .createdByUserId(creator).build()).getId();
    }

    private void fillSlots(long subject, int count, boolean includeBanned) {
        for (int slot = 1; slot <= count; slot++) {
            UUID village = newVillage("occupied-" + UUID.randomUUID().toString().replace("-", ""),
                    VillageJoinPolicy.FREE, subject);
            insertMembership(village, subject, slot, includeBanned && slot == count, "VILLAGER");
        }
    }

    private UUID insertMembership(UUID village, long subject, int slot, boolean banned, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO village_memberships "
                + "(id,village_id,subject_type,subject_id,role,joined_at,created_at,updated_at,version,profile_public,user_slot,banned_at) "
                + "VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'USER',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,1,?,?)",
                id.toString(), village.toString(), subject, role, slot, banned ? java.sql.Timestamp.from(Instant.now()) : null);
        return id;
    }

    private int activeCount(long subject) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM village_memberships "
                + "WHERE subject_type='USER' AND subject_id=? AND left_at IS NULL", Integer.class, subject);
    }

    private List<Map<String, Object>> actorMemberships(long subject) {
        return snapshot("SELECT * FROM village_memberships WHERE subject_type='USER' AND subject_id=? ORDER BY id", subject);
    }

    /** BINARYの参照同一性ではなく、全列の値を比較する。 */
    private List<Map<String, Object>> snapshot(String sql, Object... args) {
        return jdbc.queryForList(sql, args).stream().map(row -> {
            Map<String, Object> values = new LinkedHashMap<>();
            row.forEach((key, value) -> values.put(key,
                    value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value));
            return values;
        }).toList();
    }
}
