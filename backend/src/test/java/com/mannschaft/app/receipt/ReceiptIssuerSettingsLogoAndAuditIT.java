package com.mannschaft.app.receipt;

import com.mannschaft.app.auth.entity.AuditLogEntity;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.receipt.entity.ReceiptIssuerSettingsEntity;
import com.mannschaft.app.receipt.repository.ReceiptIssuerSettingsRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F08.4 発行者設定のロゴアップロード（D-3 / D-5(3)）と監査ログ（AC-33）の契約テスト（試練・実装前 red）。
 *
 * <p>正本は {@code docs/features/F08.4_receipt.md} §9.4。</p>
 *
 * <h2>本クラスが red である理由</h2>
 * <ul>
 *   <li>{@code POST /logo} は {@code MultipartFile} を受け取らず、
 *       {@code "receipt-logos/{scopeType}/{scopeId}/logo.png"} という固定キーを
 *       DB に書くだけで実ファイルをどこにも保存しない（AC-24 / AC-25 / AC-26）</li>
 *   <li>ストレージキーに正規化前の生の {@code scopeType} 文字列を連結しているため
 *       {@code team} と {@code TEAM} で別キーになる（AC-29）</li>
 *   <li>{@code RECEIPT_020} が {@code GlobalExceptionHandler} の status マップに未登録で
 *       既定 500（AC-10）</li>
 *   <li>{@code AuditEventType} に {@code RECEIPT_SETTINGS_UPDATED} が存在せず、
 *       サービスは {@code AuditLogService.record} を一度も呼ばない（AC-33）</li>
 * </ul>
 *
 * <h2>モック方針</h2>
 * <p>モックするのは <b>外部境界の {@link StorageService}（Cloudflare R2 / S3 互換）だけ</b>である。
 * 認可・DB・Security フィルタ・{@code GlobalExceptionHandler} はすべて実物を通す。
 * リサイズ結果はモックが受け取った <b>実バイト列を {@code ImageIO} でデコードして</b>検証するため、
 * 「呼ばれたこと」だけを見る偽 green にはならない。</p>
 *
 * <h2>監査ログの待ち方（AC-33）</h2>
 * <p>{@code AuditLogService.record} は {@code @Async} の fire-and-forget である。
 * 設計書 AC-33 の指定どおり <b>同期 Executor に差し替えて決定論化</b>し、Awaitility での
 * 待機は採らない（CI 負荷で揺れて flaky になるため）。{@code AsyncConfig} が複数の
 * {@code Executor} Bean を定義しているため {@code @Async}（無修飾）は型で一意解決できず、
 * Spring は Bean 名 {@code "taskExecutor"} を探しにいく。そこへ {@link SyncTaskExecutor} を
 * 差し込むことで、呼び出しは呼び出し元スレッド上で同期実行される。</p>
 */
