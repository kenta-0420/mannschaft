package com.mannschaft.app.circulation.visibility;

import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.circulation.RecipientStatus;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ReferenceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F00 Phase C — {@link CirculationDocumentVisibilityResolver} 結合テスト。
 *
 * <p>Spring Boot 全体を立ち上げて以下を検証する:</p>
 * <ol>
 *   <li>{@link CirculationDocumentVisibilityResolver} Bean が {@link ContentVisibilityChecker} に
 *       {@link ReferenceType#CIRCULATION_DOCUMENT} として登録されている</li>
 *   <li>JPA で実際に circulation_documents / circulation_recipients 行を保存し、
 *       {@link CirculationDocumentRepository#findVisibilityProjectionsByIdIn} が想定どおりの
 *       Projection を返す</li>
 *   <li>SystemAdmin / 匿名 / 一般ユーザー / recipients 登録者 のいずれからも実 DB 経由で
 *       canView 判定が成立する（案 A の ACL 判定）</li>
 * </ol>
 *
 * <p>seed は最小限に留め、エンティティは JPA 経由（Builder + Repository#save）で保存する。
 * users / roles / teams / user_roles は native query で投入する
 * （{@code MembershipBatchQueryServiceIntegrationTest} と同パターン）。</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
@DisplayName("CirculationDocumentVisibilityResolver 結合テスト")
class CirculationDocumentVisibilityResolverIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    // OOM 対策（既存 Repository テストパターン踏襲）
    @MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Autowired
    private ContentVisibilityChecker checker;

    @Autowired
    private CirculationDocumentVisibilityResolver circulationResolver;

    @Autowired
    private CirculationDocumentRepository documentRepository;

    @Autowired
    private CirculationRecipientRepository recipientRepository;

    @Autowired
    private List<ContentVisibilityResolver<?>> allResolvers;

    @PersistenceContext
    private EntityManager em;

    private Long memberRoleId;
    private Long systemAdminRoleId;
    private Long authorUserId;
    private Long recipientUserId;
    private Long nonRecipientUserId;
    private Long sysAdminUserId;
    private Long teamId;

    @BeforeEach
    void setUp() {
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('SYSTEM_ADMIN', 'システム管理者', 1, 1, NOW(), NOW())")
                .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('MEMBER', 'メンバー', 4, 0, NOW(), NOW())")
                .executeUpdate();
        em.flush();

        memberRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'MEMBER'").getSingleResult()).longValue();
        systemAdminRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'SYSTEM_ADMIN'").getSingleResult()).longValue();

        authorUserId       = insertUser("circ.author@example.com", "山田", "起票");
        recipientUserId    = insertUser("circ.recipient@example.com", "鈴木", "受信");
        nonRecipientUserId = insertUser("circ.nonrecipient@example.com", "佐藤", "未登録");
        sysAdminUserId     = insertUser("circ.sysadmin@example.com", "管理", "者");

        teamId = insertTeam("回覧 結合 チーム");

        insertUserRole(authorUserId, memberRoleId, teamId, null);
        insertUserRole(recipientUserId, memberRoleId, teamId, null);
        insertUserRole(sysAdminUserId, systemAdminRoleId, null, null);

        em.flush();
        em.clear();
    }

    private Long insertUser(String email, String lastName, String firstName) {
        em.createNativeQuery(
                "INSERT INTO users ("
                        + "email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, "
                        + "created_at, updated_at) "
                        + "VALUES (:email, :ln, :fn, :dn, 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", lastName)
                .setParameter("fn", firstName)
                .setParameter("dn", lastName + " " + firstName)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, created_at, updated_at) "
                        + "VALUES (:name, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertUserRole(Long uid, Long roleId, Long teamIdParam, Long orgIdParam) {
        em.createNativeQuery(
                "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                        + "VALUES (:uid, :rid, :tid, :oid, NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("rid", roleId)
                .setParameter("tid", teamIdParam)
                .setParameter("oid", orgIdParam)
                .executeUpdate();
    }

    /**
     * CirculationDocumentEntity を JPA 経由で保存する。Builder.Default で NOT NULL 列にデフォルトが入るため、
     * native INSERT より列定義の差異に強い。
     */
    private Long saveDocument(String title, Long createdBy, CirculationStatus status) {
        CirculationDocumentEntity d = CirculationDocumentEntity.builder()
                .scopeType("TEAM")
                .scopeId(teamId)
                .createdBy(createdBy)
                .title(title)
                .body("結合テスト本文")
                .status(status)
                .build();
        CirculationDocumentEntity saved = documentRepository.saveAndFlush(d);
        return saved.getId();
    }

    private void saveRecipient(Long documentId, Long userId, int sortOrder) {
        CirculationRecipientEntity r = CirculationRecipientEntity.builder()
                .documentId(documentId)
                .userId(userId)
                .sortOrder(sortOrder)
                .status(RecipientStatus.PENDING)
                .build();
        recipientRepository.saveAndFlush(r);
    }

    // =========================================================================
    // シナリオ
    // =========================================================================

    @Test
    @DisplayName("Bean 配線 — Resolver が ContentVisibilityChecker に CIRCULATION_DOCUMENT として登録されている")
    void resolver_registered_for_CIRCULATION_DOCUMENT() {
        assertThat(checker).isNotNull();
        assertThat(circulationResolver).isNotNull();
        assertThat(circulationResolver.referenceType()).isEqualTo(ReferenceType.CIRCULATION_DOCUMENT);

        long count = allResolvers.stream()
                .filter(r -> r.referenceType() == ReferenceType.CIRCULATION_DOCUMENT)
                .count();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("不存在 ID は誰に対しても false（fail-closed）")
    void unknown_id_false() {
        assertThat(checker.canView(ReferenceType.CIRCULATION_DOCUMENT, 999_999L, recipientUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.CIRCULATION_DOCUMENT, 999_999L, sysAdminUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.CIRCULATION_DOCUMENT, 999_999L, null)).isFalse();
    }

    @Test
    @DisplayName("ACTIVE 文書: recipients 登録者は閲覧可、未登録者は不可、匿名は不可")
    void active_visible_only_to_recipients() {
        Long docId = saveDocument("回覧A", authorUserId, CirculationStatus.ACTIVE);
        saveRecipient(docId, recipientUserId, 0);
        em.clear();

        assertThat(checker.canView(ReferenceType.CIRCULATION_DOCUMENT, docId, recipientUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.CIRCULATION_DOCUMENT, docId, nonRecipientUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.CIRCULATION_DOCUMENT, docId, null)).isFalse();
    }

    @Test
    @DisplayName("ACTIVE 文書: 作成者本人は recipients 未登録でも閲覧可")
    void active_visible_to_author_without_recipient_record() {
        Long docId = saveDocument("回覧B", authorUserId, CirculationStatus.ACTIVE);
        em.clear();

        assertThat(checker.canView(ReferenceType.CIRCULATION_DOCUMENT, docId, authorUserId)).isTrue();
    }

    @Test
    @DisplayName("ACTIVE 文書: SystemAdmin は recipients 未登録でも閲覧可")
    void active_visible_to_system_admin() {
        Long docId = saveDocument("回覧C", authorUserId, CirculationStatus.ACTIVE);
        em.clear();

        assertThat(checker.canView(ReferenceType.CIRCULATION_DOCUMENT, docId, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("DRAFT 文書: 作成者と SystemAdmin のみ閲覧可（recipients 登録があっても弾く）")
    void draft_visible_only_to_author_or_sysadmin() {
        Long docId = saveDocument("下書き", authorUserId, CirculationStatus.DRAFT);
        saveRecipient(docId, recipientUserId, 0);
        em.clear();

        assertThat(checker.canView(ReferenceType.CIRCULATION_DOCUMENT, docId, authorUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.CIRCULATION_DOCUMENT, docId, sysAdminUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.CIRCULATION_DOCUMENT, docId, recipientUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.CIRCULATION_DOCUMENT, docId, nonRecipientUserId)).isFalse();
    }

    @Test
    @DisplayName("CANCELLED 文書: SystemAdmin のみ閲覧可（ARCHIVED 扱い、作成者ですら不可）")
    void cancelled_visible_only_to_system_admin() {
        Long docId = saveDocument("中止", authorUserId, CirculationStatus.CANCELLED);
        saveRecipient(docId, recipientUserId, 0);
        em.clear();

        assertThat(checker.canView(ReferenceType.CIRCULATION_DOCUMENT, docId, authorUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.CIRCULATION_DOCUMENT, docId, recipientUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.CIRCULATION_DOCUMENT, docId, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("COMPLETED 文書: recipients 登録者は閲覧可")
    void completed_visible_to_recipient() {
        Long docId = saveDocument("完了", authorUserId, CirculationStatus.COMPLETED);
        saveRecipient(docId, recipientUserId, 0);
        em.clear();

        assertThat(checker.canView(ReferenceType.CIRCULATION_DOCUMENT, docId, recipientUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.CIRCULATION_DOCUMENT, docId, nonRecipientUserId)).isFalse();
    }

    @Test
    @DisplayName("filterAccessible は recipients 登録状況に応じて正しくフィルタする")
    void filterAccessible_mixed_for_recipient() {
        Long d1 = saveDocument("回覧1", authorUserId, CirculationStatus.ACTIVE);
        Long d2 = saveDocument("回覧2", authorUserId, CirculationStatus.ACTIVE);
        Long d3 = saveDocument("回覧3", authorUserId, CirculationStatus.DRAFT);
        saveRecipient(d1, recipientUserId, 0);
        saveRecipient(d2, recipientUserId, 0);
        saveRecipient(d3, recipientUserId, 0);
        em.clear();

        Set<Long> recipientView = checker.filterAccessible(
                ReferenceType.CIRCULATION_DOCUMENT, List.of(d1, d2, d3), recipientUserId);
        // d3 は DRAFT で recipient ですら不可
        assertThat(recipientView).containsExactlyInAnyOrder(d1, d2);

        Set<Long> nonRecipientView = checker.filterAccessible(
                ReferenceType.CIRCULATION_DOCUMENT, List.of(d1, d2, d3), nonRecipientUserId);
        assertThat(nonRecipientView).isEmpty();

        Set<Long> sysAdminView = checker.filterAccessible(
                ReferenceType.CIRCULATION_DOCUMENT, List.of(d1, d2, d3), sysAdminUserId);
        assertThat(sysAdminView).containsExactlyInAnyOrder(d1, d2, d3);
    }

    @Test
    @DisplayName("filterAccessibleByType — 複数 type ミックス API も CIRCULATION_DOCUMENT を解決できる")
    void filterAccessibleByType_includes_CIRCULATION_DOCUMENT() {
        Long docId = saveDocument("ミックス", authorUserId, CirculationStatus.ACTIVE);
        saveRecipient(docId, recipientUserId, 0);
        em.clear();

        Map<ReferenceType, Set<Long>> result = checker.filterAccessibleByType(
                Map.of(ReferenceType.CIRCULATION_DOCUMENT, List.of(docId)),
                recipientUserId);
        assertThat(result).containsKey(ReferenceType.CIRCULATION_DOCUMENT);
        assertThat(result.get(ReferenceType.CIRCULATION_DOCUMENT)).containsExactly(docId);
    }
}
