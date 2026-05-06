package com.mannschaft.app.circulation.visibility;

import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.circulation.RecipientStatus;
import com.mannschaft.app.circulation.entity.CirculationCommentEntity;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.repository.CirculationCommentRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F00 Phase C — {@link CirculationCommentVisibilityResolver} 結合テスト。
 *
 * <p>Spring Boot 全体を立ち上げて以下を検証する:</p>
 * <ol>
 *   <li>{@link CirculationCommentVisibilityResolver} Bean が {@link ContentVisibilityChecker} に
 *       {@link ReferenceType#COMMENT} として登録されている</li>
 *   <li>JPA で実際に circulation_comments / circulation_documents / circulation_recipients 行を保存し、
 *       {@link CirculationCommentRepository#findVisibilityProjectionsByIdIn} が想定どおりの
 *       Projection を返す</li>
 *   <li>canView(COMMENT, commentId, userId) 判定が親文書委譲を通じて正しく動作する</li>
 * </ol>
 *
 * <p>seed は最小限に留め、エンティティは JPA 経由（Builder + Repository#save）で保存する。
 * users / roles / teams / user_roles は native query で投入する
 * （{@code CirculationDocumentVisibilityResolverIntegrationTest} と同パターン）。</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
@DisplayName("CirculationCommentVisibilityResolver 結合テスト")
class CirculationCommentVisibilityResolverIntegrationTest {

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
    private CirculationCommentVisibilityResolver commentResolver;

    @Autowired
    private CirculationDocumentRepository documentRepository;

    @Autowired
    private CirculationRecipientRepository recipientRepository;

    @Autowired
    private CirculationCommentRepository commentRepository;

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

        authorUserId       = insertUser("comment.author@example.com", "山田", "起票");
        recipientUserId    = insertUser("comment.recipient@example.com", "鈴木", "受信");
        nonRecipientUserId = insertUser("comment.nonrecipient@example.com", "佐藤", "未登録");
        sysAdminUserId     = insertUser("comment.sysadmin@example.com", "管理", "者");

        teamId = insertTeam("コメント結合テストチーム");

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

    private Long saveDocument(String title, Long createdBy, CirculationStatus status) {
        CirculationDocumentEntity d = CirculationDocumentEntity.builder()
                .scopeType("TEAM")
                .scopeId(teamId)
                .createdBy(createdBy)
                .title(title)
                .body("コメント結合テスト本文")
                .status(status)
                .build();
        CirculationDocumentEntity saved = documentRepository.saveAndFlush(d);
        return saved.getId();
    }

    private void saveRecipient(Long documentId, Long userId) {
        CirculationRecipientEntity r = CirculationRecipientEntity.builder()
                .documentId(documentId)
                .userId(userId)
                .sortOrder(0)
                .status(RecipientStatus.PENDING)
                .build();
        recipientRepository.saveAndFlush(r);
    }

    private Long saveComment(Long documentId, Long userId) {
        CirculationCommentEntity c = CirculationCommentEntity.builder()
                .documentId(documentId)
                .userId(userId)
                .body("テストコメント本文")
                .build();
        CirculationCommentEntity saved = commentRepository.saveAndFlush(c);
        return saved.getId();
    }

    // =========================================================================
    // シナリオ
    // =========================================================================

    @Test
    @DisplayName("Bean 配線 — Resolver が ContentVisibilityChecker に COMMENT として登録されている")
    void resolver_registered_for_COMMENT() {
        assertThat(checker).isNotNull();
        assertThat(commentResolver).isNotNull();
        assertThat(commentResolver.referenceType()).isEqualTo(ReferenceType.COMMENT);

        long count = allResolvers.stream()
                .filter(r -> r.referenceType() == ReferenceType.COMMENT)
                .count();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("不存在 ID は誰に対しても false（fail-closed）")
    void unknown_id_false() {
        assertThat(checker.canView(ReferenceType.COMMENT, 999_999L, recipientUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.COMMENT, 999_999L, sysAdminUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.COMMENT, 999_999L, null)).isFalse();
    }

    @Test
    @DisplayName("recipients 登録済みユーザーはコメントを閲覧可（親文書 ACTIVE）")
    void recipient_can_view_comment() {
        Long docId = saveDocument("回覧コメントテスト", authorUserId, CirculationStatus.ACTIVE);
        saveRecipient(docId, recipientUserId);
        Long commentId = saveComment(docId, recipientUserId);
        em.clear();

        assertThat(checker.canView(ReferenceType.COMMENT, commentId, recipientUserId)).isTrue();
    }

    @Test
    @DisplayName("recipients 未登録ユーザーはコメントを閲覧不可")
    void non_recipient_cannot_view_comment() {
        Long docId = saveDocument("回覧コメントテスト2", authorUserId, CirculationStatus.ACTIVE);
        saveRecipient(docId, recipientUserId);
        Long commentId = saveComment(docId, recipientUserId);
        em.clear();

        assertThat(checker.canView(ReferenceType.COMMENT, commentId, nonRecipientUserId)).isFalse();
    }

    @Test
    @DisplayName("文書作成者本人はコメントを閲覧可（親文書の authorUserId 判定）")
    void document_author_can_view_comment() {
        Long docId = saveDocument("回覧コメントテスト3", authorUserId, CirculationStatus.ACTIVE);
        Long commentId = saveComment(docId, recipientUserId);
        em.clear();

        // 文書作成者（authorUserId）は recipients 未登録でも親文書を閲覧できるため、コメントも閲覧可
        assertThat(checker.canView(ReferenceType.COMMENT, commentId, authorUserId)).isTrue();
    }

    @Test
    @DisplayName("SystemAdmin はコメントを閲覧可")
    void system_admin_can_view_comment() {
        Long docId = saveDocument("回覧コメントテスト4", authorUserId, CirculationStatus.ACTIVE);
        Long commentId = saveComment(docId, recipientUserId);
        em.clear();

        assertThat(checker.canView(ReferenceType.COMMENT, commentId, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("null userId は false（fail-closed）")
    void null_user_id_false() {
        Long docId = saveDocument("回覧コメントテスト5", authorUserId, CirculationStatus.ACTIVE);
        saveRecipient(docId, recipientUserId);
        Long commentId = saveComment(docId, recipientUserId);
        em.clear();

        assertThat(checker.canView(ReferenceType.COMMENT, commentId, null)).isFalse();
    }

    @Test
    @DisplayName("findVisibilityProjectionsByIdIn — JOIN で親文書の scopeType/scopeId を正しく取得する")
    void findVisibilityProjectionsByIdIn_returns_correct_projection() {
        Long docId = saveDocument("プロジェクション確認", authorUserId, CirculationStatus.ACTIVE);
        Long commentId = saveComment(docId, recipientUserId);
        em.clear();

        List<CirculationCommentVisibilityProjection> projections =
                commentRepository.findVisibilityProjectionsByIdIn(List.of(commentId));

        assertThat(projections).hasSize(1);
        CirculationCommentVisibilityProjection p = projections.get(0);
        assertThat(p.id()).isEqualTo(commentId);
        assertThat(p.scopeType()).isEqualTo("TEAM");
        assertThat(p.scopeId()).isEqualTo(teamId);
        assertThat(p.authorUserId()).isEqualTo(recipientUserId);
        assertThat(p.documentId()).isEqualTo(docId);
    }
}
