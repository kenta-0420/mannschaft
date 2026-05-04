package com.mannschaft.app.gdpr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.entity.ActionMemoTagEntity;
import com.mannschaft.app.actionmemo.entity.ActionMemoTagLinkEntity;
import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagLinkRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagRepository;
import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import com.mannschaft.app.auth.entity.OAuthAccountEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
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
import java.util.Optional;
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
    private final ActionMemoRepository actionMemoRepository;
    private final ActionMemoTagRepository actionMemoTagRepository;
    private final ActionMemoTagLinkRepository actionMemoTagLinkRepository;
    private final UserActionMemoSettingsRepository userActionMemoSettingsRepository;
    private final ErrorReportRepository errorReportRepository;
    private final ProxyInputConsentRepository proxyInputConsentRepository;
    private final ProxyInputRecordRepository proxyInputRecordRepository;
    private final EncryptionService encryptionService;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /** カテゴリキー → JSONファイル名のマッピング（変更不可） */
    private static final Map<String, String> CATEGORY_FILES = Map.ofEntries(
            Map.entry("account", "account.json"),
            Map.entry("oauth", "oauth_accounts.json"),
            Map.entry("memberships", "memberships.json"),
            Map.entry("profiles", "profiles.json"),
            Map.entry("payments", "payments.json"),
            Map.entry("charts", "charts.json"),
            Map.entry("chat_messages", "chat_messages.json"),
            Map.entry("timeline", "timeline_posts.json"),
            Map.entry("audit_logs", "audit_logs.json"),
            Map.entry("notifications", "notifications.json"),
            // F02.5 行動メモ（Phase 1.5 で追加）
            Map.entry("action_memos", "action_memos.json"),
            // F12.5 エラーレポート
            Map.entry("error_reports", "error_reports.json"),
            // F14.1 代理入力（Phase 13-γ で追加）
            Map.entry("proxy_consents", "proxy_input_consents.json"),
            Map.entry("proxy_records", "proxy_input_records.json")
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
            case "oauth" -> collectOAuth(userId);
            case "memberships" -> collectMemberships(userId);
            case "profiles" -> collectProfiles(userId);
            case "payments" -> collectPayments(userId);
            case "charts" -> collectCharts(userId);
            case "chat_messages" -> collectChatMessages(userId);
            case "timeline" -> collectTimeline(userId);
            case "audit_logs" -> collectAuditLogs(userId);
            case "notifications" -> collectNotifications(userId);
            case "action_memos" -> collectActionMemos(userId);
            case "error_reports" -> collectErrorReports(userId);
            case "proxy_consents" -> collectProxyConsents(userId);
            case "proxy_records" -> collectProxyRecords(userId);
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

    /**
     * F12.5 エラーレポートを収集する。
     * stackTrace, ipAddress, requestId 等の内部情報は含めず、ユーザーが知り得る情報のみ返す。
     */
    private String collectErrorReports(Long userId) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(
                errorReportRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(er -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", er.getId());
                    entry.put("errorMessage", er.getErrorMessage());
                    entry.put("pageUrl", er.getPageUrl());
                    entry.put("userComment", er.getUserComment());
                    entry.put("occurredAt", er.getOccurredAt());
                    entry.put("status", er.getStatus());
                    entry.put("severity", er.getSeverity());
                    entry.put("createdAt", er.getCreatedAt());
                    return entry;
                }).toList());
    }

    /**
     * F02.5 行動メモ4テーブルを1つの JSON 文字列にまとめて返す。
     * 論理削除済みメモ/タグは除外する（ユーザーが「削除した」と認識しているデータは
     * エクスポートに含めない）。
     *
     * <p>返される JSON の構造:</p>
     * <pre>
     * {
     *   "action_memos": [ ... ],
     *   "action_memo_tags": [ ... ],
     *   "action_memo_tag_links": [ ... ],
     *   "user_action_memo_settings": { ... } | null
     * }
     * </pre>
     */
    private String collectActionMemos(Long userId) throws Exception {
        List<ActionMemoEntity> memos = actionMemoRepository.findByUserIdOrderByMemoDateDescCreatedAtDesc(userId);
        List<ActionMemoTagEntity> tags = actionMemoTagRepository.findByUserIdOrderBySortOrderAsc(userId);
        List<ActionMemoTagLinkEntity> links = actionMemoTagLinkRepository.findByUserId(userId);
        Optional<UserActionMemoSettingsEntity> settings = userActionMemoSettingsRepository.findById(userId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action_memos", memos);
        payload.put("action_memo_tags", tags);
        payload.put("action_memo_tag_links", links);
        payload.put("user_action_memo_settings", settings.orElse(null));
        return OBJECT_MAPPER.writeValueAsString(payload);
    }

    /**
     * 代理入力同意書データを収集する（GDPR エクスポート用）。
     * proxyUserId は PROXY_USER_001 形式で仮名化し、代理者の実名を本人データに含めない。
     */
    private String collectProxyConsents(Long userId) throws Exception {
        List<ProxyInputConsentEntity> consents =
                proxyInputConsentRepository.findAllBySubjectUserIdForExport(userId);
        // proxyUserId をローカル仮名 "PROXY_USER_001" 等に置換
        var result = new java.util.ArrayList<Map<String, Object>>();
        int proxyIndex = 1;
        for (ProxyInputConsentEntity c : consents) {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("id", c.getId());
            entry.put("subjectUserId", c.getSubjectUserId());
            entry.put("proxyUser", String.format("PROXY_USER_%03d", proxyIndex++));
            entry.put("consentMethod", c.getConsentMethod());
            entry.put("effectiveFrom", c.getEffectiveFrom());
            entry.put("effectiveUntil", c.getEffectiveUntil());
            entry.put("approvedAt", c.getApprovedAt());
            entry.put("revokedAt", c.getRevokedAt());
            entry.put("revokeMethod", c.getRevokeMethod());
            result.add(entry);
        }
        return OBJECT_MAPPER.writeValueAsString(result);
    }

    /**
     * 代理入力記録データを収集する（GDPR エクスポート用）。
     * proxyUserId は仮名化して出力する。
     */
    private String collectProxyRecords(Long userId) throws Exception {
        List<ProxyInputRecordEntity> records =
                proxyInputRecordRepository.findBySubjectUserId(userId);
        var result = new java.util.ArrayList<Map<String, Object>>();
        for (ProxyInputRecordEntity r : records) {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("id", r.getId());
            entry.put("subjectUserId", r.getSubjectUserId());
            entry.put("proxyUser", "ANONYMIZED");
            entry.put("featureScope", r.getFeatureScope());
            entry.put("targetEntityType", r.getTargetEntityType());
            entry.put("targetEntityId", r.getTargetEntityId());
            entry.put("inputSource", r.getInputSource());
            entry.put("createdAt", r.getCreatedAt());
            result.add(entry);
        }
        return OBJECT_MAPPER.writeValueAsString(result);
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
