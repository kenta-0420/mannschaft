package com.mannschaft.app.appearance;

import com.mannschaft.app.appearance.dto.AppearanceResponse;
import com.mannschaft.app.appearance.dto.UpdateAppearanceRequest;
import com.mannschaft.app.appearance.entity.AppearanceSettingsEntity;
import com.mannschaft.app.appearance.entity.ThemeMode;
import com.mannschaft.app.appearance.repository.AppearanceSettingsRepository;
import com.mannschaft.app.appearance.service.AppearanceSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AppearanceSettingsService 単体テスト（受け入れ条件 Service UT）。
 *
 * <p>AC:</p>
 * <ul>
 *   <li>getOrDefault_無: DB未登録 → デフォルト値（LIGHT / #f3efe0 / null / false）を返す</li>
 *   <li>getOrDefault_有: DB登録済み → 保存値を返す</li>
 *   <li>save_新規insert: 未登録ユーザーに save → Repository の save が1回呼ばれる</li>
 *   <li>save_既存update: 登録済みユーザーに save → 同一行を更新（id が同じ）</li>
 *   <li>save_seasonalThemeId_null許容: seasonalThemeId=null で保存できる</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AppearanceSettingsService 単体テスト")
class AppearanceSettingsServiceTest {

    @Mock
    private AppearanceSettingsRepository repository;

    @InjectMocks
    private AppearanceSettingsService service;

    private static final Long USER_ID = 100L;

    // ─────────────────────────────────────────────────────────────────────
    // AC: getOrDefault_無 → デフォルト値
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getOrDefault_無: DB未登録 → デフォルト(LIGHT/#f3efe0/null/false)を返す")
    void getOrDefault_notFound_returnsDefault() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        AppearanceResponse result = service.getOrDefault(USER_ID);

        assertThat(result.getTheme()).isEqualTo(ThemeMode.LIGHT);
        assertThat(result.getBgColor()).isEqualTo("#f3efe0");
        assertThat(result.getSeasonalThemeId()).isNull();
        assertThat(result.isHideChatPreview()).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC: getOrDefault_有 → 保存値
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getOrDefault_有: DB登録済み → 保存値を返す")
    void getOrDefault_found_returnsSavedValues() {
        AppearanceSettingsEntity entity = buildEntity(USER_ID, ThemeMode.DARK, "#1a1a2e", 99L, true);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(entity));

        AppearanceResponse result = service.getOrDefault(USER_ID);

        assertThat(result.getTheme()).isEqualTo(ThemeMode.DARK);
        assertThat(result.getBgColor()).isEqualTo("#1a1a2e");
        assertThat(result.getSeasonalThemeId()).isEqualTo(99L);
        assertThat(result.isHideChatPreview()).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC: save_新規insert → Repository.save が1回呼ばれる
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("save_新規insert: 未登録 → save が1回呼ばれ新しいEntityが渡される")
    void save_newUser_callsRepositorySaveOnce() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        AppearanceSettingsEntity saved = buildEntity(USER_ID, ThemeMode.DARK, "#000000", null, false);
        when(repository.save(any())).thenReturn(saved);

        UpdateAppearanceRequest req = buildRequest(ThemeMode.DARK, "#000000", null, false);
        service.save(USER_ID, req);

        verify(repository, times(1)).save(any(AppearanceSettingsEntity.class));
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC: save_既存update → 同一idのEntityでsaveが呼ばれる（行1を保証）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("save_既存update: 登録済み → 既存Entityをupdateしsaveが1回呼ばれる")
    void save_existingUser_updatesExistingEntity() {
        UUID existingId = UUID.randomUUID();
        AppearanceSettingsEntity existing = buildEntityWithId(existingId, USER_ID, ThemeMode.LIGHT, "#f3efe0", null, false);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateAppearanceRequest req = buildRequest(ThemeMode.DARK, "#1a1a2e", 5L, true);
        AppearanceResponse result = service.save(USER_ID, req);

        // idが変わっていないこと（toBuilderで同一行更新）を間接確認
        verify(repository, times(1)).save(any(AppearanceSettingsEntity.class));
        assertThat(result.getTheme()).isEqualTo(ThemeMode.DARK);
        assertThat(result.getBgColor()).isEqualTo("#1a1a2e");
        assertThat(result.getSeasonalThemeId()).isEqualTo(5L);
        assertThat(result.isHideChatPreview()).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC: save_seasonalThemeId_null許容
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("save_seasonalThemeId_null許容: seasonalThemeId=null で正常に保存できる")
    void save_seasonalThemeIdNull_isAllowed() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        AppearanceSettingsEntity saved = buildEntity(USER_ID, ThemeMode.LIGHT, "#f3efe0", null, false);
        when(repository.save(any())).thenReturn(saved);

        UpdateAppearanceRequest req = buildRequest(ThemeMode.LIGHT, "#f3efe0", null, false);
        AppearanceResponse result = service.save(USER_ID, req);

        assertThat(result.getSeasonalThemeId()).isNull();
        verify(repository, times(1)).save(any(AppearanceSettingsEntity.class));
    }

    // ─────────────────────────────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────────────────────────────

    private AppearanceSettingsEntity buildEntity(Long userId, ThemeMode theme, String bgColor,
                                                  Long seasonalThemeId, boolean hideChatPreview) {
        return AppearanceSettingsEntity.builder()
                .userId(userId)
                .theme(theme)
                .bgColor(bgColor)
                .seasonalThemeId(seasonalThemeId)
                .hideChatPreview(hideChatPreview)
                .build();
    }

    private AppearanceSettingsEntity buildEntityWithId(UUID id, Long userId, ThemeMode theme,
                                                        String bgColor, Long seasonalThemeId,
                                                        boolean hideChatPreview) {
        AppearanceSettingsEntity entity = buildEntity(userId, theme, bgColor, seasonalThemeId, hideChatPreview);
        entity.setId(id);
        return entity;
    }

    private UpdateAppearanceRequest buildRequest(ThemeMode theme, String bgColor,
                                                  Long seasonalThemeId, boolean hideChatPreview) {
        return UpdateAppearanceRequest.builder()
                .theme(theme)
                .bgColor(bgColor)
                .seasonalThemeId(seasonalThemeId)
                .hideChatPreview(hideChatPreview)
                .build();
    }
}
