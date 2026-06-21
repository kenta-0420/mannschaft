package com.mannschaft.app.notification;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.notification.dto.NotificationSettingsResponse;
import com.mannschaft.app.notification.dto.NotificationSettingsUpdateRequest;
import com.mannschaft.app.notification.dto.PreferenceResponse;
import com.mannschaft.app.notification.dto.PreferenceUpdateRequest;
import com.mannschaft.app.notification.dto.TypePreferenceBulkUpdateRequest;
import com.mannschaft.app.notification.dto.TypePreferenceBulkUpdateResponse;
import com.mannschaft.app.notification.dto.TypePreferenceResponse;
import com.mannschaft.app.notification.entity.NotificationPreferenceEntity;
import com.mannschaft.app.notification.entity.NotificationSettingsEntity;
import com.mannschaft.app.notification.entity.NotificationTypePreferenceEntity;
import com.mannschaft.app.notification.repository.NotificationPreferenceRepository;
import com.mannschaft.app.notification.repository.NotificationSettingsRepository;
import com.mannschaft.app.notification.repository.NotificationTypePreferenceRepository;
import com.mannschaft.app.notification.service.NotificationPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link NotificationPreferenceService} 単体テスト（F04.3 ハイブリッド方式）。
 *
 * <p>受け入れ条件:
 * AC-1/2 カタログmerge（全種別・DAILY_DIGEST既定false）／AC-3 bulk URGENT無視／
 * AC-5 settings既定true・更新／AC-6 resolveChannels全分岐／AC-8 UPSERT冪等。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationPreferenceService 単体テスト")
class NotificationPreferenceServiceTest {

    @Mock
    private NotificationPreferenceRepository preferenceRepository;

    @Mock
    private NotificationTypePreferenceRepository typePreferenceRepository;

    @Mock
    private NotificationSettingsRepository settingsRepository;

    @Mock
    private NotificationMapper notificationMapper;

