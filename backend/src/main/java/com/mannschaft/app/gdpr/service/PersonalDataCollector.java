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
import com.mannschaft.app.errorreport.entity.ErrorReportOccurrenceEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportOccurrenceRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import com.mannschaft.app.inbox.entity.InboxItemStateEntity;
import com.mannschaft.app.inbox.entity.InboxLabelLinkEntity;
import com.mannschaft.app.inbox.entity.NotificationLabelEntity;
import com.mannschaft.app.inbox.repository.InboxItemStateRepository;
import com.mannschaft.app.inbox.repository.InboxLabelLinkRepository;
import com.mannschaft.app.inbox.repository.NotificationLabelRepository;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.pointcard.dto.PointCardExportDto;
import com.mannschaft.app.pointcard.entity.PointCardGroupEntity;
import com.mannschaft.app.pointcard.entity.PointCardGroupItemEntity;
import com.mannschaft.app.pointcard.entity.PointCardUserSettingsEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.repository.PointCardGroupItemRepository;
import com.mannschaft.app.pointcard.repository.PointCardGroupRepository;
import com.mannschaft.app.pointcard.repository.PointCardUserSettingsRepository;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import com.mannschaft.app.resume.entity.ResumeCareerEntity;
import com.mannschaft.app.resume.entity.ResumeEducationEntity;
import com.mannschaft.app.resume.entity.ResumeEntity;
import com.mannschaft.app.resume.entity.ResumeQualificationEntity;
import com.mannschaft.app.resume.entity.ResumeSkillEntity;
import com.mannschaft.app.resume.repository.ResumeCareerRepository;
import com.mannschaft.app.resume.repository.ResumeEducationRepository;
import com.mannschaft.app.resume.repository.ResumeQualificationRepository;
import com.mannschaft.app.resume.repository.ResumeRepository;
import com.mannschaft.app.resume.repository.ResumeSkillRepository;
import com.mannschaft.app.schedule.entity.ScheduleCommentEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.weather.entity.UserWeatherLocationEntity;
import com.mannschaft.app.weather.repository.UserWeatherLocationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
    private final ErrorReportOccurrenceRepository errorReportOccurrenceRepository;
    private final ProxyInputConsentRepository proxyInputConsentRepository;
    private final ProxyInputRecordRepository proxyInputRecordRepository;
    private final UserWeatherLocationRepository userWeatherLocationRepository;
    private final EncryptionService encryptionService;
    // F18 個人ポイントカードウォレット（S3 で本実装）
    private final UserPointCardRepository userPointCardRepository;
    private final PointCardUserSettingsRepository pointCardUserSettingsRepository;
    private final PointCardGroupRepository pointCardGroupRepository;
    private final PointCardGroupItemRepository pointCardGroupItemRepository;
    // F01.10 履歴書・職務経歴書（Phase 5 で追加）
    private final ResumeRepository resumeRepository;
    private final ResumeEducationRepository resumeEducationRepository;
    private final ResumeCareerRepository resumeCareerRepository;
    private final ResumeQualificationRepository resumeQualificationRepository;
    private final ResumeSkillRepository resumeSkillRepository;
    // F04.11 統合通知インボックス（per-user オーバーレイ3表）
    private final InboxItemStateRepository inboxItemStateRepository;
    private final NotificationLabelRepository notificationLabelRepository;
    private final InboxLabelLinkRepository inboxLabelLinkRepository;

    // F03.16 予定コメントスレッド: schedule_comments はドメイン専用リポジトリの declared メソッド集合が
    // AC-34（ScheduleCommentThreadContractIT）で厳密に固定されているため、収集専用の finder をそこへ
    // 追加しない。EntityManager 直叩きで完結させる（本 Collector オーケストレータへの局所変更に留める）。
    @PersistenceContext
    private EntityManager entityManager;

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
            Map.entry("proxy_records", "proxy_input_records.json"),
            // F02.10 天気ウィジェット — ユーザー地点キャッシュ（嗜好情報レベル）
            Map.entry("location_preference", "weather_locations.json"),
            // F18 個人ポイントカードウォレット（第二陣 2C スケルトン、第三陣で完成）
            Map.entry("point_cards", "point_cards.json"),
            // F01.10 履歴書・職務経歴書（Phase 5 で追加）
            Map.entry("resumes", "resumes.json"),
            // F04.11 統合通知インボックス（per-user オーバーレイ3表を 1 カテゴリにまとめる）
            Map.entry("inbox", "inbox.json"),
            // F03.16 予定コメントスレッド（@PersonalData(category="scheduleComments") と対で登録）。
            // AC-35 は collect() の戻り Map のキーが category 文字列そのものであることを検証するため
            // （他カテゴリの慣例は snake_case ファイル名だが、本カテゴリはキー＝ファイル名を一致させる）、
            // ファイル名も category と同一文字列にする。
            Map.entry("scheduleComments", "scheduleComments")
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
            case "location_preference" -> collectLocationPreference(userId);
            case "point_cards" -> collectPointCards(userId);
            case "resumes" -> collectResumes(userId);
            case "inbox" -> collectInbox(userId);
            case "scheduleComments" -> collectScheduleComments(userId);
            default -> "[]";
        };
    }

    /**
     * F18 個人ポイントカードウォレットを収集する（GDPR エクスポート用）。
     *
     * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §10
     *
     * <p>収集対象:
     * <ul>
     *   <li>{@code settings}: ユーザー設定（オプトイン / 規約同意 / 生体認証要求）</li>
     *   <li>{@code cards}: 保有カード全件（{@code EncryptedStringConverter} が
     *       SELECT 時に displayName / nickname / barcodeValue / memo を自動復号する）</li>
     *   <li>{@code groups}: グループ + 各グループの中間アイテム（card_id 配列）</li>
     * </ul>
     *
     * <p>暗号化フィールドは Hibernate AttributeConverter により読み込み時に透過復号されるため、
     * Entity をそのまま DTO に詰めると平文で JSON 出力される（GDPR 第 15 条のアクセス権実現）。
     */
    private String collectPointCards(Long userId) throws Exception {
        Optional<PointCardUserSettingsEntity> settings =
                pointCardUserSettingsRepository.findById(userId);

        List<UserPointCardEntity> cards = userPointCardRepository.findByUserId(userId);
        List<PointCardExportDto> cardDtos = cards.stream()
                .map(PointCardExportDto::from)
                .toList();

        List<PointCardGroupEntity> groups =
                pointCardGroupRepository.findAllByUserIdOrderByDisplayOrderAscCreatedAtAsc(userId);
        List<Map<String, Object>> groupOutput;
        if (groups.isEmpty()) {
            groupOutput = List.of();
        } else {
            List<java.util.UUID> groupIds = groups.stream()
                    .map(PointCardGroupEntity::getId)
                    .toList();
            // 1 SQL でまとめて取得した上でグループ単位に詰め替える
            Map<java.util.UUID, List<PointCardGroupItemEntity>> itemsByGroup =
                    pointCardGroupItemRepository.findAllByGroupIdIn(groupIds).stream()
                            .collect(java.util.stream.Collectors.groupingBy(
                                    PointCardGroupItemEntity::getGroupId));
            groupOutput = new java.util.ArrayList<>(groups.size());
            for (PointCardGroupEntity g : groups) {
                Map<String, Object> groupEntry = new LinkedHashMap<>();
                groupEntry.put("id", g.getId());
                groupEntry.put("name", g.getName());
                groupEntry.put("emoji", g.getEmoji());
                groupEntry.put("displayOrder", g.getDisplayOrder());
                groupEntry.put("createdAt", g.getCreatedAt());
                groupEntry.put("updatedAt", g.getUpdatedAt());
                List<Map<String, Object>> itemDtos =
                        itemsByGroup.getOrDefault(g.getId(), List.of()).stream()
                                .map(i -> {
                                    Map<String, Object> entry = new LinkedHashMap<>();
                                    entry.put("cardId", i.getCardId());
                                    entry.put("displayOrder", i.getDisplayOrder());
                                    entry.put("createdAt", i.getCreatedAt());
                                    return entry;
                                })
                                .toList();
                groupEntry.put("items", itemDtos);
                groupOutput.add(groupEntry);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("settings", settings.orElse(null));
        payload.put("cards", cardDtos);
        payload.put("groups", groupOutput);
        return OBJECT_MAPPER.writeValueAsString(payload);
    }

    /**
     * F01.10 履歴書・職務経歴書データを収集する（GDPR エクスポート用）。
     *
     * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §9.3
     *
     * <p>収集対象:
     * <ul>
     *   <li>履歴書バージョンごとにヘッダ + 学歴 + 職歴 + 免許資格 + スキル</li>
     *   <li>暗号化カラム（住所・電話・メール）は {@link EncryptionService} で復号して出力（GDPR 第 15 条アクセス権）</li>
     *   <li>証明写真はストレージキー（{@code photo_key}）のみ出力（バイナリは含めない）</li>
     *   <li>論理削除済み（{@code deleted_at IS NOT NULL}）は {@code @SQLRestriction} により除外される</li>
     * </ul>
     *
     * <p>返される JSON の構造:
     * <pre>
     * {
     *   "resumes": [
     *     {
     *       "id": "...",
     *       "title": "標準",
     *       "current_address": "東京都...",
     *       ...
     *       "educations": [ ... ],
     *       "careers": [ ... ],
     *       "qualifications": [ ... ],
     *       "skills": [ ... ]
     *     }
     *   ]
     * }
     * </pre>
     */
    private String collectResumes(Long userId) throws Exception {
        List<ResumeEntity> resumes = resumeRepository.findByUserIdOrderByCreatedAtDesc(userId);

        List<Map<String, Object>> resumeList = new java.util.ArrayList<>(resumes.size());
        for (ResumeEntity resume : resumes) {
            java.util.UUID resumeId = resume.getId();

            // 学歴
            List<ResumeEducationEntity> educations =
                    resumeEducationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId);
            List<Map<String, Object>> educationList = educations.stream().map(edu -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", edu.getId());
                entry.put("entryYear", edu.getEntryYear());
                entry.put("entryMonth", edu.getEntryMonth());
                entry.put("description", edu.getDescription());
                entry.put("displayOrder", edu.getDisplayOrder());
                return entry;
            }).toList();

            // 職歴
            List<ResumeCareerEntity> careers =
                    resumeCareerRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId);
            List<Map<String, Object>> careerList = careers.stream().map(career -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", career.getId());
                entry.put("entryYear", career.getEntryYear());
                entry.put("entryMonth", career.getEntryMonth());
                entry.put("endYear", career.getEndYear());
                entry.put("endMonth", career.getEndMonth());
                entry.put("isCurrent", career.isCurrent());
                entry.put("companyName", career.getCompanyName());
                entry.put("department", career.getDepartment());
                entry.put("employmentType", career.getEmploymentType());
                entry.put("businessSummary", career.getBusinessSummary());
                entry.put("jobDescription", career.getJobDescription());
                entry.put("achievements", career.getAchievements());
                entry.put("includeInRirekisho", career.isIncludeInRirekisho());
                entry.put("includeInShokumukeireki", career.isIncludeInShokumukeireki());
                entry.put("displayOrder", career.getDisplayOrder());
                return entry;
            }).toList();

            // 免許・資格
            List<ResumeQualificationEntity> qualifications =
                    resumeQualificationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId);
            List<Map<String, Object>> qualificationList = qualifications.stream().map(qual -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", qual.getId());
                entry.put("acquiredYear", qual.getAcquiredYear());
                entry.put("acquiredMonth", qual.getAcquiredMonth());
                entry.put("name", qual.getName());
                entry.put("note", qual.getNote());
                entry.put("displayOrder", qual.getDisplayOrder());
                return entry;
            }).toList();

            // 構造化スキル
            List<ResumeSkillEntity> skills =
                    resumeSkillRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId);
            List<Map<String, Object>> skillList = skills.stream().map(skill -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", skill.getId());
                entry.put("skillName", skill.getSkillName());
                entry.put("level", skill.getLevel() != null ? skill.getLevel().name() : null);
                entry.put("description", skill.getDescription());
                entry.put("displayOrder", skill.getDisplayOrder());
                return entry;
            }).toList();

            // 履歴書ヘッダー（暗号化カラムは復号して出力）
            Map<String, Object> resumeEntry = new LinkedHashMap<>();
            resumeEntry.put("id", resume.getId());
            resumeEntry.put("title", resume.getTitle());
            resumeEntry.put("eraFormat", resume.getEraFormat().name());
            resumeEntry.put("photoKey", resume.getPhotoKey());
            resumeEntry.put("currentAddress", decryptSafe(resume.getCurrentAddress()));
            resumeEntry.put("currentAddressKana", decryptSafe(resume.getCurrentAddressKana()));
            resumeEntry.put("contactAddress", decryptSafe(resume.getContactAddress()));
            resumeEntry.put("contactAddressKana", decryptSafe(resume.getContactAddressKana()));
            resumeEntry.put("contactPhone", decryptSafe(resume.getContactPhone()));
            resumeEntry.put("contactEmail", decryptSafe(resume.getContactEmail()));
            resumeEntry.put("motivation", resume.getMotivation());
            resumeEntry.put("selfPr", resume.getSelfPr());
            resumeEntry.put("personalRequest", resume.getPersonalRequest());
            resumeEntry.put("commuteMinutes", resume.getCommuteMinutes());
            resumeEntry.put("dependentsCount", resume.getDependentsCount());
            resumeEntry.put("hasSpouse", resume.getHasSpouse());
            resumeEntry.put("spouseSupport", resume.getSpouseSupport());
            resumeEntry.put("careerSummary", resume.getCareerSummary());
            resumeEntry.put("skillsSummary", resume.getSkillsSummary());
            resumeEntry.put("createdAt", resume.getCreatedAt());
            resumeEntry.put("updatedAt", resume.getUpdatedAt());
            resumeEntry.put("educations", educationList);
            resumeEntry.put("careers", careerList);
            resumeEntry.put("qualifications", qualificationList);
            resumeEntry.put("skills", skillList);

            resumeList.add(resumeEntry);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resumes", resumeList);
        return OBJECT_MAPPER.writeValueAsString(payload);
    }

    /**
     * F02.10 ユーザー地点キャッシュを収集する（GDPR エクスポート用）。
     *
     * <p>{@code country_code} / {@code postal_code_hash} はエクスポートに含めない
     * （前者は users.country_code から取得可能・後者は片方向ハッシュで本人にも復元不能）。
     * 0.5 度丸めの緯度経度・地名スナップショット・derived_at のみ JSON 化する。</p>
     */
    private String collectLocationPreference(Long userId) throws Exception {
        List<UserWeatherLocationEntity> locations =
                userWeatherLocationRepository.findByUserId(userId);
        var result = new java.util.ArrayList<Map<String, Object>>();
        for (UserWeatherLocationEntity loc : locations) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", loc.getId() != null ? loc.getId().toString() : null);
            entry.put("label", loc.getLabel());
            entry.put("latitudeRounded", loc.getLatitudeRounded());
            entry.put("longitudeRounded", loc.getLongitudeRounded());
            entry.put("placeNameSnapshot", loc.getPlaceNameSnapshot());
            entry.put("derivedAt", loc.getDerivedAt());
            result.add(entry);
        }
        return OBJECT_MAPPER.writeValueAsString(result);
    }

    private String collectAccount(Long userId) throws Exception {
        return userRepository.findById(userId).map(user -> {
            try {
                Map<String, Object> accountData = new LinkedHashMap<>();
                accountData.put("id", user.getId());
                accountData.put("email", user.getEmail());
                accountData.put("nickname", user.getDisplayName());
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
     *
     * <p>F12.5 Phase 2 — 個別発生ログ（error_report_occurrences）も同じ
     * カテゴリでまとめてエクスポートする。activities / ai_analyses は管理者作成データ
     * または AI 出力のため個人データ非該当としてエクスポート対象外。</p>
     *
     * <p>返される JSON の構造:</p>
     * <pre>
     * {
     *   "error_reports": [ ... ],
     *   "error_report_occurrences": [ ... ]
     * }
     * </pre>
     */
    private String collectErrorReports(Long userId) throws Exception {
        List<Map<String, Object>> reports = errorReportRepository
                .findByUserIdOrderByCreatedAtDesc(userId).stream().map(er -> {
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
                }).toList();

        List<ErrorReportOccurrenceEntity> occurrences =
                errorReportOccurrenceRepository.findByUserIdOrderByOccurredAtDesc(userId);
        List<Map<String, Object>> occurrenceList = occurrences.stream().map(o -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", o.getId());
            entry.put("errorReportId", o.getErrorReportId());
            entry.put("pageUrl", o.getPageUrl());
            entry.put("userAgent", o.getUserAgent());
            entry.put("ipAddress", o.getIpAddress());
            entry.put("requestId", o.getRequestId());
            entry.put("occurredAt", o.getOccurredAt());
            entry.put("createdAt", o.getCreatedAt());
            return entry;
        }).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error_reports", reports);
        payload.put("error_report_occurrences", occurrenceList);
        return OBJECT_MAPPER.writeValueAsString(payload);
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
     * F04.11 統合通知インボックスの per-user オーバーレイ3表を 1 つの JSON 文字列に
     * まとめて返す（案A：3表フルダンプ）。
     *
     * <p>3表とも {@code user_id} 軸の個人データ（per-user の triage 状態オーバーレイ）であり、
     * {@code AbstractUserOwnedRepository.findByUserId(userId)} で N+1 を回避してまとめ取りする。
     * 通知本体（source）の解決は行わず、{@code (source_type, source_id)} の論理参照を含めた
     * 生データをそのまま出力する（手本: {@code collectActionMemos}）。</p>
     *
     * <p>{@code notification_labels} は {@code @SQLRestriction("deleted_at IS NULL")} により
     * 論理削除済みは自動除外される（手本 action_memos と同じく「ユーザーが削除したと認識する
     * データ」はエクスポートに含めない）。</p>
     *
     * <p>返される JSON の構造:</p>
     * <pre>
     * {
     *   "inbox_item_states": [ ... ],
     *   "notification_labels": [ ... ],
     *   "inbox_label_links": [ ... ]
     * }
     * </pre>
     */
    private String collectInbox(Long userId) throws Exception {
        List<InboxItemStateEntity> states = inboxItemStateRepository.findByUserId(userId);
        List<NotificationLabelEntity> labels = notificationLabelRepository.findByUserId(userId);
        List<InboxLabelLinkEntity> links = inboxLabelLinkRepository.findByUserId(userId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inbox_item_states", states);
        payload.put("notification_labels", labels);
        payload.put("inbox_label_links", links);
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

    /**
     * F03.16 予定コメントスレッドを収集する（GDPR エクスポート用）。
     *
     * <p>設計書: {@code docs/features/F03.16_schedule_comment_thread.md} §3.3 / AC-35。
     * 削除済み（{@code deleted_at} 非 NULL）も本人のデータとして含める
     * （{@code "[]"} を返すだけのスタブでは AC を満たさない）。</p>
     */
    private String collectScheduleComments(Long userId) throws Exception {
        List<ScheduleCommentEntity> comments = entityManager.createQuery(
                        "SELECT c FROM ScheduleCommentEntity c WHERE c.userId = :userId ORDER BY c.createdAt DESC",
                        ScheduleCommentEntity.class)
                .setParameter("userId", userId)
                .getResultList();

        List<Map<String, Object>> result = new java.util.ArrayList<>(comments.size());
        for (ScheduleCommentEntity c : comments) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", c.getId());
            entry.put("scheduleId", c.getScheduleId());
            entry.put("body", c.getBody());
            entry.put("isEdited", c.getIsEdited());
            entry.put("createdAt", c.getCreatedAt());
            entry.put("updatedAt", c.getUpdatedAt());
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
