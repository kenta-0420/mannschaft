package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.auth.dto.RequestWithdrawalRequest;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.gdpr.dto.RetryResultResponse;
import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * CMP-260822-1243（残り）: 退会者のカスタム公開範囲テンプレート（F01.7）の強消去を実DBで確認する。
 *
 * <p>マスター裁可（2026-10-11）: 退会者のテンプレートは30日後の強匿名化で消し、参照していた本人の投稿は
 * 非公開（PRIVATE 相当）へ落とす。退会取消可能期間（弱匿名化の時点）ではテンプレートを残す。
 * ユーザー行は物理削除されないので FK の ON DELETE CASCADE / SET NULL は発火せず、また
 * V110.001 で visibility_template_id への SET NULL FK は撤廃済みのため、purge で明示的に消す必要がある。
 * 参照先の消えたテンプレートは {@link VisibilityTemplateEvaluator#canView} が fail-closed（false）で扱う。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VisibilityTemplateAccountPurgeIT extends AbstractMySqlIntegrationTest {

    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private UserService userService;
    @Autowired private AccountPurgeService accountPurgeService;
    @Autowired private GdprPurgeRetryFacade retryService;
    @Autowired private VisibilityTemplateEvaluator evaluator;
    @Autowired @Qualifier("purge-pool") private Executor purgeExecutor;
    @PersistenceContext private EntityManager entityManager;

    @Test
    @DisplayName("(a)(b) 30日後の強匿名化で本人のテンプレートとルールだけが消え、本人のテンプレートは閲覧者に使えなくなる")
    void strongPurgeDeletesOnlyOwnTemplatesAndMakesThemUnviewable() {
        stubRedis();
        Long target = createUser("退会者");
        Long other = createUser("別所有者");
        Long viewer = createUser("閲覧者");
        Long presetId = null;
        try {
            Long targetT1 = seedTemplate(target, "本人T1", viewer);
            Long targetT2 = seedTemplate(target, "本人T2", viewer);
            Long otherT = seedTemplate(other, "他人T", viewer);
            presetId = seedPreset(viewer);
            // 現行の失敗理由: purge 経路にテンプレート削除の担当が無く、強匿名化後も本人のテンプレートが残る。
            assertThat(evaluator.canView(viewer, targetT1, target)).isTrue();

            userService.requestWithdrawal(target, new RequestWithdrawalRequest(null));
            expireWithdrawal(target);
            accountPurgeService.purgeExpiredAccounts();

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(count("SELECT COUNT(*) FROM visibility_templates WHERE owner_user_id = " + target))
                        .as("(a) 本人のテンプレート").isZero();
                assertThat(count("SELECT COUNT(*) FROM visibility_template_rules WHERE template_id IN ("
                        + targetT1 + "," + targetT2 + ")")).as("(a) 本人テンプレートの子ルール").isZero();
                assertThat(count("SELECT COUNT(*) FROM account_purge_completion_status WHERE user_id = " + target
                        + " AND domain_name = 'visibility' AND status = 'SUCCESS'"))
                        .as("visibility domain の完了記録").isEqualTo(1);
            });
            // 他人のテンプレートとシステムプリセット（owner NULL）は残る。
            assertThat(count("SELECT COUNT(*) FROM visibility_templates WHERE id = " + otherT)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM visibility_template_rules WHERE template_id = " + otherT))
                    .isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM visibility_templates WHERE id = " + presetId)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM visibility_template_rules WHERE template_id = " + presetId))
                    .isEqualTo(1);

            // (b) 本人の投稿が参照するテンプレートは閲覧者に使えない（PRIVATE 相当）。他人・プリセットは影響を受けない。
            assertThat(evaluator.canView(viewer, targetT1, target)).as("(b) 本人T1").isFalse();
            assertThat(evaluator.canView(viewer, targetT2, target)).as("(b) 本人T2").isFalse();
            assertThat(evaluator.canView(viewer, otherT, other)).as("(b) 他人のテンプレート").isTrue();
            assertThat(evaluator.canView(viewer, presetId, other)).as("(b) システムプリセット").isTrue();
        } finally {
            cleanup(target, other, viewer, presetId);
        }
    }

    @Test
    @DisplayName("(c) 退会受付直後（弱匿名化の時点）ではテンプレートは残り、取り消せばそのまま使える")
    void withdrawalRequestKeepsTemplatesAndCancellationRestoresUse() {
        stubRedis();
        Long target = createUser("退会予定者");
        Long viewer = createUser("閲覧者");
        try {
            Long template = seedTemplate(target, "本人T", viewer);
            userService.requestWithdrawal(target, new RequestWithdrawalRequest(null));
            assertThat(count("SELECT COUNT(*) FROM visibility_templates WHERE owner_user_id = " + target))
                    .as("猶予中のテンプレート").isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM visibility_template_rules WHERE template_id = " + template))
                    .as("猶予中のルール").isEqualTo(1);
            assertThat(evaluator.canView(viewer, template, target)).isTrue();

            userService.cancelWithdrawal(target);
            assertThat(count("SELECT COUNT(*) FROM visibility_templates WHERE owner_user_id = " + target))
                    .as("取消後のテンプレート").isEqualTo(1);
            assertThat(evaluator.canView(viewer, template, target)).as("取消後も使える").isTrue();
        } finally {
            cleanup(target, null, viewer, null);
        }
    }

    /**
     * 再試行経路（管理画面の手動 retry）の実削除を確認する。
     * retryPurge を {@code return true} だけの no-op にすると、本人分の templates/rules が残るためこのテストは落ちる
     * （PENDING を SUCCESS にするだけで消去しない実装を検出する）。
     */
    @Test
    @DisplayName("再試行経路: 本人のテンプレートとルールだけが消え、他人とプリセットは残る（retryPurge が no-op なら落ちる）")
    void retryDeletesOnlyOwnTemplatesAndRules() {
        stubRedis();
        Long target = createUser("再試行の本人");
        Long other = createUser("別所有者");
        Long viewer = createUser("閲覧者");
        Long presetId = null;
        try {
            Long targetT1 = seedTemplate(target, "本人T1", viewer);
            Long targetT2 = seedTemplate(target, "本人T2", viewer);
            Long otherT = seedTemplate(other, "他人T", viewer);
            presetId = seedPreset(viewer);
            seedPending(target);

            RetryResultResponse result = retryService.retryDomainPurge(target, "visibility");

            assertThat(result.succeeded()).isTrue();
            assertThat(count("SELECT COUNT(*) FROM visibility_templates WHERE owner_user_id = " + target))
                    .as("本人のテンプレート").isZero();
            assertThat(count("SELECT COUNT(*) FROM visibility_template_rules WHERE template_id IN ("
                    + targetT1 + "," + targetT2 + ")")).as("本人の子ルール").isZero();
            assertThat(count("SELECT COUNT(*) FROM visibility_templates WHERE id IN (" + otherT + "," + presetId + ")"))
                    .as("他人とプリセットのテンプレート").isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM visibility_template_rules WHERE template_id IN ("
                    + otherT + "," + presetId + ")")).as("他人とプリセットのルール").isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM account_purge_completion_status WHERE user_id = " + target
                    + " AND domain_name = 'visibility' AND status = 'SUCCESS'")).isEqualTo(1);
        } finally {
            cleanup(target, other, viewer, presetId);
        }
    }

    /**
     * 親(templates)の DELETE が失敗したら、先に消した子(rules)も同じ TX でロールバックされ PENDING が残る。
     * 子を別 TX で先にコミットする実装だと rules だけ0件になりこのテストは落ちる。
     */
    @Test
    @DisplayName("再試行経路: 親の削除失敗で子ルールもロールバックされPENDINGが残り、障害解消後の再試行で0件になる")
    void retryFailureRollsBackChildrenAndKeepsPending() {
        stubRedis();
        Long target = createUser("親削除失敗の本人");
        Long viewer = createUser("閲覧者");
        String trigger = "cmp1243_vt_" + target;
        try {
            Long t1 = seedTemplate(target, "本人T1", viewer);
            Long t2 = seedTemplate(target, "本人T2", viewer);
            seedPending(target);
            executeTriggerDdl("CREATE TRIGGER " + trigger + " BEFORE DELETE ON visibility_templates "
                    + "FOR EACH ROW BEGIN IF OLD.owner_user_id = " + target + " THEN SIGNAL SQLSTATE '45000' "
                    + "SET MESSAGE_TEXT = 'cmp1243 template delete failure'; END IF; END");

            RetryResultResponse failed = retryService.retryDomainPurge(target, "visibility");

            assertThat(failed.succeeded()).isFalse();
            assertThat(failed.newStatus()).isEqualTo("PENDING");
            assertThat(count("SELECT COUNT(*) FROM visibility_template_rules WHERE template_id IN ("
                    + t1 + "," + t2 + ")")).as("子ルールもロールバック").isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM visibility_templates WHERE owner_user_id = " + target))
                    .isEqualTo(2);

            executeTriggerDdl("DROP TRIGGER IF EXISTS " + trigger);
            RetryResultResponse recovered = retryService.retryDomainPurge(target, "visibility");
            assertThat(recovered.succeeded()).isTrue();
            assertThat(recovered.newStatus()).isEqualTo("SUCCESS");
            assertThat(count("SELECT COUNT(*) FROM visibility_templates WHERE owner_user_id = " + target)).isZero();
            assertThat(count("SELECT COUNT(*) FROM visibility_template_rules WHERE template_id IN ("
                    + t1 + "," + t2 + ")")).isZero();
        } finally {
            executeTriggerDdl("DROP TRIGGER IF EXISTS " + trigger);
            cleanup(target, null, viewer, null);
        }
    }

    private void seedPending(Long userId) {
        // id は UUIDv7 のアプリ採番のため、native INSERT ではなく Entity 経由で投入する。
        transactionTemplate.executeWithoutResult(tx -> {
            AccountPurgeCompletionStatusEntity pending = new AccountPurgeCompletionStatusEntity();
            pending.setUserId(userId);
            pending.setEmailHash("a".repeat(64));
            pending.setDomainName("visibility");
            pending.setStatus("PENDING");
            pending.setAttemptedAt(LocalDateTime.now());
            entityManager.persist(pending);
        });
    }

    private void executeTriggerDdl(String sql) {
        // trigger 権限は所有 container の root で実行する（PersonalSettingsAccountPurgeIT と同じ作法）。
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException("試練trigger DDL失敗", failure);
        }
    }

    private Long seedTemplate(Long owner, String name, Long viewer) {
        return transactionTemplate.execute(tx -> {
            entityManager.createNativeQuery("INSERT INTO visibility_templates "
                    + "(owner_user_id,name,is_system_preset,created_at,updated_at) VALUES ("
                    + owner + ",'" + name + "',false,NOW(),NOW())").executeUpdate();
            Long id = lastTemplateId();
            insertRule(id, viewer);
            return id;
        });
    }

    private Long seedPreset(Long viewer) {
        return transactionTemplate.execute(tx -> {
            entityManager.createNativeQuery("INSERT INTO visibility_templates "
                    + "(owner_user_id,name,is_system_preset,preset_key,created_at,updated_at) VALUES ("
                    + "NULL,'試練プリセット',true,'CMP1243_TEST_PRESET',NOW(),NOW())").executeUpdate();
            Long id = lastTemplateId();
            insertRule(id, viewer);
            return id;
        });
    }

    private void insertRule(Long templateId, Long viewer) {
        entityManager.createNativeQuery("INSERT INTO visibility_template_rules "
                + "(template_id,rule_type,rule_target_id,sort_order,created_at) VALUES ("
                + templateId + ",'EXPLICIT_USER'," + viewer + ",0,NOW())").executeUpdate();
    }

    private Long lastTemplateId() {
        return ((Number) entityManager.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    private void expireWithdrawal(Long target) {
        transactionTemplate.executeWithoutResult(tx -> entityManager.createNativeQuery(
                "UPDATE users SET deleted_at = DATE_SUB(NOW(), INTERVAL 31 DAY) WHERE id = :owner")
                .setParameter("owner", target).executeUpdate());
    }

    private void cleanup(Long target, Long other, Long viewer, Long presetId) {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) purgeExecutor;
        await().atMost(Duration.ofSeconds(10)).until(() ->
                pool.getActiveCount() == 0 && pool.getThreadPoolExecutor().getQueue().isEmpty());
        transactionTemplate.executeWithoutResult(tx -> {
            String owners = target + (other == null ? "" : "," + other);
            entityManager.createNativeQuery("DELETE FROM visibility_template_rules WHERE template_id IN "
                    + "(SELECT id FROM visibility_templates WHERE owner_user_id IN (" + owners + ")"
                    + (presetId == null ? "" : " OR id = " + presetId) + ")").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM visibility_templates WHERE owner_user_id IN (" + owners
                    + ")" + (presetId == null ? "" : " OR id = " + presetId)).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM account_purge_completion_status WHERE user_id IN ("
                    + owners + ")").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM users WHERE id IN (" + owners + "," + viewer + ")")
                    .executeUpdate();
        });
    }

    private void stubRedis() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
    }

    private Long createUser(String name) {
        return transactionTemplate.execute(tx -> {
            UserEntity user = UserEntity.builder()
                    .email("cmp1243-vt-" + System.nanoTime() + "@example.com")
                    .lastName("試練").firstName(name).displayName(name)
                    .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                    .isSearchable(true).build();
            entityManager.persist(user);
            entityManager.flush();
            return user.getId();
        });
    }

    private long count(String sql) {
        return transactionTemplate.execute(tx -> ((Number) entityManager.createNativeQuery(sql)
                .getSingleResult()).longValue());
    }
}
