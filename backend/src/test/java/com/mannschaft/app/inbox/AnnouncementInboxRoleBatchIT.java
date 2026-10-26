package com.mannschaft.app.inbox;

import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.inbox.service.adapter.AnnouncementInboxAdapter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ???Inbox adapter????????????role???????????
 * feed??????????budget????role SQL??????
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AnnouncementInboxRoleBatchIT extends AbstractMySqlIntegrationTest {
    @Autowired private AnnouncementInboxAdapter adapter;
    @PersistenceContext private EntityManager em;

    @Test
    @DisplayName("??1???21??????role??????feed??scope??????????")
    void roleQueriesStayConstantAcrossScopes() {
        long oneUser = insertUser();
        long manyUser = insertUser();
        addTeams(oneUser, 1);
        addTeams(manyUser, 21);
        List<String> one = fetchSql(oneUser);
        List<String> many = fetchSql(manyUser);

        assertThat(feedQueries(one)).isEqualTo(1);
        assertThat(feedQueries(many)).isEqualTo(21);
        assertThat(roleQueries(one)).isPositive();
        assertThat(roleQueries(many))
                .as("role SQL budget: 1scope=%s / 21scope=%s (feed???)",
                        roleQueries(one), roleQueries(many))
                .isEqualTo(roleQueries(one));
    }

    private List<String> fetchSql(long userId) {
        em.flush();
        em.clear();
        try (var capture = SqlIntentCounter.captureCurrentThread()) {
            assertThat(adapter.fetch(userId, 500)).isEmpty();
            return capture.capturedSqls();
        }
    }

    private long feedQueries(List<String> statements) {
        return statements.stream().map(sql -> sql.toLowerCase(Locale.ROOT))
                .filter(sql -> sql.contains("from announcement_feeds ")).count();
    }

    private long roleQueries(List<String> statements) {
        return statements.stream().map(sql -> sql.toLowerCase(Locale.ROOT))
                .filter(sql -> sql.matches("(?s).*\\b(from|join) (users|user_roles|memberships|roles)\\b.*"))
                .count();
    }

    private long insertUser() {
        String email = "inbox-batch-" + UUID.randomUUID() + "@example.com";
        em.createNativeQuery("""
                INSERT INTO users (email, last_name, first_name, display_name, status,
                    is_searchable, handle_searchable, contact_approval_required,
                    online_visibility, dm_receive_from, encryption_key_version,
                    locale, timezone, reporting_restricted, follow_list_visibility,
                    care_notification_enabled, offline_only, created_at, updated_at)
                VALUES (:email, 'BATCH', '??', 'BATCH??', 'ACTIVE', 1, 1, 1,
                    'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())
                """).setParameter("email", email).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email=:email")
                .setParameter("email", email).getSingleResult()).longValue();
    }

    private void addTeams(long userId, int count) {
        for (int i = 0; i < count; i++) {
            String slug = "ib-" + UUID.randomUUID().toString().substring(0, 16);
            em.createNativeQuery("""
                    INSERT INTO teams (name, visibility, supporter_enabled, version, member_count,
                        slug, created_at, updated_at)
                    VALUES ('BATCH??', 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())
                    """).setParameter("slug", slug).executeUpdate();
            long teamId = ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug=:slug")
                    .setParameter("slug", slug).getSingleResult()).longValue();
            em.createNativeQuery("""
                    INSERT INTO memberships (user_id, scope_type, scope_id, role_kind,
                        joined_at, created_at, updated_at)
                    VALUES (:user, 'TEAM', :scope, 'MEMBER', NOW(), NOW(), NOW())
                    """).setParameter("user", userId).setParameter("scope", teamId).executeUpdate();
        }
    }
}
