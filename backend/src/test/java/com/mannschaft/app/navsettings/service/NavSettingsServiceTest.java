package com.mannschaft.app.navsettings.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.navsettings.dto.NavFeatureResponse;
import com.mannschaft.app.navsettings.dto.NavSettingsResponse;
import com.mannschaft.app.navsettings.entity.NavFeatureEntity;
import com.mannschaft.app.navsettings.entity.UserNavSettingsEntity;
import com.mannschaft.app.navsettings.repository.NavFeatureRepository;
import com.mannschaft.app.navsettings.repository.UserNavSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NavSettingsService 単体テスト")
class NavSettingsServiceTest {

    @Mock NavFeatureRepository navFeatureRepository;
    @Mock UserNavSettingsRepository userNavSettingsRepository;
    @Mock AuditLogService auditLogService;

    private NavSettingsService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new NavSettingsService(navFeatureRepository, userNavSettingsRepository, objectMapper, auditLogService);
    }

    private NavFeatureEntity makeFeature(String key, boolean fixed) {
        return makeFeature(key, fixed, 10);
    }

    private NavFeatureEntity makeFeature(String key, boolean fixed, int sortOrder) {
        NavFeatureEntity e = new NavFeatureEntity();
        e.setKey(key);
        e.setLabelKey("nav." + key);
        e.setIcon("pi pi-circle");
        e.setPath("/" + key);
        e.setFixed(fixed);
        e.setEnabled(true);
        e.setSortOrder(sortOrder);
        e.setMobileVisible(true);
        return e;
    }

    private List<String> orderedKeys(NavSettingsResponse res) {
        return res.getFeatures().stream().map(NavFeatureResponse::getKey).toList();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 既存テスト（表示ON/OFF）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getMyNavSettings: 固定項目は常にvisible=true")
    void getMyNavSettings_fixedItemAlwaysVisible() {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("calendar", true)));
        given(userNavSettingsRepository.findById(1L))
                .willReturn(Optional.of(hiddenSettings("[\"calendar\"]")));

        var result = service.getMyNavSettings(1L);

        assertThat(result.getFeatures()).hasSize(1);
        assertThat(result.getFeatures().get(0).isVisible()).isTrue();
    }

    @Test
    @DisplayName("getMyNavSettings: hiddenKeysに含まれる項目はvisible=false")
    void getMyNavSettings_hiddenItemNotVisible() {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("todo", false)));
        given(userNavSettingsRepository.findById(1L))
                .willReturn(Optional.of(hiddenSettings("[\"todo\"]")));

        var result = service.getMyNavSettings(1L);

        assertThat(result.getFeatures().get(0).isVisible()).isFalse();
    }

    @Test
    @DisplayName("getMyNavSettings: レコードなし（初回）は全項目visible=true")
    void getMyNavSettings_noRecord_allVisible() {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("todo", false), makeFeature("chat", false)));
        given(userNavSettingsRepository.findById(1L)).willReturn(Optional.empty());

        var result = service.getMyNavSettings(1L);

        assertThat(result.getFeatures()).allMatch(NavFeatureResponse::isVisible);
    }

    @Test
    @DisplayName("updateMyNavSettings: is_fixed=TRUEのキーでBusinessException")
    void updateMyNavSettings_fixedKey_throws() {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("calendar", true)));

        assertThatThrownBy(() -> service.updateMyNavSettings(1L, List.of("calendar"), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("updateMyNavSettings: 正常ケースでupsertが呼ばれる")
    void updateMyNavSettings_normal_upsertCalled() {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("todo", false)));

        service.updateMyNavSettings(1L, List.of("todo"), null);

        then(userNavSettingsRepository).should().upsertSettings(eq(1L), any(), any());
    }

    @Test
    @DisplayName("updateMyNavSettings: 正常ケースで監査ログが記録される")
    void updateMyNavSettings_normal_auditRecorded() {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("todo", false)));

        service.updateMyNavSettings(1L, List.of("todo"), null);

        then(auditLogService).should().record(
                eq(AuditEventType.NAV_SETTINGS_UPDATED.name()),
                eq(1L), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC1-1: navDisplayOrder の保存
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC1-1: navDisplayOrder を upsert で保存できる")
    void update_savesDisplayOrder() {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("a", false, 10), makeFeature("b", false, 20), makeFeature("c", false, 30)));

        service.updateMyNavSettings(1L, List.of(), List.of("c", "a", "b"));

        ArgumentCaptor<String> orderCaptor = ArgumentCaptor.forClass(String.class);
        then(userNavSettingsRepository).should().upsertSettings(eq(1L), any(), orderCaptor.capture());
        assertThat(orderCaptor.getValue()).contains("c").contains("a").contains("b");
    }

    @Test
    @DisplayName("AC1-1: navDisplayOrder=null のときは順序を NULL で保存する（リセット）")
    void update_nullDisplayOrder_savesNull() {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("a", false, 10)));

        service.updateMyNavSettings(1L, List.of(), null);

        ArgumentCaptor<String> orderCaptor = ArgumentCaptor.forClass(String.class);
        then(userNavSettingsRepository).should().upsertSettings(eq(1L), any(), orderCaptor.capture());
        assertThat(orderCaptor.getValue()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC1-4: 存在しない key は拒否
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC1-4: navDisplayOrder に存在しない key を含むと BusinessException")
    void update_unknownKeyInOrder_throws() {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("a", false, 10), makeFeature("b", false, 20)));

        assertThatThrownBy(() -> service.updateMyNavSettings(1L, List.of(), List.of("a", "ghost")))
                .isInstanceOf(BusinessException.class);

        then(userNavSettingsRepository).should(never()).upsertSettings(any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC1-2: 個人順 + マスタ補完
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC1-2: 個人順に無い新規 key はマスタ sort_order 順で末尾補完（欠落・重複なし）")
    void get_userOrderWithMissingKey_appendsByMasterSortOrder() {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("a", false, 10), makeFeature("b", false, 20),
                        makeFeature("c", false, 30), makeFeature("d", false, 40)));

        UserNavSettingsEntity entity = orderedSettings("[]", "[\"c\",\"a\"]");
        given(userNavSettingsRepository.findById(1L)).willReturn(Optional.of(entity));

        NavSettingsResponse res = service.getMyNavSettings(1L);

        assertThat(orderedKeys(res)).containsExactly("c", "a", "b", "d");
    }

    @Test
    @DisplayName("AC1-2: 個人順に未知/重複 key があっても無視され、欠落・重複なく解決される")
    void get_userOrderWithStaleOrDuplicateKey_isSanitized() {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("a", false, 10), makeFeature("b", false, 20), makeFeature("c", false, 30)));

        UserNavSettingsEntity entity = orderedSettings("[]", "[\"b\",\"removed\",\"a\",\"a\"]");
        given(userNavSettingsRepository.findById(1L)).willReturn(Optional.of(entity));

        NavSettingsResponse res = service.getMyNavSettings(1L);

        assertThat(orderedKeys(res)).containsExactly("b", "a", "c");
    }

    @Test
    @DisplayName("AC1-2: 個人順が無い（NULL）ときはマスタ sort_order 昇順で返る")
    void get_noUserOrder_returnsMasterOrder() {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("a", false, 10), makeFeature("b", false, 20), makeFeature("c", false, 30)));
        given(userNavSettingsRepository.findById(1L)).willReturn(Optional.empty());

        NavSettingsResponse res = service.getMyNavSettings(1L);

        assertThat(orderedKeys(res)).containsExactly("a", "b", "c");
    }

    // ─────────────────────────────────────────────────────────────────────
    // バグB修正: 'settings' 項目は個人並び順に関わらず常に最後尾に固定される
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("settings最後尾固定: 個人並び順の先頭に settings があっても応答の最後尾に強制移動される")
    void get_settingsAlwaysLast_evenIfUserOrderPutsItFirst() {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("calendar", true, 20),
                        makeFeature("todo", false, 30),
                        makeFeature("settings", true, 100)));

        // ユーザーが settings を先頭に設定している場合でも
        UserNavSettingsEntity entity = orderedSettings("[]", "[\"settings\",\"todo\",\"calendar\"]");
        given(userNavSettingsRepository.findById(1L)).willReturn(Optional.of(entity));

        NavSettingsResponse res = service.getMyNavSettings(1L);

        // settings は最後尾でなければならない
        List<String> keys = orderedKeys(res);
        assertThat(keys.get(keys.size() - 1)).isEqualTo("settings");
        // 他の項目も全て含まれること（欠落なし）
        assertThat(keys).contains("calendar", "todo", "settings");
        // settings の visible=true は変わらない（fixed 項目）
        assertThat(res.getFeatures().stream()
                .filter(f -> f.getKey().equals("settings")).findFirst().orElseThrow().isVisible()).isTrue();
    }

    @Test
    @DisplayName("settings最後尾固定: 個人並び順なし（マスタ順）でも settings が最後尾")
    void get_settingsAlwaysLast_whenNoUserOrder() {
        // マスタ sort_order: calendar=20, todo=30, settings=100（マスタ順でも最後尾）
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("calendar", true, 20),
                        makeFeature("todo", false, 30),
                        makeFeature("settings", true, 100)));
        given(userNavSettingsRepository.findById(1L)).willReturn(Optional.empty());

        NavSettingsResponse res = service.getMyNavSettings(1L);

        List<String> keys = orderedKeys(res);
        assertThat(keys.get(keys.size() - 1)).isEqualTo("settings");
    }

    @Test
    @DisplayName("settings最後尾固定: 個人並び順の中間に settings があっても最後尾に移動")
    void get_settingsAlwaysLast_whenSettingsInMiddleOfUserOrder() {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("a", false, 10),
                        makeFeature("settings", true, 100),
                        makeFeature("b", false, 20),
                        makeFeature("c", false, 30)));

        UserNavSettingsEntity entity = orderedSettings("[]", "[\"a\",\"settings\",\"b\",\"c\"]");
        given(userNavSettingsRepository.findById(1L)).willReturn(Optional.of(entity));

        NavSettingsResponse res = service.getMyNavSettings(1L);

        List<String> keys = orderedKeys(res);
        assertThat(keys.get(keys.size() - 1)).isEqualTo("settings");
        // 残りの順序は維持されること（settings 抜き）
        assertThat(keys.subList(0, keys.size() - 1)).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("settings最後尾固定: settings 項目が存在しない場合は通常の順序で返る")
    void get_settingsAlwaysLast_whenSettingsNotPresent() {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("a", false, 10),
                        makeFeature("b", false, 20),
                        makeFeature("c", false, 30)));
        given(userNavSettingsRepository.findById(1L)).willReturn(Optional.empty());

        NavSettingsResponse res = service.getMyNavSettings(1L);

        assertThat(orderedKeys(res)).containsExactly("a", "b", "c");
    }

    private UserNavSettingsEntity hiddenSettings(String json) {
        UserNavSettingsEntity e = new UserNavSettingsEntity();
        e.setUserId(1L);
        e.setHiddenNavKeys(json);
        return e;
    }

    private UserNavSettingsEntity orderedSettings(String hiddenJson, String orderJson) {
        UserNavSettingsEntity e = new UserNavSettingsEntity();
        e.setUserId(1L);
        e.setHiddenNavKeys(hiddenJson);
        e.setNavDisplayOrder(orderJson);
        return e;
    }
}
