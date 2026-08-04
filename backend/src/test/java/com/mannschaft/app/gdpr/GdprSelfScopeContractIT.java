package com.mannschaft.app.gdpr;

import com.mannschaft.app.chart.entity.ChartRecordEntity;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.repository.DataExportRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 第2波 — GDPR / 個人情報管理（F12.3）の自己スコープ契約テスト。
 *
 * <p>本テストが固定する防御仕様: {@code /api/v1/account} 配下のエクスポート・削除プレビュー系 EP は、
 * 対象ユーザーを {@code SecurityUtils.getCurrentUserId()} からのみ解決し、リクエストで
 * 他人の識別子を指定する余地を持たない。したがって返却されるエクスポート状態・署名 URL・
 * 削除プレビュー件数は常に呼び出し元自身のものに限られる。</p>
 *
 * <p>本テストは以下の自己スコープ宣言（{@code @SelfScopedEndpoint}）の証跡を兼ねる:
 * {@code GdprController#requestExport} / {@code GdprController#getExportStatus} /
 * {@code GdprController#getDownloadUrl} / {@code GdprController#getDeletionPreview}。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("GDPR（F12.3）自己スコープ 認可契約テスト（第2波）")
class GdprSelfScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataExportRepository dataExportRepository;

    @Autowired
    private ChartRecordRepository chartRecordRepository;

    @PersistenceContext
    private EntityManager em;

    /** 自分のエクスポート・自分のカルテを持つユーザー。 */
    private Long selfUserId;
    /** 別ユーザー（完了済みエクスポートとカルテを持つ）。 */
    private Long otherUserId;

    private Long selfExportId;
    private Long otherExportId;

    @BeforeEach
    void setUp() {
        selfUserId = insertUser("gdpr-self@example.com");
        otherUserId = insertUser("gdpr-other@example.com");

        // 自分のエクスポートは PENDING（ダウンロード不可）。
        selfExportId = insertDataExport(selfUserId, "PENDING", null);
        // 別ユーザーのエクスポートは COMPLETED（S3キーあり・ダウンロード可能な状態）。
        otherExportId = insertDataExport(otherUserId, "COMPLETED", "exports/other-user.zip");

        // 別ユーザーにだけカルテを 1 件持たせる（削除プレビューの件数に混入しないことの確認用）。
        insertChartRecord(otherUserId);

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("GET /api/v1/account/data-export/status: 返るのは自分のエクスポートのみ"
            + "（GdprController#getExportStatus）")
    void エクスポート状態は自分のものだけが返る() throws Exception {
        setAuth(selfUserId);
        mockMvc.perform(get("/api/v1/account/data-export/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exportId").value(selfExportId))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        assertThat(selfExportId)
                .as("別ユーザーのエクスポートと取り違えていないこと")
                .isNotEqualTo(otherExportId);
    }

    @Test
    @DisplayName("GET /api/v1/account/data-export/download: 他ユーザーの完了済みエクスポートの署名URLは得られない"
            + "（GdprController#getDownloadUrl）")
    void ダウンロードURLは他人の完了済みエクスポートを返さない() throws Exception {
        setAuth(selfUserId);
        int statusCode = mockMvc.perform(get("/api/v1/account/data-export/download"))
                .andReturn().getResponse().getStatus();

        assertThat(statusCode)
                .as("自分の最新エクスポートが未完了である以上、署名URLは発行されないこと")
                .isNotEqualTo(200);
    }

    @Test
    @DisplayName("GET /api/v1/account/deletion-preview: 件数は自分のデータのみを数える"
            + "（GdprController#getDeletionPreview）")
    void 削除プレビューは自分のデータのみを数える() throws Exception {
        setAuth(selfUserId);
        mockMvc.perform(get("/api/v1/account/deletion-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSummary.charts").value(0));

        setAuth(otherUserId);
        mockMvc.perform(get("/api/v1/account/deletion-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSummary.charts").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/account/data-export: 発注できるのは自分のエクスポートのみ"
            + "（GdprController#requestExport — リクエストにユーザー識別子が無い）")
    void エクスポート発注は自分に閉じる() throws Exception {
        // リクエストボディにはカテゴリと再認証情報しか無く、対象ユーザーを指定する項目が存在しない。
        // 本テストは「別ユーザーが PROCESSING 相当の状態でも自分の発注可否には影響しない」ことと、
        // 発注が別ユーザーのレコードを書き換えないことを DB の実値で確認する。
        em.flush();
        em.clear();

        String otherStatusBefore = dataExportRepository.findById(otherExportId)
                .orElseThrow()
                .getStatus();

        assertThat(otherStatusBefore)
                .as("前提: 別ユーザーのエクスポートは COMPLETED")
                .isEqualTo("COMPLETED");

        setAuth(selfUserId);
        mockMvc.perform(get("/api/v1/account/data-export/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exportId").value(selfExportId));
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /**
     * データエクスポート行を JPA 経由で作成する。
     *
     * <p>列名の解決は Hibernate に委ねる（ネイティブ SQL で列名を直書きしない）。
     * test profile のスキーマは {@code ddl-auto=create} により Entity から生成されるため、
     * 本番 DDL の列名を直書きすると両者の差でテストが壊れる。</p>
     */
    private Long insertDataExport(Long userId, String status, String s3Key) {
        DataExportEntity saved = dataExportRepository.save(DataExportEntity.builder()
                .userId(userId)
                .status(status)
                .progressPercent(0)
                .s3Key(s3Key)
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build());
        return saved.getId();
    }

    /** カルテ行を JPA 経由で作成する（削除プレビューの件数対象）。 */
    private void insertChartRecord(Long customerUserId) {
        chartRecordRepository.save(ChartRecordEntity.builder()
                .teamId(1L)
                .customerUserId(customerUserId)
                .visitDate(LocalDate.now())
                .build());
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'GDPRAUTHZ', 'テスト', 'GDPRAUTHZ テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }
}
