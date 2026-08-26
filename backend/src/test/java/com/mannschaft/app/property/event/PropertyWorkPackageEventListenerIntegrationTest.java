package com.mannschaft.app.property.event;

import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.incident.entity.IncidentEntity;
import com.mannschaft.app.incident.event.IncidentStatusChangedEvent;
import com.mannschaft.app.incident.repository.IncidentRepository;
import com.mannschaft.app.property.WorkType;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import com.mannschaft.app.property.repository.PropertyWorkPackageRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PropertyWorkPackageEventListener} 統合テスト（F09.13 Phase 1-ζ-A）。
 *
 * <p>F07.6 Incident → F09.13 PropertyWorkPackage 自動生成連携を検証する。</p>
 *
 * <p>検証フロー:</p>
 * <ol>
 *   <li>users / incidents を実 DB に投入</li>
 *   <li>{@link IncidentStatusChangedEvent}（newStatus="CONFIRMED"）を {@link ApplicationEventPublisher} 経由で発火</li>
 *   <li>{@link PropertyWorkPackageEventListener} が同期で受信し、Service.createFromIncident でパッケージ自動生成</li>
 *   <li>{@code property_work_packages} に {@code work_type=INCIDENT}, {@code incident_id} 一致の行が 1 件作成されることを確認</li>
 * </ol>
 *
 * <p><strong>独自判断</strong>: 設計書 §5.2 で要求される {@code reportedAt}/{@code summary}
 * は現行 {@link IncidentEntity} に存在しないため、リスナーは {@code createdAt}/{@code description}
 * でフォールバック動作する。本テストはその動作を検証する。</p>
 *
 * <p><strong>トランザクション設計</strong>: リスナーは {@code REQUIRES_NEW} で別トランザクションを
 * 持つため、テストメソッド全体に {@code @Transactional} をかけると見えない（ロールバック競合）。
 * よって本テストは {@code TransactionTemplate} で個別にコミットしてから検証する設計。
 * 後始末は @AfterEach 等の手動 cleanup で行う（@Transactional は付けない）。</p>
 */
