package com.mannschaft.app.notification;

import com.mannschaft.app.committee.service.CommitteeAccessGuard;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.notification.dto.PreferenceResponse;
import com.mannschaft.app.notification.entity.NotificationPreferenceEntity;
import com.mannschaft.app.notification.repository.NotificationPreferenceRepository;
import com.mannschaft.app.notification.repository.NotificationSettingsRepository;
import com.mannschaft.app.notification.repository.NotificationTypePreferenceRepository;
import com.mannschaft.app.notification.service.NotificationPreferenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** F04.3: 未保存の所属スコープは既定ONで返す。保存行の意味と読み取り専用性を維持する。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationPreferenceDefaultsTest {
    private static final long ME = 936401L;
    @Mock private NotificationPreferenceRepository preferenceRepository;
    @Mock private NotificationTypePreferenceRepository typePreferenceRepository;
    @Mock private NotificationSettingsRepository settingsRepository;
    @Mock private NotificationMapper notificationMapper;
    @Mock private NameResolverService nameResolverService;
    @Mock private MessageSource messageSource;
    @Mock private AccessControlService accessControlService;
    @Mock private CommitteeAccessGuard committeeAccessGuard;
    @InjectMocks private NotificationPreferenceService service;

    @Test
    @DisplayName("未保存でも所属TEAMと直接ORGANIZATIONを既定ONで返し、DBに書き込まない")
    void unsavedAffiliationsAreEnabledWithoutWrites() {
        affiliations();
        given(preferenceRepository.findByUserId(ME)).willReturn(List.of());

        List<PreferenceResponse> result = service.listPreferences(ME);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(r -> r.getScope().scopeType()).containsExactlyInAnyOrder("TEAM", "ORGANIZATION");
        assertThat(result).allSatisfy(r -> {
            assertThat(r.getUserId()).isEqualTo(ME);
            assertThat(r.getIsEnabled()).isTrue();
            assertThat(r.getId()).isNull();
            assertThat(r.getAudit()).isNull();
        });
        assertThat(result).extracting(PreferenceResponse::getScopeName).containsExactlyInAnyOrder("所属チーム", "直接所属組織");
        verify(preferenceRepository, never()).save(any());
        verify(preferenceRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("保存OFFを保持し同一type/idは重複せず、未保存組織のみ補完する")
    void savedOffOverridesDefault() {
        affiliations();
        NotificationPreferenceEntity saved = row("TEAM", 11L, false);
        given(preferenceRepository.findByUserId(ME)).willReturn(List.of(saved));
        mapper();

        List<PreferenceResponse> result = service.listPreferences(ME);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getScope().scopeType()).isEqualTo("TEAM");
        assertThat(result.get(0).getIsEnabled()).isFalse();
        assertThat(result.get(1).getScope().scopeType()).isEqualTo("ORGANIZATION");
        assertThat(result.get(1).getIsEnabled()).isTrue();
    }

    @Test
    @DisplayName("legacy FRIEND_TEAM行はTEAMと別キーで保存値を保持する")
    void legacyFriendTeamDoesNotSuppressTeamDefault() {
        affiliations();
        given(preferenceRepository.findByUserId(ME)).willReturn(List.of(row("FRIEND_TEAM", 11L, false)));
        mapper();

        List<PreferenceResponse> result = service.listPreferences(ME);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getScope().scopeType()).isEqualTo("FRIEND_TEAM");
        assertThat(result.get(0).getIsEnabled()).isFalse();
        assertThat(result.stream().filter(r -> "TEAM".equals(r.getScope().scopeType())).toList())
                .singleElement().satisfies(r -> assertThat(r.getIsEnabled()).isTrue());
    }

    @Test
    @DisplayName("SYSTEM_ADMINにも実所属だけを補完し、所属なしは空配列")
    void systemAdminWithoutAffiliationsHasNoDefaults() {
        given(accessControlService.isSystemAdmin(ME)).willReturn(true);
        given(accessControlService.findAffiliatedScopeIds(ME, "TEAM")).willReturn(Set.of());
        given(accessControlService.findAffiliatedScopeIds(ME, "ORGANIZATION")).willReturn(Set.of());
        given(preferenceRepository.findByUserId(ME)).willReturn(List.of());
        assertThat(service.listPreferences(ME)).isEmpty();
        verify(preferenceRepository, never()).save(any());
    }

    private void affiliations() {
        given(accessControlService.findAffiliatedScopeIds(ME, "TEAM")).willReturn(Set.of(11L));
        given(accessControlService.findAffiliatedScopeIds(ME, "ORGANIZATION")).willReturn(Set.of(21L));
        given(nameResolverService.resolveTeamNames(any())).willReturn(Map.of(11L, "所属チーム"));
        given(nameResolverService.resolveOrganizationNames(any())).willReturn(Map.of(21L, "直接所属組織"));
    }

    private NotificationPreferenceEntity row(String type, Long id, boolean enabled) {
        return NotificationPreferenceEntity.builder().userId(ME).scopeType(type).scopeId(id).isEnabled(enabled).build();
    }

    private void mapper() {
        given(notificationMapper.toPreferenceResponse(any())).willAnswer(inv -> {
            NotificationPreferenceEntity e = inv.getArgument(0);
            return PreferenceResponse.builder().id(e.getId()).userId(e.getUserId())
                    .scope(new PreferenceResponse.PreferenceScopeDto(e.getScopeType(), e.getScopeId()))
                    .isEnabled(e.getIsEnabled()).build();
        });
    }
}
