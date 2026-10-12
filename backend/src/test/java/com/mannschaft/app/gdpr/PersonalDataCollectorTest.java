package com.mannschaft.app.gdpr;

import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagLinkRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagRepository;
import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.errorreport.repository.ErrorReportOccurrenceRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import com.mannschaft.app.gdpr.service.PersonalDataCollector;
import com.mannschaft.app.inbox.entity.InboxItemStateEntity;
import com.mannschaft.app.inbox.entity.InboxLabelLinkEntity;
import com.mannschaft.app.inbox.entity.NotificationLabelEntity;
import com.mannschaft.app.inbox.repository.InboxItemStateRepository;
import com.mannschaft.app.inbox.repository.InboxLabelLinkRepository;
import com.mannschaft.app.inbox.repository.NotificationLabelRepository;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.pointcard.entity.PointCardGroupEntity;
import com.mannschaft.app.pointcard.entity.PointCardGroupItemEntity;
import com.mannschaft.app.pointcard.entity.PointCardUserSettingsEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.enums.BarcodeFormat;
import com.mannschaft.app.pointcard.repository.PointCardGroupItemRepository;
import com.mannschaft.app.pointcard.repository.PointCardGroupRepository;
import com.mannschaft.app.pointcard.repository.PointCardUserSettingsRepository;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.resume.repository.ResumeCareerRepository;
import com.mannschaft.app.resume.repository.ResumeEducationRepository;
import com.mannschaft.app.resume.repository.ResumeQualificationRepository;
import com.mannschaft.app.resume.repository.ResumeRepository;
import com.mannschaft.app.resume.repository.ResumeSkillRepository;
import com.mannschaft.app.schedule.service.ScheduleCommentService;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.weather.repository.UserWeatherLocationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalDataCollector 単体テスト")
class PersonalDataCollectorTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private OAuthAccountRepository oAuthAccountRepository;
    @Mock
    private MemberProfileRepository memberProfileRepository;
    @Mock
    private MemberPaymentRepository memberPaymentRepository;
    @Mock
    private ChartRecordRepository chartRecordRepository;
    @Mock
    private TimelinePostRepository timelinePostRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private ActionMemoRepository actionMemoRepository;
    @Mock
    private ActionMemoTagRepository actionMemoTagRepository;
    @Mock
    private ActionMemoTagLinkRepository actionMemoTagLinkRepository;
    @Mock
    private UserActionMemoSettingsRepository userActionMemoSettingsRepository;
    @Mock
    private ErrorReportRepository errorReportRepository;
    @Mock
    private ErrorReportOccurrenceRepository errorReportOccurrenceRepository;
    @Mock
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;
    @Mock
    private UserWeatherLocationRepository userWeatherLocationRepository;
    @Mock
    private EncryptionService encryptionService;
    // F01.10 職務経歴書（履歴書）
    @Mock
    private ResumeRepository resumeRepository;
    @Mock
    private ResumeEducationRepository resumeEducationRepository;
    @Mock
    private ResumeCareerRepository resumeCareerRepository;
    @Mock
    private ResumeQualificationRepository resumeQualificationRepository;
    @Mock
    private ResumeSkillRepository resumeSkillRepository;
    // F18 個人ポイントカードウォレット S3
    @Mock
    private UserPointCardRepository userPointCardRepository;
    @Mock
    private PointCardUserSettingsRepository pointCardUserSettingsRepository;
    @Mock
    private PointCardGroupRepository pointCardGroupRepository;
    @Mock
    private PointCardGroupItemRepository pointCardGroupItemRepository;
    // F04.11 統合通知インボックス（per-user オーバーレイ3表）
    @Mock
    private InboxItemStateRepository inboxItemStateRepository;
    @Mock
    private NotificationLabelRepository notificationLabelRepository;
    @Mock
    private InboxLabelLinkRepository inboxLabelLinkRepository;
    // F03.16 予定コメントスレッド（AC-35）
    @Mock
    private ScheduleCommentService scheduleCommentService;

    @InjectMocks
    private PersonalDataCollector collector;

    @Nested
    @DisplayName("collect")
    class Collect {

        @Test
        @DisplayName("正常系: nullカテゴリで全カテゴリが収集される（19カテゴリ）")
        void 正常_nullカテゴリ_全カテゴリ収集() {
            given(userRepository.findById(anyLong())).willReturn(Optional.empty());
            given(oAuthAccountRepository.findByUserId(anyLong())).willReturn(List.of());
            given(memberProfileRepository.findByUserIdOrderByCreatedAtDesc(anyLong())).willReturn(List.of());
            given(memberPaymentRepository.findByUserId(anyLong())).willReturn(List.of());
            given(chartRecordRepository.findByCustomerUserIdAndIsSharedToCustomerTrueOrderByVisitDateDesc(
                    anyLong(), any())).willReturn(org.springframework.data.domain.Page.empty());
            given(timelinePostRepository.findByUserIdOrderByCreatedAtDesc(anyLong(), any()))
                    .willReturn(List.of());
            given(notificationRepository.findByUserIdOrderByCreatedAtDesc(anyLong(), any()))
                    .willReturn(org.springframework.data.domain.Page.empty());
            given(actionMemoRepository.findByUserIdOrderByMemoDateDescCreatedAtDesc(anyLong()))
                    .willReturn(List.of());
            given(actionMemoTagRepository.findByUserIdOrderBySortOrderAsc(anyLong()))
                    .willReturn(List.of());
            given(actionMemoTagLinkRepository.findByUserId(anyLong()))
                    .willReturn(List.of());
            given(userActionMemoSettingsRepository.findById(anyLong()))
                    .willReturn(Optional.empty());
            given(errorReportRepository.findByUserIdOrderByCreatedAtDesc(anyLong()))
                    .willReturn(List.of());
            given(errorReportOccurrenceRepository.findByUserIdOrderByOccurredAtDesc(anyLong()))
                    .willReturn(List.of());
            given(proxyInputConsentRepository.findAllBySubjectUserIdForExport(anyLong()))
                    .willReturn(List.of());
            given(proxyInputRecordRepository.findBySubjectUserId(anyLong()))
                    .willReturn(List.of());
            given(userWeatherLocationRepository.findByUserId(anyLong()))
                    .willReturn(List.of());
            // F01.10 職務経歴書（履歴書）
            given(resumeRepository.findByUserIdOrderByCreatedAtDesc(anyLong()))
                    .willReturn(List.of());
            // F18 個人ポイントカードウォレット S3
            given(pointCardUserSettingsRepository.findById(anyLong()))
                    .willReturn(Optional.empty());
            given(userPointCardRepository.findByUserId(anyLong()))
                    .willReturn(List.of());
            given(pointCardGroupRepository.findAllByUserIdOrderByDisplayOrderAscCreatedAtAsc(anyLong()))
                    .willReturn(List.of());
            // F04.11 統合通知インボックス
            given(inboxItemStateRepository.findByUserId(anyLong())).willReturn(List.of());
            given(notificationLabelRepository.findByUserId(anyLong())).willReturn(List.of());
            given(inboxLabelLinkRepository.findByUserId(anyLong())).willReturn(List.of());
            // F03.16 予定コメントスレッド（AC-35）
            given(scheduleCommentService.collectPersonalDataForGdpr(anyLong())).willReturn(List.of());

            Map<String, String> result = collector.collect(1L, null);

            assertThat(result).hasSize(19);
            assertThat(result.keySet()).containsExactlyInAnyOrder(
                    "account.json", "oauth_accounts.json", "memberships.json", "profiles.json",
                    "payments.json", "charts.json", "chat_messages.json", "timeline_posts.json",
                    "audit_logs.json", "notifications.json", "action_memos.json",
                    "error_reports.json", "proxy_input_consents.json", "proxy_input_records.json",
                    "weather_locations.json", "point_cards.json", "resumes.json", "inbox.json",
                    "scheduleComments"
            );
            assertThat(result).as("F03.16 予定コメントスレッド（AC-35）が全カテゴリ収集に含まれること")
                    .containsKey("scheduleComments");
        }

        @Test
        @DisplayName("正常系: [account, payments]指定で2ファイルのみ返る")
        void 正常_部分カテゴリ_2件返却() {
            given(userRepository.findById(anyLong())).willReturn(Optional.empty());
            given(memberPaymentRepository.findByUserId(anyLong())).willReturn(List.of());

            Map<String, String> result = collector.collect(1L, Set.of("account", "payments"));

            assertThat(result).hasSize(2);
            assertThat(result.keySet()).containsExactlyInAnyOrder("account.json", "payments.json");
        }

        @Test
        @DisplayName("異常系: リポジトリ例外発生時は[]でスキップされる")
        void 異常_リポジトリ例外_スキップ() {
            given(userRepository.findById(anyLong())).willThrow(new RuntimeException("DB error"));

            Map<String, String> result = collector.collect(1L, Set.of("account"));

            assertThat(result).hasSize(1);
            assertThat(result.get("account.json")).isEqualTo("[]");
        }
    }

    @Nested
    @DisplayName("getCategoryKeys")
    class GetCategoryKeys {

        @Test
        @DisplayName("正常系: 19カテゴリキーが返る")
        void 正常_19カテゴリキー返却() {
            Set<String> keys = collector.getCategoryKeys();

            assertThat(keys).hasSize(19);
            assertThat(keys).containsExactlyInAnyOrder(
                    "account", "oauth", "memberships", "profiles", "payments",
                    "charts", "chat_messages", "timeline", "audit_logs", "notifications",
                    "action_memos", "error_reports", "proxy_consents", "proxy_records",
                    "location_preference", "point_cards", "resumes", "inbox", "scheduleComments"
            );
            assertThat(keys).as("F03.16 予定コメントスレッド（AC-35）のカテゴリキーが含まれること")
                    .contains("scheduleComments");
        }
    }

    @Nested
    @DisplayName("point_cards カテゴリ（F18 S3 本実装）")
    class PointCardsCategory {

        @Test
        @DisplayName("空データ: settings=null / cards=[] / groups=[] を含む 1 ファイルが返る")
        void 空_3構造のキーが含まれる() {
            given(pointCardUserSettingsRepository.findById(anyLong()))
                    .willReturn(Optional.empty());
            given(userPointCardRepository.findByUserId(anyLong()))
                    .willReturn(List.of());
            given(pointCardGroupRepository.findAllByUserIdOrderByDisplayOrderAscCreatedAtAsc(anyLong()))
                    .willReturn(List.of());

            Map<String, String> result = collector.collect(1L, Set.of("point_cards"));

            assertThat(result).hasSize(1);
            assertThat(result).containsKey("point_cards.json");
            String json = result.get("point_cards.json");
            assertThat(json).contains("\"settings\":null");
            assertThat(json).contains("\"cards\":[]");
            assertThat(json).contains("\"groups\":[]");
        }

        @Test
        @DisplayName("正常系: cards と groups の中身が JSON に平文で含まれる（EncryptedStringConverter 復号済み）")
        void 正常系_復号後の平文がエクスポートされる() {
            PointCardUserSettingsEntity settings = PointCardUserSettingsEntity.builder()
                    .userId(1L)
                    .enabled(Boolean.TRUE)
                    .termsVersion("v1.0.0")
                    .requireBiometricOnShow(Boolean.FALSE)
                    .build();
            given(pointCardUserSettingsRepository.findById(anyLong()))
                    .willReturn(Optional.of(settings));

            UserPointCardEntity card = UserPointCardEntity.builder()
                    .userId(1L)
                    .providerId(null) // 自由入力カード
                    .displayName("近所のスーパー")  // EncryptedStringConverter が読み込み時に復号した想定
                    .barcodeValue("9876543210987")
                    .barcodeFormat(BarcodeFormat.CODE128)
                    .last4("0987")
                    .favorite(false)
                    .displayOrder(0)
                    .build();
            card.setId(UUID.randomUUID());
            given(userPointCardRepository.findByUserId(anyLong())).willReturn(List.of(card));

            PointCardGroupEntity group = PointCardGroupEntity.builder()
                    .userId(1L)
                    .name("お買い物セット")
                    .emoji("🛒")
                    .displayOrder(0)
                    .build();
            group.setId(UUID.randomUUID());
            given(pointCardGroupRepository.findAllByUserIdOrderByDisplayOrderAscCreatedAtAsc(anyLong()))
                    .willReturn(List.of(group));

            PointCardGroupItemEntity item = PointCardGroupItemEntity.builder()
                    .groupId(group.getId())
                    .cardId(card.getId())
                    .displayOrder(0)
                    .build();
            given(pointCardGroupItemRepository.findAllByGroupIdIn(any()))
                    .willReturn(List.of(item));

            Map<String, String> result = collector.collect(1L, Set.of("point_cards"));

            assertThat(result).hasSize(1);
            String json = result.get("point_cards.json");
            // settings は復号後の平文が含まれる
            assertThat(json).contains("\"termsVersion\":\"v1.0.0\"");
            // card は復号後の barcodeValue / displayName を含む（GDPR §15 アクセス権実現）
            assertThat(json).contains("\"barcodeValue\":\"9876543210987\"");
            assertThat(json).contains("\"displayName\":\"近所のスーパー\"");
            // groups は中間アイテム配列を含む
            assertThat(json).contains("\"name\":\"お買い物セット\"");
            assertThat(json).contains("\"items\":");
            assertThat(json).contains(card.getId().toString());
        }

        @Test
        @DisplayName("異常系: リポジトリ例外は[]でスキップされる")
        void 異常系_例外発生_スキップ() {
            given(pointCardUserSettingsRepository.findById(anyLong()))
                    .willThrow(new RuntimeException("DB error"));

            Map<String, String> result = collector.collect(1L, Set.of("point_cards"));

            assertThat(result).hasSize(1);
            assertThat(result.get("point_cards.json")).isEqualTo("[]");
        }
    }

    @Nested
    @DisplayName("action_memos カテゴリ（F02.5 Phase 1.5）")
    class ActionMemosCategory {

        @Test
        @DisplayName("正常系: action_memos 指定で4テーブルの内容が1ファイルにまとめて返る")
        void 正常_action_memos_4テーブル収集() {
            given(actionMemoRepository.findByUserIdOrderByMemoDateDescCreatedAtDesc(anyLong()))
                    .willReturn(List.of());
            given(actionMemoTagRepository.findByUserIdOrderBySortOrderAsc(anyLong()))
                    .willReturn(List.of());
            given(actionMemoTagLinkRepository.findByUserId(anyLong()))
                    .willReturn(List.of());
            given(userActionMemoSettingsRepository.findById(anyLong()))
                    .willReturn(Optional.empty());

            Map<String, String> result = collector.collect(1L, Set.of("action_memos"));

            assertThat(result).hasSize(1);
            assertThat(result).containsKey("action_memos.json");
            String json = result.get("action_memos.json");
            assertThat(json).contains("action_memos");
            assertThat(json).contains("action_memo_tags");
            assertThat(json).contains("action_memo_tag_links");
            assertThat(json).contains("user_action_memo_settings");
        }

        @Test
        @DisplayName("異常系: リポジトリ例外は[]でスキップされる")
        void 異常_action_memos_例外_スキップ() {
            given(actionMemoRepository.findByUserIdOrderByMemoDateDescCreatedAtDesc(anyLong()))
                    .willThrow(new RuntimeException("DB error"));

            Map<String, String> result = collector.collect(1L, Set.of("action_memos"));

            assertThat(result).hasSize(1);
            assertThat(result.get("action_memos.json")).isEqualTo("[]");
        }
    }

    @Nested
    @DisplayName("error_reports カテゴリ（F12.5）")
    class ErrorReportsCategory {

        @Test
        @DisplayName("正常系: error_reports 指定でエラーレポートと発生履歴が収集される")
        void 正常_error_reports_収集() {
            given(errorReportRepository.findByUserIdOrderByCreatedAtDesc(anyLong()))
                    .willReturn(List.of());
            given(errorReportOccurrenceRepository.findByUserIdOrderByOccurredAtDesc(anyLong()))
                    .willReturn(List.of());

            Map<String, String> result = collector.collect(1L, Set.of("error_reports"));

            assertThat(result).hasSize(1);
            assertThat(result).containsKey("error_reports.json");
            // F12.5 Phase 2 — error_reports と error_report_occurrences を 1 ファイルに集約する仕様
            assertThat(result.get("error_reports.json"))
                    .contains("\"error_reports\":[]")
                    .contains("\"error_report_occurrences\":[]");
        }

        @Test
        @DisplayName("異常系: リポジトリ例外は[]でスキップされる")
        void 異常_error_reports_例外_スキップ() {
            given(errorReportRepository.findByUserIdOrderByCreatedAtDesc(anyLong()))
                    .willThrow(new RuntimeException("DB error"));

            Map<String, String> result = collector.collect(1L, Set.of("error_reports"));

            assertThat(result).hasSize(1);
            assertThat(result.get("error_reports.json")).isEqualTo("[]");
        }
    }

    @Nested
    @DisplayName("inbox カテゴリ（F04.11 統合通知インボックス 3表フルダンプ）")
    class InboxCategory {

        @Test
        @DisplayName("空データ: 3表のキーを持つ空配列の 1 ファイルが返る")
        void 空_3表のキーが含まれる() {
            given(inboxItemStateRepository.findByUserId(anyLong())).willReturn(List.of());
            given(notificationLabelRepository.findByUserId(anyLong())).willReturn(List.of());
            given(inboxLabelLinkRepository.findByUserId(anyLong())).willReturn(List.of());

            Map<String, String> result = collector.collect(1L, Set.of("inbox"));

            assertThat(result).hasSize(1);
            assertThat(result).containsKey("inbox.json");
            String json = result.get("inbox.json");
            assertThat(json)
                    .contains("\"inbox_item_states\":[]")
                    .contains("\"notification_labels\":[]")
                    .contains("\"inbox_label_links\":[]");
        }

        @Test
        @DisplayName("正常系: 3表それぞれの内容が 1 ファイルにまとめて返る")
        void 正常_3表の中身が1ファイルに集約される() {
            InboxItemStateEntity state = new InboxItemStateEntity();
            state.setId(UUID.randomUUID());
            state.setUserId(1L);
            state.setSourceType(com.mannschaft.app.inbox.InboxSourceType.NOTIFICATION);
            state.setSourceId(101L);
            state.setSnoozedUntil(java.time.LocalDateTime.of(2026, 6, 10, 9, 0));
            state.setArchivedAt(java.time.LocalDateTime.of(2026, 6, 5, 12, 0));
            given(inboxItemStateRepository.findByUserId(anyLong())).willReturn(List.of(state));

            NotificationLabelEntity label = new NotificationLabelEntity();
            label.setId(UUID.randomUUID());
            label.setUserId(1L);
            label.setName("重要");
            label.setColor("#FF0000");
            label.setIcon("pi-star");
            label.setSortOrder(1);
            given(notificationLabelRepository.findByUserId(anyLong())).willReturn(List.of(label));

            InboxLabelLinkEntity link = new InboxLabelLinkEntity();
            link.setId(UUID.randomUUID());
            link.setLabelId(label.getId());
            link.setUserId(1L);
            link.setSourceType(com.mannschaft.app.inbox.InboxSourceType.NOTIFICATION);
            link.setSourceId(101L);
            given(inboxLabelLinkRepository.findByUserId(anyLong())).willReturn(List.of(link));

            Map<String, String> result = collector.collect(1L, Set.of("inbox"));

            assertThat(result).hasSize(1);
            String json = result.get("inbox.json");
            // inbox_item_states の生データが含まれる
            assertThat(json).contains("\"sourceType\":\"NOTIFICATION\"");
            assertThat(json).contains("\"sourceId\":101");
            // notification_labels の生データが含まれる
            assertThat(json).contains("\"name\":\"重要\"");
            assertThat(json).contains("\"color\":\"#FF0000\"");
            // inbox_label_links の生データが含まれる
            assertThat(json).contains(label.getId().toString());
        }

        @Test
        @DisplayName("異常系: リポジトリ例外は[]でスキップされる")
        void 異常_inbox_例外_スキップ() {
            given(inboxItemStateRepository.findByUserId(anyLong()))
                    .willThrow(new RuntimeException("DB error"));

            Map<String, String> result = collector.collect(1L, Set.of("inbox"));

            assertThat(result).hasSize(1);
            assertThat(result.get("inbox.json")).isEqualTo("[]");
        }
    }
}