    @Mock
    private NameResolverService nameResolverService;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private NotificationPreferenceService preferenceService;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUpLabel() {
        // label は MessageSource で解決。デフォルトで「コード名」を返す簡易スタブ。
        given(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .willAnswer(inv -> inv.getArgument(2)); // defaultMessage（enum名）をそのまま返す
    }

    private NotificationTypePreferenceEntity typeRow(String type, boolean enabled,
                                                     boolean override, boolean inApp, boolean push) {
        return NotificationTypePreferenceEntity.builder()
                .userId(USER_ID)
                .notificationType(type)
                .isEnabled(enabled)
                .channelOverride(override)
                .inAppEnabled(inApp)
                .pushEnabled(push)
                .build();
    }

    // ========================================
    // AC-1 / AC-2: listTypePreferences カタログ merge
    // ========================================

    @Nested
    @DisplayName("listTypePreferences（カタログmerge）")
    class ListTypePreferences {

        @Test
        @DisplayName("AC-1: 行ゼロでも enum 全種別を返す（空配列回帰の防止）")
        void 行ゼロでも全種別返却() {
            given(typePreferenceRepository.findByUserId(USER_ID)).willReturn(List.of());

            List<TypePreferenceResponse> result = preferenceService.listTypePreferences(USER_ID);

            assertThat(result).hasSize(NotificationType.values().length);
            assertThat(result).extracting(TypePreferenceResponse::getNotificationType)
                    .contains("SCHEDULE_CREATED", "SAFETY_CHECK", "DAILY_DIGEST", "TODO_HANDED_OFF");
        }

        @Test
        @DisplayName("AC-2: DAILY_DIGEST は既定 false、他は既定 true")
        void DAILY_DIGEST既定false() {
            given(typePreferenceRepository.findByUserId(USER_ID)).willReturn(List.of());

            List<TypePreferenceResponse> result = preferenceService.listTypePreferences(USER_ID);

            TypePreferenceResponse digest = result.stream()
                    .filter(r -> r.getNotificationType().equals("DAILY_DIGEST")).findFirst().orElseThrow();
            TypePreferenceResponse schedule = result.stream()
                    .filter(r -> r.getNotificationType().equals("SCHEDULE_CREATED")).findFirst().orElseThrow();

            assertThat(digest.getIsEnabled()).isFalse();
            assertThat(schedule.getIsEnabled()).isTrue();
        }

        @Test
        @DisplayName("URGENT 種別は isLocked=true、その他は false")
        void URGENTはロック() {
            given(typePreferenceRepository.findByUserId(USER_ID)).willReturn(List.of());

            List<TypePreferenceResponse> result = preferenceService.listTypePreferences(USER_ID);

            TypePreferenceResponse safety = result.stream()
                    .filter(r -> r.getNotificationType().equals("SAFETY_CHECK")).findFirst().orElseThrow();
            TypePreferenceResponse schedule = result.stream()
                    .filter(r -> r.getNotificationType().equals("SCHEDULE_CREATED")).findFirst().orElseThrow();

            assertThat(safety.getIsLocked()).isTrue();
            assertThat(safety.getPriority()).isEqualTo("URGENT");
            assertThat(schedule.getIsLocked()).isFalse();
        }

        @Test
        @DisplayName("DB保存値（Dual 展開）が enum 既定にマージされて反映される")
        void DB保存値がマージされる() {
            given(typePreferenceRepository.findByUserId(USER_ID))
                    .willReturn(List.of(typeRow("BLOG_PUBLISHED", true, true, true, false)));

            List<TypePreferenceResponse> result = preferenceService.listTypePreferences(USER_ID);

            TypePreferenceResponse blog = result.stream()
                    .filter(r -> r.getNotificationType().equals("BLOG_PUBLISHED")).findFirst().orElseThrow();
            assertThat(blog.getChannelOverride()).isTrue();
            assertThat(blog.getInAppEnabled()).isTrue();
            assertThat(blog.getPushEnabled()).isFalse();
        }
    }

    // ========================================
    // AC-3: bulkUpdateTypePreferences
    // ========================================

    @Nested
    @DisplayName("bulkUpdateTypePreferences")
    class BulkUpdate {

        @Test
        @DisplayName("AC-3: URGENT 種別はスキップし ignoredLockedCount に計上")
        void URGENTはスキップ() {
            var entries = List.of(
                    new TypePreferenceBulkUpdateRequest.TypePreferenceEntry(
                            "ATTENDANCE_RESPONDED", false, false, null, null),
                    new TypePreferenceBulkUpdateRequest.TypePreferenceEntry(
                            "SAFETY_CHECK", false, false, null, null));
            var request = new TypePreferenceBulkUpdateRequest(entries);

            given(typePreferenceRepository.findByUserIdAndNotificationType(eq(USER_ID), anyString()))
                    .willReturn(Optional.empty());
            given(typePreferenceRepository.save(any(NotificationTypePreferenceEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            TypePreferenceBulkUpdateResponse response =
                    preferenceService.bulkUpdateTypePreferences(USER_ID, request);

            assertThat(response.getUpdatedCount()).isEqualTo(1);
            assertThat(response.getIgnoredLockedCount()).isEqualTo(1);
            // SAFETY_CHECK は save されない
            verify(typePreferenceRepository, never())
                    .findByUserIdAndNotificationType(USER_ID, "SAFETY_CHECK");
        }

        @Test
        @DisplayName("AC-8: 既存行があれば UPSERT（更新）し冪等")
        void 既存行はUPSERT更新() {
            var existing = typeRow("BLOG_PUBLISHED", true, false, true, true);
            var entry = new TypePreferenceBulkUpdateRequest.TypePreferenceEntry(
                    "BLOG_PUBLISHED", true, null, true, false);
            var request = new TypePreferenceBulkUpdateRequest(List.of(entry));

            given(typePreferenceRepository.findByUserIdAndNotificationType(USER_ID, "BLOG_PUBLISHED"))
                    .willReturn(Optional.of(existing));
            given(typePreferenceRepository.save(any(NotificationTypePreferenceEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            TypePreferenceBulkUpdateResponse response =
                    preferenceService.bulkUpdateTypePreferences(USER_ID, request);

            assertThat(response.getUpdatedCount()).isEqualTo(1);
            assertThat(existing.getChannelOverride()).isTrue();
            assertThat(existing.getPushEnabled()).isFalse();
            verify(typePreferenceRepository).save(existing);
        }

        @Test
        @DisplayName("AC-4: 未知の種別は BusinessException（→400）")
        void 未知種別は例外() {
            var entry = new TypePreferenceBulkUpdateRequest.TypePreferenceEntry(
                    "NOT_A_REAL_TYPE", false, false, null, null);
            var request = new TypePreferenceBulkUpdateRequest(List.of(entry));

            assertThatThrownBy(() -> preferenceService.bulkUpdateTypePreferences(USER_ID, request))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("AC-4: channelOverride=false で isEnabled 欠落は BusinessException")
        void 単一でisEnabled欠落は例外() {
            var entry = new TypePreferenceBulkUpdateRequest.TypePreferenceEntry(
                    "BLOG_PUBLISHED", false, null, null, null);
            var request = new TypePreferenceBulkUpdateRequest(List.of(entry));

            assertThatThrownBy(() -> preferenceService.bulkUpdateTypePreferences(USER_ID, request))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("AC-4: channelOverride=true で inApp/push 欠落は BusinessException")
        void Dualでチャネル欠落は例外() {
            var entry = new TypePreferenceBulkUpdateRequest.TypePreferenceEntry(
                    "BLOG_PUBLISHED", true, null, true, null);
            var request = new TypePreferenceBulkUpdateRequest(List.of(entry));

            assertThatThrownBy(() -> preferenceService.bulkUpdateTypePreferences(USER_ID, request))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // AC-5: settings get/update
    // ========================================

    @Nested
    @DisplayName("グローバル設定 getSettings / updateSettings")
    class Settings {

        @Test
        @DisplayName("AC-5: レコードなしは既定 priorityAutoDelivery=true（副作用なし）")
        void 設定なしは既定true() {
            given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

            NotificationSettingsResponse response = preferenceService.getSettings(USER_ID);

            assertThat(response.getPriorityAutoDelivery()).isTrue();
            verify(settingsRepository, never()).save(any());
        }

        @Test
        @DisplayName("AC-5: 保存済み値を返す")
        void 保存済み値を返す() {
            given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.of(
                    NotificationSettingsEntity.builder().userId(USER_ID).priorityAutoDelivery(false).build()));

            NotificationSettingsResponse response = preferenceService.getSettings(USER_ID);

            assertThat(response.getPriorityAutoDelivery()).isFalse();
        }

        @Test
        @DisplayName("AC-5: 既存なしは新規作成して更新")
        void 既存なしは新規作成() {
            given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(settingsRepository.save(any(NotificationSettingsEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            NotificationSettingsResponse response = preferenceService.updateSettings(
                    USER_ID, new NotificationSettingsUpdateRequest(false));

            assertThat(response.getPriorityAutoDelivery()).isFalse();
            verify(settingsRepository).save(any(NotificationSettingsEntity.class));
        }

        @Test
        @DisplayName("AC-5/AC-8: 既存ありは直接ミューテートで UPSERT 更新")
        void 既存ありはUPSERT更新() {
            var existing = NotificationSettingsEntity.builder()
                    .userId(USER_ID).priorityAutoDelivery(true).build();
            given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.of(existing));
            given(settingsRepository.save(existing)).willReturn(existing);

            NotificationSettingsResponse response = preferenceService.updateSettings(
                    USER_ID, new NotificationSettingsUpdateRequest(false));

            assertThat(response.getPriorityAutoDelivery()).isFalse();
            assertThat(existing.getPriorityAutoDelivery()).isFalse();
        }
    }

    // ========================================
    // AC-6: resolveChannels 全分岐
    // ========================================

    @Nested
    @DisplayName("resolveChannels（§5 配信判定）")
    class ResolveChannels {

        @Test
        @DisplayName("AC-6: URGENT は全設定無視で {IN_APP, PUSH} 強制")
        void URGENT強制全チャネル() {
            // 設定が一切なくても（findByなど呼ばれない想定）強制配信
            Set<String> channels = preferenceService.resolveChannels(
                    USER_ID, NotificationType.SAFETY_CHECK, NotificationPriority.URGENT);

            assertThat(channels).containsExactlyInAnyOrder("IN_APP", "PUSH");
        }

        @Test
        @DisplayName("AC-6: Dual 手動（inApp=true/push=false）はそのまま反映・自動配信無視")
        void Dual手動反映() {
            given(typePreferenceRepository.findByUserIdAndNotificationType(USER_ID, "BLOG_PUBLISHED"))
                    .willReturn(Optional.of(typeRow("BLOG_PUBLISHED", true, true, true, false)));

            Set<String> channels = preferenceService.resolveChannels(
                    USER_ID, NotificationType.BLOG_PUBLISHED, NotificationPriority.NORMAL);

            assertThat(channels).containsExactly("IN_APP");
            // 自動配信は参照されない
            verify(settingsRepository, never()).findByUserId(any());
        }

        @Test
        @DisplayName("AC-6: 単一・is_enabled=false は空集合")
        void 単一無効は空() {
            given(typePreferenceRepository.findByUserIdAndNotificationType(USER_ID, "BLOG_PUBLISHED"))
                    .willReturn(Optional.of(typeRow("BLOG_PUBLISHED", false, false, true, true)));

            Set<String> channels = preferenceService.resolveChannels(
                    USER_ID, NotificationType.BLOG_PUBLISHED, NotificationPriority.NORMAL);

            assertThat(channels).isEmpty();
        }

        @Test
        @DisplayName("AC-6: 単一・有効・自動ON・NORMAL は {IN_APP, PUSH}")
        void 単一有効自動ON_NORMAL() {
            given(typePreferenceRepository.findByUserIdAndNotificationType(USER_ID, "BLOG_PUBLISHED"))
                    .willReturn(Optional.empty()); // 行なし=既定 enabled
            given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.empty()); // 既定 autoDelivery=true

            Set<String> channels = preferenceService.resolveChannels(
                    USER_ID, NotificationType.BLOG_PUBLISHED, NotificationPriority.NORMAL);

            assertThat(channels).containsExactlyInAnyOrder("IN_APP", "PUSH");
        }

        @Test
        @DisplayName("AC-6: 単一・有効・自動ON・LOW は IN_APP のみ（priority < NORMAL）")
        void 単一有効自動ON_LOW() {
            given(typePreferenceRepository.findByUserIdAndNotificationType(USER_ID, "MEMBER_JOINED"))
                    .willReturn(Optional.empty());
            given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

            Set<String> channels = preferenceService.resolveChannels(
                    USER_ID, NotificationType.MEMBER_JOINED, NotificationPriority.LOW);

            assertThat(channels).containsExactly("IN_APP");
        }

        @Test
        @DisplayName("AC-6: 単一・有効・自動OFF は IN_APP のみ（NORMAL でも PUSH なし）")
        void 単一有効自動OFF() {
            given(typePreferenceRepository.findByUserIdAndNotificationType(USER_ID, "BLOG_PUBLISHED"))
                    .willReturn(Optional.empty());
            given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.of(
                    NotificationSettingsEntity.builder().userId(USER_ID).priorityAutoDelivery(false).build()));

            Set<String> channels = preferenceService.resolveChannels(
                    USER_ID, NotificationType.BLOG_PUBLISHED, NotificationPriority.NORMAL);

            assertThat(channels).containsExactly("IN_APP");
        }

        @Test
        @DisplayName("AC-2: DAILY_DIGEST は行なしだと既定 false ＝ 空集合")
        void DAILY_DIGEST既定OFFは空() {
            given(typePreferenceRepository.findByUserIdAndNotificationType(USER_ID, "DAILY_DIGEST"))
                    .willReturn(Optional.empty());

            Set<String> channels = preferenceService.resolveChannels(
                    USER_ID, NotificationType.DAILY_DIGEST, NotificationPriority.LOW);

            assertThat(channels).isEmpty();
        }
    }

    // ========================================
    // AC-7: listPreferences scopeName 充填
    // ========================================

    @Nested
    @DisplayName("listPreferences（scopeName 充填）")
    class ListPreferences {

        @Test
        @DisplayName("AC-7: scopeName が NameResolverService で充填される")
        void scopeName充填() {
            var entity = NotificationPreferenceEntity.builder()
                    .userId(USER_ID).scopeType("TEAM").scopeId(5L).isEnabled(true).build();
            var mapped = PreferenceResponse.builder()
                    .id(1L).userId(USER_ID)
                    .scope(new PreferenceResponse.PreferenceScopeDto("TEAM", 5L))
                    .isEnabled(true)
                    .build();

            given(preferenceRepository.findByUserId(USER_ID)).willReturn(List.of(entity));
            given(notificationMapper.toPreferenceResponse(entity)).willReturn(mapped);
            given(nameResolverService.resolveScopeName("TEAM", 5L)).willReturn("FCバルセロナ");

            List<PreferenceResponse> result = preferenceService.listPreferences(USER_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getScopeName()).isEqualTo("FCバルセロナ");
        }
    }

    // ========================================
    // 後方互換 isNotificationEnabled / isTypeEnabled
    // ========================================

    @Nested
    @DisplayName("後方互換 isTypeEnabled")
    class IsTypeEnabled {

        @Test
        @DisplayName("設定なしは enum 既定（SCHEDULE_CREATED=true）")
        void 設定なしは既定true() {
            given(typePreferenceRepository.findByUserIdAndNotificationType(USER_ID, "SCHEDULE_CREATED"))
                    .willReturn(Optional.empty());

            assertThat(preferenceService.isTypeEnabled(USER_ID, "SCHEDULE_CREATED")).isTrue();
        }

        @Test
        @DisplayName("DAILY_DIGEST は設定なしだと既定 false")
        void DAILY_DIGESTは既定false() {
            given(typePreferenceRepository.findByUserIdAndNotificationType(USER_ID, "DAILY_DIGEST"))
                    .willReturn(Optional.empty());

            assertThat(preferenceService.isTypeEnabled(USER_ID, "DAILY_DIGEST")).isFalse();
        }
    }
}