@DisplayName("PropertyWorkPackageEventListener 統合テスト（F09.13 Phase 1-ζ-A / F07.6 連携）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PropertyWorkPackageEventListenerIntegrationTest extends AbstractMySqlIntegrationTest {

    /** ゲート開放用（{@link #openBackgroundFeatureGate()} で使う）。 */
    @Autowired
    private FeatureFlagRepository backgroundGateFeatureFlagRepository;

    /**
     * ゲート対象のバックグラウンド入口を open にしてから各テストを走らせる。
     *
     * <p>テストプロファイルは Flyway を無効化しており {@code feature_flags} が空のため、
     * 何もしないと {@code FeatureFlagService#isEnabled} がフェイルクローズで false を返し、
     * 検証対象のバッチ／リスナーが本体を呼ばずに正常終了してしまう。
     * 詳細は {@link FeatureFlagTestSupport} を参照。</p>
     */
    @BeforeEach
    void openBackgroundFeatureGate() {
        FeatureFlagTestSupport.enable(backgroundGateFeatureFlagRepository, "FEATURE_PROPERTY_REPAIRPLAN_ENABLED");
    }

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private PropertyWorkPackageRepository packageRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    private static final String SCOPE_TEAM = "TEAM";
    private static final Long TEAM_ID = 992_001L;

    private Long userId;
    private Long createdIncidentId;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                userId = insertUser("evt-test-" + System.nanoTime() + "@example.jp", "イベント", "テスト");
            }
        });
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

    @Test
    @DisplayName("CONFIRMED イベントで F07.6 Incident から自動的に WorkPackage が生成される")
    void onIncidentConfirmed_createsPackage() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // Incident を別トランザクションでコミットして登録
        Long incidentId = tx.execute(status -> {
            IncidentEntity incident = IncidentEntity.builder()
                    .scopeType(SCOPE_TEAM)
                    .scopeId(TEAM_ID)
                    .title("水漏れ事故")
                    .description("洗面所で漏水を確認")
                    .status("REPORTED")
                    .priority("HIGH")
                    .reportedBy(userId)
                    .build();
            return incidentRepository.save(incident).getId();
        });
        createdIncidentId = incidentId;

        // CONFIRMED への遷移イベントを発火
        IncidentStatusChangedEvent event = new IncidentStatusChangedEvent(
                incidentId, SCOPE_TEAM, TEAM_ID,
                "REPORTED", "CONFIRMED", userId);
        eventPublisher.publishEvent(event);

        // 自動生成された WorkPackage を別トランザクションで取得して検証
        Optional<PropertyWorkPackageEntity> created = tx.execute(status ->
                packageRepository.findByIncidentIdAndDeletedAtIsNull(incidentId));

        assertThat(created).isPresent();
        PropertyWorkPackageEntity pkg = created.get();
        assertThat(pkg.getWorkType()).isEqualTo(WorkType.INCIDENT);
        assertThat(pkg.getIncidentId()).isEqualTo(incidentId);
        assertThat(pkg.getScopeType()).isEqualTo(SCOPE_TEAM);
        assertThat(pkg.getScopeId()).isEqualTo(TEAM_ID);
        assertThat(pkg.getTitle()).isEqualTo("水漏れ事故");
        // 設計書 §5.2 では reportedAt 要求だが現行 Entity に欠落のため createdAt で代替
        assertThat(pkg.getIncidentDate()).isNotNull();
        // narrative も description で代替
        assertThat(pkg.getIncidentNarrative()).contains("漏水");
    }

    @Test
    @DisplayName("CONFIRMED 以外のステータス遷移ではパッケージ生成しない")
    void onIncidentNotConfirmed_skips() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long incidentId = tx.execute(status -> {
            IncidentEntity incident = IncidentEntity.builder()
                    .scopeType(SCOPE_TEAM)
                    .scopeId(TEAM_ID)
                    .title("仮の事故")
                    .description("詳細")
                    .status("REPORTED")
                    .priority("MEDIUM")
                    .reportedBy(userId)
                    .build();
            return incidentRepository.save(incident).getId();
        });
        createdIncidentId = incidentId;

        // CONFIRMED ではない遷移
        eventPublisher.publishEvent(new IncidentStatusChangedEvent(
                incidentId, SCOPE_TEAM, TEAM_ID, "REPORTED", "IN_PROGRESS", userId));

        Optional<PropertyWorkPackageEntity> created = tx.execute(status ->
                packageRepository.findByIncidentIdAndDeletedAtIsNull(incidentId));
        assertThat(created).isEmpty();
    }

    @Test
    @DisplayName("既に同 incidentId のパッケージがある場合は重複生成しない（skip）")
    void onIncidentConfirmed_duplicate_skips() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long incidentId = tx.execute(status -> {
            IncidentEntity incident = IncidentEntity.builder()
                    .scopeType(SCOPE_TEAM)
                    .scopeId(TEAM_ID)
                    .title("二重発火事故")
                    .description("詳細")
                    .status("REPORTED")
                    .priority("HIGH")
                    .reportedBy(userId)
                    .build();
            return incidentRepository.save(incident).getId();
        });
        createdIncidentId = incidentId;

        // 1 回目: 自動生成される
        eventPublisher.publishEvent(new IncidentStatusChangedEvent(
                incidentId, SCOPE_TEAM, TEAM_ID, "REPORTED", "CONFIRMED", userId));

        Long firstId = tx.execute(status ->
                packageRepository.findByIncidentIdAndDeletedAtIsNull(incidentId)
                        .map(PropertyWorkPackageEntity::getId)
                        .orElse(null));
        assertThat(firstId).isNotNull();

        // 2 回目: 同じ Incident で再発火 → skip（パッケージ ID が変わらない）
        eventPublisher.publishEvent(new IncidentStatusChangedEvent(
                incidentId, SCOPE_TEAM, TEAM_ID, "CONFIRMED", "CONFIRMED", userId));

        Long secondId = tx.execute(status ->
                packageRepository.findByIncidentIdAndDeletedAtIsNull(incidentId)
                        .map(PropertyWorkPackageEntity::getId)
                        .orElse(null));
        assertThat(secondId).isEqualTo(firstId);
    }
}