@AutoConfigureMockMvc
@Transactional
@Import(ReceiptIssuerSettingsLogoAndAuditIT.SyncAsyncTestConfig.class)
@DisplayName("F08.4 発行者設定 ロゴ・監査ログ契約テスト（試練・実装前 red）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReceiptIssuerSettingsLogoAndAuditIT extends AbstractMySqlIntegrationTest {

    /**
     * {@code @Async} を同期実行に差し替えるテスト専用構成（AC-33）。
     */
    @TestConfiguration
    static class SyncAsyncTestConfig {
        @Bean("taskExecutor")
        TaskExecutor syncTaskExecutor() {
            return new SyncTaskExecutor();
        }
    }

    private static final String PATH = "/api/v1/admin/receipt-settings";
    private static final String LOGO_PATH = PATH + "/logo";

    private static final Long ADMIN_A = 920141001L;

    private static final AtomicInteger SLUG_SEQ = new AtomicInteger(0);

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ReceiptIssuerSettingsRepository issuerSettingsRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    /** 外部境界（R2 / S3）。ここだけモックしてよい。 */
    @MockitoBean
    private StorageService storageService;

    private Long teamAId;

    @BeforeEach
    void setUp() {
        MembershipTestHelper.insertActiveUser(em, ADMIN_A);
        Long adminRoleId = ensureRole("ADMIN", 2);
        ensureRole("SYSTEM_ADMIN", 1);
        ensureRole("DEPUTY_ADMIN", 3);
        ensureRole("MEMBER", 4);
        ensureRole("SUPPORTER", 5);
        ensureRole("GUEST", 6);

        teamAId = teamRepository.save(TeamEntity.builder()
                .slug("receipt-logo-" + SLUG_SEQ.incrementAndGet())
                .name("領収書ロゴテストチーム")
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                .supporterEnabled(true)
                .build()).getId();

        userRoleRepository.save(UserRoleEntity.builder()
                .userId(ADMIN_A).roleId(adminRoleId).teamId(teamAId).build());

        issuerSettingsRepository.save(ReceiptIssuerSettingsEntity.builder()
                .scopeType(ReceiptScopeType.TEAM)
                .scopeId(teamAId)
                .issuerName("ロゴテスト発行者")
                .isQualifiedInvoicer(true)
                .invoiceRegistrationNumber("T1111111111111")
                .fiscalYearStartMonth(4)
                .autoResetNumber(true)
                .build());
        em.flush();
        em.clear();
    }

    private Long ensureRole(String name, int priority) {
        return roleRepository.findByName(name)
                .map(RoleEntity::getId)
                .orElseGet(() -> roleRepository.save(RoleEntity.builder()
                        .name(name)
                        .displayName(name)
                        .priority(priority)
                        .isSystem("SYSTEM_ADMIN".equals(name))
                        .build()).getId());
    }

    /** 指定サイズの PNG バイト列を作る（透過あり）。 */
    private static byte[] transparentPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        // 全面を完全透過のまま残し、中央だけ不透明の赤にする。
        g.setColor(new Color(255, 0, 0, 255));
        g.fillRect(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static MockMultipartFile pngPart(byte[] bytes) {
        return new MockMultipartFile("file", "logo.png", MediaType.IMAGE_PNG_VALUE, bytes);
    }

    private ReceiptIssuerSettingsEntity reload() {
        em.flush();
        em.clear();
        return issuerSettingsRepository
                .findByScopeTypeAndScopeId(ReceiptScopeType.TEAM, teamAId).orElse(null);
    }

    // ───────────────────────────── AC-24: 実ファイルが UUID キーで保存される ─────────────────────────────

    @Test
    @WithMockUser(username = "920141001")
    @DisplayName("AC-24: ロゴをアップロードすると receipt-logos/{scopeType}/{scopeId}/{uuid}.{ext} で保存される")
    void ac24_uploadLogo_storesRealFileWithUuidKey() throws Exception {
        mockMvc.perform(multipart(LOGO_PATH)
                        .file(pngPart(transparentPng(120, 60)))
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isOk());

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageService).upload(keyCaptor.capture(), any(byte[].class), anyString());

        String key = keyCaptor.getValue();
        assertThat(key).matches(
                "^receipt-logos/TEAM/" + teamAId
                        + "/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(png|jpg|jpeg)$");

        ReceiptIssuerSettingsEntity reloaded = reload();
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getLogoStorageKey()).isEqualTo(key);
    }

    // ───────────────────────────── AC-25: 差し替え時に旧キーを削除する ─────────────────────────────

    @Test
    @WithMockUser(username = "920141001")
    @DisplayName("AC-25: ロゴを差し替えると旧キーのファイルが StorageService.delete で削除される")
    void ac25_uploadLogo_replacing_deletesOldObject() throws Exception {
        mockMvc.perform(multipart(LOGO_PATH)
                        .file(pngPart(transparentPng(120, 60)))
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isOk());
        String firstKey = reload().getLogoStorageKey();

        mockMvc.perform(multipart(LOGO_PATH)
                        .file(pngPart(transparentPng(100, 50)))
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isOk());

        verify(storageService).delete(firstKey);
        assertThat(reload().getLogoStorageKey()).isNotEqualTo(firstKey);
    }

    // ───────────────────────────── AC-26: 長辺 200px 以内・白背景合成 ─────────────────────────────

    @Test
    @WithMockUser(username = "920141001")
    @DisplayName("AC-26: 長辺 400px の画像は長辺 200px 以内へ縮小され、アスペクト比が維持される")
    void ac26_uploadLogo_resizesToMaxEdge200KeepingAspectRatio() throws Exception {
        mockMvc.perform(multipart(LOGO_PATH)
                        .file(pngPart(transparentPng(400, 200)))
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isOk());

        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(storageService).upload(anyString(), bytesCaptor.capture(), anyString());

        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(bytesCaptor.getValue()));
        assertThat(stored).isNotNull();
        assertThat(Math.max(stored.getWidth(), stored.getHeight())).isLessThanOrEqualTo(200);
        // 400x200（2:1）を維持していること。
        assertThat(stored.getWidth()).isEqualTo(200);
        assertThat(stored.getHeight()).isEqualTo(100);
    }

    @Test
    @WithMockUser(username = "920141001")
    @DisplayName("AC-26: 200px 以下の画像は拡大されない")
    void ac26_uploadLogo_doesNotUpscaleSmallImage() throws Exception {
        mockMvc.perform(multipart(LOGO_PATH)
                        .file(pngPart(transparentPng(80, 40)))
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isOk());

        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(storageService).upload(anyString(), bytesCaptor.capture(), anyString());

        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(bytesCaptor.getValue()));
        assertThat(stored).isNotNull();
        assertThat(stored.getWidth()).isEqualTo(80);
        assertThat(stored.getHeight()).isEqualTo(40);
    }

    @Test
    @WithMockUser(username = "920141001")
    @DisplayName("AC-26: 透過 PNG は白背景に合成され、黒く潰れない")
    void ac26_uploadLogo_flattensTransparencyOntoWhite() throws Exception {
        mockMvc.perform(multipart(LOGO_PATH)
                        .file(pngPart(transparentPng(120, 60)))
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isOk());

        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(storageService).upload(anyString(), bytesCaptor.capture(), anyString());

        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(bytesCaptor.getValue()));
        assertThat(stored).isNotNull();
        // 元画像の左上は完全透過。白背景合成なら白、既存 resize（背景を塗らない）なら黒になる。
        Color corner = new Color(stored.getRGB(0, 0), true);
        assertThat(corner.getRed()).isGreaterThanOrEqualTo(250);
        assertThat(corner.getGreen()).isGreaterThanOrEqualTo(250);
        assertThat(corner.getBlue()).isGreaterThanOrEqualTo(250);
    }

    // ───────────────────────────── AC-29: scopeType の大文字小文字でキーが変わらない ─────────────────────────────

    @Test
    @WithMockUser(username = "920141001")
    @DisplayName("AC-29: scopeType=team（小文字）でも生成されるキーの scopeType 部は TEAM になる")
    void ac29_uploadLogo_lowerCaseScopeType_normalizesKey() throws Exception {
        mockMvc.perform(multipart(LOGO_PATH)
                        .file(pngPart(transparentPng(120, 60)))
                        .param("scopeType", "team")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isOk());

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageService).upload(keyCaptor.capture(), any(byte[].class), anyString());

        assertThat(keyCaptor.getValue())
                .startsWith("receipt-logos/TEAM/" + teamAId + "/");
    }

    // ───────────────────────────── AC-10: 上限超過・非対応形式は 400（RECEIPT_020） ─────────────────────────────

    @Test
    @WithMockUser(username = "920141001")
    @DisplayName("AC-10: 1MB 超のロゴを送ると 400（RECEIPT_020）で返り 500 にならない")
    void ac10_uploadLogo_tooLarge_badRequest() throws Exception {
        byte[] oversized = new byte[1024 * 1024 + 1];
        // 先頭は PNG のマジックナンバーにしておき、サイズ超過だけが理由になるようにする。
        System.arraycopy(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}, 0, oversized, 0, 4);

        mockMvc.perform(multipart(LOGO_PATH)
                        .file(pngPart(oversized))
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RECEIPT_020"));
    }

    @Test
    @WithMockUser(username = "920141001")
    @DisplayName("AC-10: PNG / JPEG 以外（GIF）のロゴを送ると 400（RECEIPT_020）")
    void ac10_uploadLogo_unsupportedType_badRequest() throws Exception {
        MockMultipartFile gif = new MockMultipartFile(
                "file", "logo.gif", MediaType.IMAGE_GIF_VALUE,
                new byte[]{'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0});

        mockMvc.perform(multipart(LOGO_PATH)
                        .file(gif)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RECEIPT_020"));
    }

    @Test
    @WithMockUser(username = "920141001")
    @DisplayName("AC-10: 拡張子 png だが実体が PNG でないファイル（MIME 偽装）は 400（RECEIPT_020）")
    void ac10_uploadLogo_spoofedMagicNumber_badRequest() throws Exception {
        MockMultipartFile spoofed = new MockMultipartFile(
                "file", "logo.png", MediaType.IMAGE_PNG_VALUE,
                "this is definitely not an image".getBytes());

        mockMvc.perform(multipart(LOGO_PATH)
                        .file(spoofed)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RECEIPT_020"));
    }

    // ───────────────────────────── AC-33: 監査ログ ─────────────────────────────

    @Test
    @WithMockUser(username = "920141001")
    @DisplayName("AC-33: 登録番号を変更して保存すると RECEIPT_SETTINGS_UPDATED が旧値→新値付きで記録される")
    void ac33_patchSettings_changingRegistrationNumber_recordsAuditLogWithOldAndNew() throws Exception {
        mockMvc.perform(patch(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceRegistrationNumber\":\"T2222222222222\"}"))
                .andExpect(status().isOk());

        List<AuditLogEntity> logs = auditLogRepository.findAll().stream()
                .filter(l -> "RECEIPT_SETTINGS_UPDATED".equals(l.getEventType()))
                .toList();

        assertThat(logs)
                .as("audit_logs に RECEIPT_SETTINGS_UPDATED がちょうど 1 件記録されること")
                .hasSize(1);
        assertThat(logs.get(0).getMetadata())
                .as("metadata に旧値と新値の両方が含まれること")
                .contains("T1111111111111")
                .contains("T2222222222222");
    }

    @Test
    @WithMockUser(username = "920141001")
    @DisplayName("AC-33: 適格フラグを変更して保存すると RECEIPT_SETTINGS_UPDATED に旧値→新値が記録される")
    void ac33_patchSettings_changingQualifiedFlag_recordsAuditLog() throws Exception {
        mockMvc.perform(patch(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isQualifiedInvoicer\":false,"
                                + "\"invoiceRegistrationNumber\":\"\"}"))
                .andExpect(status().isOk());

        List<AuditLogEntity> logs = auditLogRepository.findAll().stream()
                .filter(l -> "RECEIPT_SETTINGS_UPDATED".equals(l.getEventType()))
                .toList();

        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getMetadata())
                .as("isQualifiedInvoicer の旧値 true と新値 false が含まれること")
                .contains("isQualifiedInvoicer")
                .contains("true")
                .contains("false");
    }

    @Test
    @WithMockUser(username = "920141001")
    @DisplayName("AC-33: 適格フラグ・登録番号を変えない保存では RECEIPT_SETTINGS_UPDATED を記録しない")
    void ac33_patchSettings_withoutInvoiceChange_doesNotRecordAuditLog() throws Exception {
        mockMvc.perform(patch(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customFooter\":\"フッターだけ変更\"}"))
                .andExpect(status().isOk());

        assertThat(auditLogRepository.findAll().stream()
                .filter(l -> "RECEIPT_SETTINGS_UPDATED".equals(l.getEventType()))
                .toList())
                .isEmpty();
    }
}
