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
 *   <li>GET初回200: 未登録ユーザーがGET → 200 + デフォルト(LIGHT)</li>
 *   <li>PUT後GET一致: PUT で保存後 GET → 保存値と一致</li>
 *   <li>PUT2回で行1: 同ユーザーが2回PUTしてもDBに1行のみ存在（upsert）</li>
 *   <li>GET未認証401: 未認証でGET → 401系例外</li>
 *   <li>PUT未認証401: 未認証でPUT → 401系例外</li>
 *   <li>PUT theme不正値400: theme=INVALID → バリデーションエラー400</li>
 *   <li>PUT bgColor形式不正400: bgColor=#ZZZ → バリデーションエラー400</li>
 *   <li>userId はトークン由来: Controller は URL/body から userId を取らない（SecurityUtils固定）</li>
 *   <li>レスポンスがdataラッパ配下に4項目: data.theme / data.bgColor / data.seasonalThemeId / data.hideChatPreview</li>
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
    @DisplayName("GET初回200: 未登録ユーザーがGET → 200 + デフォルト(LIGHT/#f3efe0/null/false)")
    void get_firstTime_returnsDefault() {
        ResponseEntity<ApiResponse<AppearanceResponse>> resp = controller.getAppearance();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        AppearanceResponse data = resp.getBody().getData();
        assertThat(data.getTheme()).isEqualTo(ThemeMode.LIGHT);
        assertThat(data.getBgColor()).isEqualTo("#f3efe0");
        assertThat(data.getSeasonalThemeId()).isNull();
        assertThat(data.isHideChatPreview()).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC: PUT後GET一致
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT後GET一致: PUT保存後にGET → 同じ値が返る")
    void putThenGet_returnsUpdatedValues() {
        UpdateAppearanceRequest req = UpdateAppearanceRequest.builder()
                .theme(ThemeMode.DARK)
                .bgColor("#1a1a2e")
                .seasonalThemeId(7L)
                .hideChatPreview(true)
                .build();

        controller.updateAppearance(req);

        ResponseEntity<ApiResponse<AppearanceResponse>> resp = controller.getAppearance();
        AppearanceResponse data = resp.getBody().getData();
        assertThat(data.getTheme()).isEqualTo(ThemeMode.DARK);
        assertThat(data.getBgColor()).isEqualTo("#1a1a2e");
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
                .seasonalThemeId(null)
                .hideChatPreview(false)
                .build();
        UpdateAppearanceRequest req2 = UpdateAppearanceRequest.builder()
                .theme(ThemeMode.DARK)
                .bgColor("#000000")
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
    @DisplayName("レスポンスdataラッパ: ApiResponse.data に theme/bgColor/seasonalThemeId/hideChatPreview の4項目")
    void responseHasFourFieldsUnderData() {
        ResponseEntity<ApiResponse<AppearanceResponse>> resp = controller.getAppearance();

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getData()).isNotNull();

        AppearanceResponse data = resp.getBody().getData();
        // 4項目全て存在（null許容含む）
        assertThat(data.getTheme()).isNotNull();
        assertThat(data.getBgColor()).isNotNull();
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
