package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.MembershipJoinRequest;
import com.mannschaft.app.village.dto.VillageCreationRequestCreateRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP1808: 五つの入村writerが実Flyway制約と全業務TXを通る最初の試練。
 *
 * <p>共有ddl-auto=createスキーマではCHECK/生成列一意を証明できないため、
 * BACKEND_CODING_CONVENTIONの専用実スキーマ金型を使う。実DB・認可・サービスは差し替えない。
 * 当該一クラスの20ケースが一つの専用コンテナ/コンテキストを共有し、テスト全体TXは置かない。
 * 並行参加・retry境界・HTTP認可は後続契約であり、この20件をその合格に代用しない。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.docker.compose.enabled=false"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.village.service.VillageMembershipAdmissionTransactionIT#isDockerAvailable")
@DisplayName("CMP1808 五経路のUSER枠と業務原子性（実Flyway/MySQL）")
@Timeout(120)
class VillageMembershipAdmissionTransactionIT {

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
        String ddl = jdbc.queryForObject("SHOW CREATE TABLE village_memberships",
                (rs, row) -> rs.getString(2));
        assertThat(ddl).contains("ck_vm_active_user_slot", "uk_vm_user_active_slot", "uk_vm_active_subject");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE success=1 AND script='V236.20261003224214__enforce_village_membership_slots.sql'",
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

    private Fixture prepare(Writer writer) {
        long subjectId = newUser();
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
