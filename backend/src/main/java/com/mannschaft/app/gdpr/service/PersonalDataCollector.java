package com.mannschaft.app.gdpr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mannschaft.app.auth.entity.OAuthAccountEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 個人データ収集オーケストレータ。
 * カテゴリ別にリポジトリからデータを収集し、JSON文字列として返す。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalDataCollector {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oAuthAccountRepository;
    private final MemberProfileRepository memberProfileRepository;
    private final MemberPaymentRepository memberPaymentRepository;
    private final ChartRecordRepository chartRecordRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final AuditLogRepository auditLogRepository;
    private final NotificationRepository notificationRepository;
    private final EncryptionService encryptionService;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /** カテゴリキー → JSONファイル名のマッピング（@PersonalData のカテゴリ値と一致させること） */
    private static final Map<String, String> CATEGORY_FILES = Map.ofEntries(
            Map.entry("account", "account.json"),
            Map.entry("oauthAccounts", "oauth_accounts.json"),
            Map.entry("memberships", "memberships.json"),
            Map.entry("profiles", "profiles.json"),
            Map.entry("payments", "payments.json"),
            Map.entry("charts", "charts.json"),
            Map.entry("chatMessages", "chat_messages.json"),
            Map.entry("timeline", "timeline_posts.json"),
            Map.entry("auditLogs", "audit_logs.json"),
            Map.entry("notifications", "notifications.json"),
            Map.entry("error_reports", "error_reports.json")
    );

    /**
     * PersonalDataCoverageValidator から参照するカテゴリキーセット取得。
     */
    public Set<String> getCategoryKeys() {
        return CATEGORY_FILES.keySet();
    }

    /**
     * 指定ユーザーの個人データをカテゴリ別に収集する。
     *
     * @param userId     対象ユーザーID
     * @param categories 収集対象カテゴリ（nullまたは空=全カテゴリ）
     * @return ファイル名 → JSON文字列のマップ
     */
    public Map<String, String> collect(Long userId, Set<String> categories) {
        Set<String> targets = (categories == null || categories.isEmpty())
                ? CATEGORY_FILES.keySet()
                : categories;

        Map<String, String> data = new LinkedHashMap<>();
        for (String category : targets) {
            String fileName = CATEGORY_FILES.get(category);
            if (fileName == null) continue;
            try {
                String json = collectByCategory(userId, category);
                data.put(fileName, json);
            } catch (Exception e) {
                log.warn("カテゴリ収集失敗: category={}, userId={}", category, userId, e);
                data.put(fileName, "[]");
            }
        }
        return data;
    }

    private String collectByCategory(Long userId, String category) throws Exception {
        return switch (category) {
            case "account" -> collectAccount(userId);
            case "oauthAccounts" -> collectOAuth(userId);
            case "memberships" -> collectMemberships(userId);
            case "profiles" -> collectProfiles(userId);
            case "payments" -> collectPayments(userId);
            case "charts" -> collectCharts(userId);
            case "chatMessages" -> collectChatMessages(userId);
            case "timeline" -> collectTimeline(userId);
            case "auditLogs" -> collectAuditLogs(userId);
            case "notifications" -> collectNotifications(userId);
            case "error_reports" -> "[]";
            default -> "[]";
        };
    }

    private String collectAccount(Long userId) throws Exception {
        return userRepository.findById(userId).map(user -> {
            try {
                Map<String, Object> accountData = new LinkedHashMap<>();
                accountData.put("id", user.getId());
                accountData.put("email", user.getEmail());
                accountData.put("displayName", user.getDisplayName());
                accountData.put("nickname2", user.getNickname2());
                accountData.put("lastName", decryptSafe(user.getLastName()));
                accountData.put("firstName", decryptSafe(user.getFirstName()));
                accountData.put("lastNameKana", decryptSafe(user.getLastNameKana()));
                accountData.put("firstNameKana", decryptSafe(user.getFirstNameKana()));
                accountData.put("phoneNumber", decryptSafe(user.getPhoneNumber()));
                accountData.put("postalCode", decryptSafe(user.getPostalCode()));
                accountData.put("locale", user.getLocale());
                accountData.put("timezone", user.getTimezone());
                accountData.put("status", user.getStatus());
                accountData.put("isSearchable", user.getIsSearchable());
                accountData.put("avatarUrl", user.getAvatarUrl());
                accountData.put("lastLoginAt", user.getLastLoginAt());
                accountData.put("createdAt", user.getCreatedAt());
                accountData.put("updatedAt", user.getUpdatedAt());
                return OBJECT_MAPPER.writeValueAsString(accountData);
            } catch (Exception e) {
                log.warn("アカウントデータのJSON変換失敗: userId={}", userId, e);
                return "{}";
            }
        }).orElse("{}");
    }

    private String collectOAuth(Long userId) throws Exception {
        List<OAuthAccountEntity> oAuthAccounts = oAuthAccountRepository.findByUserId(userId);
        return OBJECT_MAPPER.writeValueAsString(oAuthAccounts.stream().map(oa -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", oa.getId());
            entry.put("provider", oa.getProvider());
            entry.put("providerUserId", oa.getProviderUserId());
            entry.put("providerEmail", oa.getProviderEmail());
            entry.put("createdAt", oa.getCreatedAt());
            return entry;
        }).toList());
    }

    private String collectMemberships(Long userId) throws Exception {
        // team_org_membershipsはTeamOrgMembershipEntityであり、userId検索メソッドが存在しない
        log.warn("リポジトリ未実装: category=memberships (userId単独検索メソッドなし)");
        return "[]";
    }

    private String collectProfiles(Long userId) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(
                memberProfileRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    private String collectPayments(Long userId) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(
                memberPaymentRepository.findByUserId(userId));
    }

    private String collectCharts(Long userId) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(
                chartRecordRepository.findByCustomerUserIdAndIsSharedToCustomerTrueOrderByVisitDateDesc(
                        userId, Pageable.unpaged()).getContent());
    }

    private String collectChatMessages(Long userId) throws Exception {
        // ChatMessageRepositoryはchannelId検索のみ対応、userId検索メソッドが存在しない
        log.warn("リポジトリ未実装: category=chat_messages (userId検索メソッドなし)");
        return "[]";
    }

    private String collectTimeline(Long userId) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(
                timelinePostRepository.findByUserIdOrderByCreatedAtDesc(userId, Pageable.unpaged()));
    }

    private String collectAuditLogs(Long userId) throws Exception {
        // AuditLogRepositoryはJpaRepositoryのみ継承、userId検索メソッドが存在しない
        log.warn("リポジトリ未実装: category=audit_logs (userId検索メソッドなし)");
        return "[]";
    }

    private String collectNotifications(Long userId) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(
                notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, Pageable.unpaged())
                        .getContent());
    }

    private String decryptSafe(String cipherText) {
        if (cipherText == null) return null;
        try {
            return encryptionService.decrypt(cipherText);
        } catch (Exception e) {
            log.warn("フィールド復号失敗", e);
            return null;
        }
    }
}
