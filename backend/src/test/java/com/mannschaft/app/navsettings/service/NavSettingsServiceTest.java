package com.mannschaft.app.navsettings.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.navsettings.entity.NavFeatureEntity;
import com.mannschaft.app.navsettings.entity.UserNavSettingsEntity;
import com.mannschaft.app.navsettings.repository.NavFeatureRepository;
import com.mannschaft.app.navsettings.repository.UserNavSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        NavFeatureEntity e = new NavFeatureEntity();
        e.setKey(key);
        e.setLabelKey("nav." + key);
        e.setIcon("pi pi-circle");
        e.setPath("/" + key);
        e.setFixed(fixed);
        e.setEnabled(true);
        e.setSortOrder(10);
        e.setMobileVisible(true);
        return e;
    }

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

        assertThat(result.getFeatures()).allMatch(f -> f.isVisible());
    }

    @Test
    @DisplayName("updateMyNavSettings: is_fixed=TRUEのキーでBusinessException")
    void updateMyNavSettings_fixedKey_throws() {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("calendar", true)));

        assertThatThrownBy(() -> service.updateMyNavSettings(1L, List.of("calendar")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("updateMyNavSettings: 正常ケースでupsertが呼ばれる")
    void updateMyNavSettings_normal_upsertCalled() throws Exception {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("todo", false)));

        service.updateMyNavSettings(1L, List.of("todo"));

        then(userNavSettingsRepository).should().upsertHiddenKeys(eq(1L), any());
    }

    @Test
    @DisplayName("updateMyNavSettings: 正常ケースで監査ログが記録される")
    void updateMyNavSettings_normal_auditRecorded() {
        given(navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(makeFeature("todo", false)));

        service.updateMyNavSettings(1L, List.of("todo"));

        // NAV_SETTINGS_UPDATED が操作者 userId 付きで記録されること
        then(auditLogService).should().record(
                eq(AuditEventType.NAV_SETTINGS_UPDATED.name()),
                eq(1L), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any());
    }

    private UserNavSettingsEntity hiddenSettings(String json) {
        UserNavSettingsEntity e = new UserNavSettingsEntity();
        e.setUserId(1L);
        e.setHiddenNavKeys(json);
        return e;
    }
}
