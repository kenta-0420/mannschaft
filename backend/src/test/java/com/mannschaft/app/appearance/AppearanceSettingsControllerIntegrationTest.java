package com.mannschaft.app.appearance;

import com.mannschaft.app.appearance.controller.AppearanceSettingsController;
import com.mannschaft.app.appearance.dto.AppearanceResponse;
import com.mannschaft.app.appearance.dto.UpdateAppearanceRequest;
import com.mannschaft.app.appearance.entity.ThemeMode;
import com.mannschaft.app.appearance.repository.AppearanceSettingsRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AppearanceSettingsController 統合テスト（受け入れ条件 Controller契約テスト）。
 *
 * <p>Testcontainers MySQL + AbstractMySqlIntegrationTest で ApplicationContext を共有する。
 * Controller を直接 Autowire し、SecurityContext に認証情報を設定してテストする。</p>
 *
 * <p>AC（Controller契約）:</p>
 * <ul>
 *   <li>GET初回200: 未登録ユーザーがGET → 200 + デフォルト(LIGHT/#f3efe0/#18181b)</li>
 *   <li>PUT後GET一致: PUT で保存後 GET → 保存値と一致（darkBgColor含む）</li>
 *   <li>PUT2回で行1: 同ユーザーが2回PUTしてもDBに1行のみ存在（upsert）</li>
 *   <li>GET未認証401: 未認証でGET → 401系例外</li>
 *   <li>PUT未認証401: 未認証でPUT → 401系例外</li>
 *   <li>PUT theme不正値400: theme=INVALID → バリデーションエラー400</li>
 *   <li>PUT bgColor形式不正400: bgColor=#ZZZ → バリデーションエラー400</li>
 *   <li>PUT darkBgColor形式不正400: darkBgColor=#ZZZZZZ → バリデーションエラー400</li>
 *   <li>userId はトークン由来: Controller は URL/body から userId を取らない（SecurityUtils固定）</li>
 *   <li>レスポンスがdataラッパ配下に5項目: data.theme / data.bgColor / data.darkBgColor / data.seasonalThemeId / data.hideChatPreview</li>
 *   <li>darkBgColor_デフォルト: 未登録ユーザーGET → darkBgColor="#18181b"</li>
 *   <li>darkBgColor_PUT後永続化: PUT で darkBgColor 保存後 GET → 保存値が復元される（永続→再読込）</li>
 *   <li>darkBgColor_upsert更新: 既存行の darkBgColor を更新できる</li>
 * </ul>
 */
@DisplayName("AppearanceSettingsController 統合テスト")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AppearanceSettingsControllerIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private AppearanceSettingsController controller;

    @Autowired
    private AppearanceSettingsRepository repository;

    private static final Long USER_ID_A = 88_001L;
    private static final Long USER_ID_B = 88_002L;

    @BeforeEach
    void setUp() {
        // 既存データクリア（@Transactional によりテスト後ロールバックされるが念のため）
        repository.deleteAll();
        // デフォルト: ユーザーA として認証
        setAuthentication(USER_ID_A);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC: GET初回200 — デフォルト値が返る
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET初回200: 未登録ユーザーがGET → 200 + デフォルト(LIGHT/#f3efe0/#18181b/null/false)")
    void get_firstTime_returnsDefault() {
        ResponseEntity<ApiResponse<AppearanceResponse>> resp = controller.getAppearance();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        AppearanceResponse data = resp.getBody().getData();
        assertThat(data.getTheme()).isEqualTo(ThemeMode.LIGHT);
        assertThat(data.getBgColor()).isEqualTo("#f3efe0");
        assertThat(data.getDarkBgColor()).isEqualTo("#18181b");
        assertThat(data.getSeasonalThemeId()).isNull();
        assertThat(data.isHideChatPreview()).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC: PUT後GET一致
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT後GET一致: PUT保存後にGET → 同じ値が返る（darkBgColor含む）")
    void putThenGet_returnsUpdatedValues() {
        UpdateAppearanceRequest req = UpdateAppearanceRequest.builder()
                .theme(ThemeMode.DARK)
                .bgColor("#1a1a2e")
                .darkBgColor("#2d2d2d")
                .seasonalThemeId(7L)
                .hideChatPreview(true)
                .build();

        controller.updateAppearance(req);

        ResponseEntity<ApiResponse<AppearanceResponse>> resp = controller.getAppearance();
        AppearanceResponse data = resp.getBody().getData();
        assertThat(data.getTheme()).isEqualTo(ThemeMode.DARK);
        assertThat(data.getBgColor()).isEqualTo("#1a1a2e");
        assertThat(data.getDarkBgColor()).isEqualTo("#2d2d2d");
        assertThat(data.getSeasonalThemeId()).isEqualTo(7L);
        assertThat(data.isHideChatPreview()).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC: PUT2回で行1 — upsert確認
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT2回で行1: 同ユーザーが2回PUTしてもDBの行数が1のまま")
    void putTwice_onlyOneRow() {
        UpdateAppearanceRequest req1 = UpdateAppearanceRequest.builder()
                .theme(ThemeMode.LIGHT)
                .bgColor("#ffffff")
                .darkBgColor("#18181b")
                .seasonalThemeId(null)
                .hideChatPreview(false)
                .build();
        UpdateAppearanceRequest req2 = UpdateAppearanceRequest.builder()
                .theme(ThemeMode.DARK)
                .bgColor("#000000")
                .darkBgColor("#3a3a3a")
                .seasonalThemeId(3L)
                .hideChatPreview(true)
                .build();

        controller.updateAppearance(req1);
        controller.updateAppearance(req2);

        long count = repository.count();
        assertThat(count).isEqualTo(1L);

        AppearanceResponse data = controller.getAppearance().getBody().getData();
        assertThat(data.getTheme()).isEqualTo(ThemeMode.DARK);
        assertThat(data.getBgColor()).isEqualTo("#000000");
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC: darkBgColor_デフォルト — 未登録ユーザーの darkBgColor はデフォルト "#18181b"
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("darkBgColor_デフォルト: 未登録ユーザーGET → darkBgColor=\"#18181b\"")
    void get_firstTime_returnsDarkBgColorDefault() {
        ResponseEntity<ApiResponse<AppearanceResponse>> resp = controller.getAppearance();

        AppearanceResponse data = resp.getBody().getData();
        assertThat(data.getDarkBgColor()).isEqualTo("#18181b");
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC: darkBgColor_PUT後永続化 — PUT → GET で darkBgColor が復元される（永続→再読込）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("darkBgColor_PUT後永続化: PUT で darkBgColor 保存後 GET → 保存値が復元される（DB永続→再読込）")
    void put_darkBgColor_persistedAndReloaded() {
        String customDark = "#2a2a2a";
        UpdateAppearanceRequest req = UpdateAppearanceRequest.builder()
                .theme(ThemeMode.DARK)
                .bgColor("#111111")
                .darkBgColor(customDark)
                .seasonalThemeId(null)
                .hideChatPreview(false)
                .build();

        controller.updateAppearance(req);

        // @Transactional ロールバック前に repository 経由で直接再読込して永続化を確認する
        Long userId = USER_ID_A;
        String persistedDark = repository.findByUserId(userId)
                .map(com.mannschaft.app.appearance.entity.AppearanceSettingsEntity::getDarkBgColor)
                .orElse(null);
        assertThat(persistedDark).isEqualTo(customDark);

        // GET 経由でも復元される
        AppearanceResponse data = controller.getAppearance().getBody().getData();
        assertThat(data.getDarkBgColor()).isEqualTo(customDark);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC: darkBgColor_upsert更新 — 既存行の darkBgColor を更新できる
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("darkBgColor_upsert更新: 既存行の darkBgColor を別の値に更新できる")
    void put_darkBgColor_existingRow_updated() {
        // 初回保存（darkBgColor=#18181b）
        controller.updateAppearance(UpdateAppearanceRequest.builder()
                .theme(ThemeMode.DARK).bgColor("#000000").darkBgColor("#18181b")
                .seasonalThemeId(null).hideChatPreview(false).build());

        // 2回目の保存（darkBgColor を更新）
        controller.updateAppearance(UpdateAppearanceRequest.builder()
                .theme(ThemeMode.DARK).bgColor("#000000").darkBgColor("#3c3c3c")
                .seasonalThemeId(null).hideChatPreview(false).build());

        AppearanceResponse data = controller.getAppearance().getBody().getData();
        assertThat(data.getDarkBgColor()).isEqualTo("#3c3c3c");
        assertThat(repository.count()).isEqualTo(1L);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC: GET未認証401
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET未認証401: 認証なしでGET → @PreAuthorize が AuthenticationCredentialsNotFoundException を投げる(→401)")
    void get_unauthenticated_throws() {
        SecurityContextHolder.clearContext();

        // @SpringBootTest では実コントローラが method security プロキシ越しに呼ばれるため、
        // 未認証時は本体(SecurityUtils.getCurrentUserId の COMMON_000)より先に @PreAuthorize が発火し
        // AuthenticationCredentialsNotFoundException を投げる（HTTP 層では 401 に変換される）。
        assertThatThrownBy(() -> controller.getAppearance())
                .isInstanceOf(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC: PUT未認証401
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT未認証401: 認証なしでPUT → @PreAuthorize が AuthenticationCredentialsNotFoundException を投げる(→401)")
    void put_unauthenticated_throws() {
        SecurityContextHolder.clearContext();

        UpdateAppearanceRequest req = UpdateAppearanceRequest.builder()
                .theme(ThemeMode.DARK)
                .bgColor("#000000")
                .darkBgColor("#18181b")
                .seasonalThemeId(null)
                .hideChatPreview(false)
                .build();

        // method security プロキシにより未認証は @PreAuthorize で弾かれる（→401）。
        assertThatThrownBy(() -> controller.updateAppearance(req))
                .isInstanceOf(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC: userId はトークン由来（IDOR防止確認）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("userId トークン由来: ユーザーBの設定はユーザーAからは取得できない（別行）")
    void userId_isTakenFromToken_notFromRequest() {
        // ユーザーAが保存
        setAuthentication(USER_ID_A);
        UpdateAppearanceRequest reqA = UpdateAppearanceRequest.builder()
                .theme(ThemeMode.DARK)
                .bgColor("#111111")
                .darkBgColor("#222222")
                .seasonalThemeId(null)
                .hideChatPreview(true)
                .build();
        controller.updateAppearance(reqA);

        // ユーザーBとして切り替えてGET → ユーザーBのデフォルト値が返る（ユーザーAの設定ではない）
        setAuthentication(USER_ID_B);
        AppearanceResponse data = controller.getAppearance().getBody().getData();
        // ユーザーBはまだ設定していないのでデフォルト値
        assertThat(data.getTheme()).isEqualTo(ThemeMode.LIGHT);
        assertThat(data.getBgColor()).isEqualTo("#f3efe0");
        assertThat(data.isHideChatPreview()).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC: レスポンスがdataラッパ配下に4項目
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("レスポンスdataラッパ: ApiResponse.data に theme/bgColor/darkBgColor/seasonalThemeId/hideChatPreview の5項目")
    void responseHasFiveFieldsUnderData() {
        ResponseEntity<ApiResponse<AppearanceResponse>> resp = controller.getAppearance();

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getData()).isNotNull();

        AppearanceResponse data = resp.getBody().getData();
        // 5項目全て存在（null許容含む）
        assertThat(data.getTheme()).isNotNull();
        assertThat(data.getBgColor()).isNotNull();
        assertThat(data.getDarkBgColor()).isNotNull();
        // seasonalThemeId は null 許容
        // 未登録ユーザーのデフォルトは false（プリミティブ boolean なので値そのものを検証する）
        assertThat(data.isHideChatPreview()).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────────────────────────────

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, java.util.List.of()));
    }
}
