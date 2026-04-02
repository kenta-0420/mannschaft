package com.mannschaft.app.gdpr;

import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.gdpr.service.PersonalDataCollector;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
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
    private EncryptionService encryptionService;

    @InjectMocks
    private PersonalDataCollector collector;

    @Nested
    @DisplayName("collect")
    class Collect {

        @Test
        @DisplayName("正常系: nullカテゴリで全カテゴリが収集される（10カテゴリ）")
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

            Map<String, String> result = collector.collect(1L, null);

            assertThat(result).hasSize(10);
            assertThat(result.keySet()).containsExactlyInAnyOrder(
                    "account.json", "oauth_accounts.json", "memberships.json", "profiles.json",
                    "payments.json", "charts.json", "chat_messages.json", "timeline_posts.json",
                    "audit_logs.json", "notifications.json"
            );
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
        @DisplayName("正常系: 10カテゴリキーが返る")
        void 正常_10カテゴリキー返却() {
            Set<String> keys = collector.getCategoryKeys();

            assertThat(keys).hasSize(10);
            assertThat(keys).containsExactlyInAnyOrder(
                    "account", "oauth", "memberships", "profiles", "payments",
                    "charts", "chat_messages", "timeline", "audit_logs", "notifications"
            );
        }
    }
}
