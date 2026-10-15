package com.mannschaft.app.notification.service;

import com.mannschaft.app.committee.service.CommitteeAccessGuard;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.notification.NotificationErrorCode;
import com.mannschaft.app.notification.NotificationMapper;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.NotificationType;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 通知設定サービス。スコープ別・種別別・グローバルの通知設定を管理する（F04.3 ハイブリッド方式）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationPreferenceService {

    /** 配信チャネルキー（アプリ内 / プッシュ）。 */
    public static final String CHANNEL_IN_APP = "IN_APP";
    public static final String CHANNEL_PUSH = "PUSH";

    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationTypePreferenceRepository typePreferenceRepository;
    private final NotificationSettingsRepository settingsRepository;
    private final NotificationMapper notificationMapper;
    private final NameResolverService nameResolverService;
    private final MessageSource messageSource;
    private final AccessControlService accessControlService;
    private final CommitteeAccessGuard committeeAccessGuard;

    // ============================================================
    // スコープ別設定（notification_preferences）
    // ============================================================

    /**
     * ユーザーの通知設定一覧を取得する。各スコープに表示名を充填する。
     *
     * @param userId ユーザーID
     * @return 通知設定レスポンスリスト
     */
    public List<PreferenceResponse> listPreferences(Long userId) {
        List<NotificationPreferenceEntity> entities = preferenceRepository.findByUserId(userId);
        if (entities.isEmpty()) {
            return List.of();
        }
        // ScopeAffiliationCache は listPreferences 1 回の呼び出しにつき 1 個だけ構築する。
        // stream().map() の lambda 内で毎回 new すると行数ぶんクエリが増える（N+1）ため、
        // ループの外で 1 度だけ構築して使い回す（AC-30）。
        ScopeAffiliationCache cache = new ScopeAffiliationCache(userId, entities);
        return entities.stream()
                .map(notificationMapper::toPreferenceResponse)
                .map(response -> fillScopeName(userId, response, cache))
                .collect(Collectors.toList());
    }

    /**
     * PreferenceResponse にスコープ表示名を充填する（越境禁止・非所属スコープは実名を返さない）。
     *
     * <p>認可根治戦役 Wave4 ロットD AC-29: 行が非所属スコープを指していても（レガシーデータ・
     * 脱退後の残置行等）実名を返してはならない（実在／非実在の区別が付く名前列挙オラクルになるため）。
     * 所属可否の判定は {@link ScopeAffiliationCache} が {@code listPreferences} 呼び出し単位で
     * まとめて 1 回だけ解決した集合を参照するため、行数に比例したクエリは発生しない（AC-30）。</p>
     */
    private PreferenceResponse fillScopeName(Long userId, PreferenceResponse response,
                                              ScopeAffiliationCache cache) {
        if (response.getScope() == null) {
            return response;
        }
        String scopeType = response.getScope().scopeType();
        Long scopeId = response.getScope().scopeId();
        String scopeName = switch (scopeType == null ? "" : scopeType) {
            case "TEAM", "FRIEND_TEAM" -> cache.isAffiliatedTeam(scopeId)
                    ? cache.teamName(scopeId)
                    : "不明なチーム";
            case "ORGANIZATION" -> cache.isAffiliatedOrganization(scopeId)
                    ? cache.organizationName(scopeId)
                    : "不明な組織";
            case "PERSONAL" -> "個人";
            default -> "不明なスコープ";
        };
        return response.toBuilder().scopeName(scopeName).build();
    }

    /**
     * {@link #listPreferences} 1 回の呼び出しに閉じたスコープ所属・表示名のキャッシュ。
     *
     * <p>行ごとに所属チェック・名前解決を行うと N+1 になるため（AC-30）、対象行に登場する
     * scopeId を種別ごとにまとめて 1 回のクエリ（{@link AccessControlService#findAffiliatedScopeIds}
     * 等）で解決する。SYSTEM_ADMIN は全件を所属扱いとする（他 API の越境窓口と同じ扱い）。</p>
     */
    private final class ScopeAffiliationCache {

        private final Set<Long> affiliatedTeamIds;
        private final Set<Long> affiliatedOrganizationIds;
        private final Map<Long, String> teamNames;
        private final Map<Long, String> organizationNames;

        ScopeAffiliationCache(Long userId, List<NotificationPreferenceEntity> entities) {
            boolean systemAdmin = accessControlService.isSystemAdmin(userId);

            Set<Long> teamIds = new LinkedHashSet<>();
            Set<Long> orgIds = new LinkedHashSet<>();
            for (NotificationPreferenceEntity e : entities) {
                if (e.getScopeId() == null) {
                    continue;
                }
                if ("TEAM".equals(e.getScopeType()) || "FRIEND_TEAM".equals(e.getScopeType())) {
                    teamIds.add(e.getScopeId());
                } else if ("ORGANIZATION".equals(e.getScopeType())) {
                    orgIds.add(e.getScopeId());
                }
            }

            this.affiliatedTeamIds = teamIds.isEmpty()
                    ? Set.of()
                    : systemAdmin ? teamIds : accessControlService.findAffiliatedScopeIds(userId, "TEAM");
            this.affiliatedOrganizationIds = orgIds.isEmpty()
                    ? Set.of()
                    : systemAdmin ? orgIds : accessControlService.findAffiliatedScopeIds(userId, "ORGANIZATION");

            Set<Long> resolvableTeamIds = teamIds.stream()
                    .filter(this.affiliatedTeamIds::contains).collect(Collectors.toSet());
            Set<Long> resolvableOrgIds = orgIds.stream()
                    .filter(this.affiliatedOrganizationIds::contains).collect(Collectors.toSet());
            this.teamNames = nameResolverService.resolveTeamNames(resolvableTeamIds);
            this.organizationNames = nameResolverService.resolveOrganizationNames(resolvableOrgIds);
        }

        boolean isAffiliatedTeam(Long teamId) {
            return teamId != null && affiliatedTeamIds.contains(teamId);
        }

        boolean isAffiliatedOrganization(Long orgId) {
            return orgId != null && affiliatedOrganizationIds.contains(orgId);
        }

        String teamName(Long teamId) {
            return teamNames.getOrDefault(teamId, "不明なチーム");
        }

        String organizationName(Long orgId) {
            return organizationNames.getOrDefault(orgId, "不明な組織");
        }
    }

    /**
     * 通知設定を更新する（存在しない場合は新規作成）。
     *
     * @param userId  ユーザーID
     * @param request 更新リクエスト
     * @return 更新された通知設定レスポンス
     */
    @Transactional
    public PreferenceResponse updatePreference(Long userId, PreferenceUpdateRequest request) {
        authorizeScope(userId, request.getScopeType(), request.getScopeId());

        NotificationPreferenceEntity entity = preferenceRepository
                .findByUserIdAndScopeTypeAndScopeId(userId, request.getScopeType(), request.getScopeId())
                .orElse(null);

        if (entity == null) {
            entity = NotificationPreferenceEntity.builder()
                    .userId(userId)
                    .scopeType(request.getScopeType())
                    .scopeId(request.getScopeId())
                    .isEnabled(request.getIsEnabled())
                    .build();
        } else {
            entity.updateEnabled(request.getIsEnabled());
        }

        NotificationPreferenceEntity saved = preferenceRepository.save(entity);
        log.info("通知設定更新: userId={}, scopeType={}, scopeId={}, enabled={}",
                userId, request.getScopeType(), request.getScopeId(), request.getIsEnabled());
        return fillScopeName(userId, notificationMapper.toPreferenceResponse(saved),
                new ScopeAffiliationCache(userId, List.of(saved)));
    }

    /**
     * {@code request} のスコープ指定を検証する（認可根治戦役 Wave4 ロットD）。
     *
     * <p>既存行の有無に関わらず必ず実行する（{@link #updatePreference} の先頭で呼ぶ）。既存行を
     * 迂回路にして検証を素通りさせない（AC-23）。</p>
     *
     * <table>
     * <caption>スコープ別の資格</caption>
     * <tr><th>scopeType</th><th>scopeId</th><th>資格</th></tr>
     * <tr><td>TEAM / FRIEND_TEAM</td><td>必須</td>
     *     <td>SYSTEM_ADMIN || ADMIN以上(user_roles) || メンバー(memberships)</td></tr>
     * <tr><td>ORGANIZATION</td><td>必須（直接所属のみ。配下チーム所属は対象外）</td>
     *     <td>SYSTEM_ADMIN || ADMIN以上(user_roles) || メンバー(memberships)</td></tr>
     * <tr><td>COMMITTEE</td><td>必須</td><td>委員会の現役メンバー</td></tr>
     * <tr><td>PERSONAL</td><td>null または自分の userId</td><td>常に可（他人の userId は拒否）</td></tr>
     * <tr><td>SYSTEM</td><td>null のみ</td><td>常に可（非 null は 400）</td></tr>
     * <tr><td>FRIEND_FOLDER</td><td>-</td><td>常に拒否（使用箇所ゼロ）</td></tr>
     * </table>
     *
     * <p>SUPPORTER を排除しない（{@link AccessControlService#isMember} は role_kind を問わず判定する）。
     * シフト表の未公開情報遮断とは異なり、SUPPORTER もそのスコープの通知を受け取りうるため、
     * 受信可否を止める理由が無い。</p>
     *
     * @throws BusinessException {@link NotificationErrorCode#INVALID_PREFERENCE_SCOPE}（400）:
     *         scopeType 未指定・未知の列挙値・ID必須スコープでの scopeId 欠落・SYSTEM での scopeId 非null。
     *         {@link CommonErrorCode#COMMON_002}（403）: スコープへの資格が無い。
     */
    private void authorizeScope(Long userId, String scopeType, Long scopeId) {
        if (scopeType == null) {
            throw new BusinessException(NotificationErrorCode.INVALID_PREFERENCE_SCOPE);
        }
        NotificationScopeType type;
        try {
            type = NotificationScopeType.valueOf(scopeType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(NotificationErrorCode.INVALID_PREFERENCE_SCOPE);
        }

        switch (type) {
            case TEAM, FRIEND_TEAM -> {
                requireScopeId(scopeId);
                requireTeamOrOrganizationAccess(userId, scopeId, "TEAM");
            }
            case ORGANIZATION -> {
                requireScopeId(scopeId);
                requireTeamOrOrganizationAccess(userId, scopeId, "ORGANIZATION");
            }
            case COMMITTEE -> {
                requireScopeId(scopeId);
                committeeAccessGuard.requireCommitteeMember(scopeId, userId);
            }
            case PERSONAL -> {
                if (scopeId != null && !scopeId.equals(userId)) {
                    throw new BusinessException(CommonErrorCode.COMMON_002);
                }
            }
            case SYSTEM -> {
                if (scopeId != null) {
                    throw new BusinessException(NotificationErrorCode.INVALID_PREFERENCE_SCOPE);
                }
            }
            case FRIEND_FOLDER -> throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    private void requireScopeId(Long scopeId) {
        if (scopeId == null) {
            throw new BusinessException(NotificationErrorCode.INVALID_PREFERENCE_SCOPE);
        }
    }

    /**
     * TEAM / ORGANIZATION（直接所属のみ）の資格を検証する。
     * SYSTEM_ADMIN || ADMIN以上(user_roles) || メンバー(memberships) のいずれか。
     */
    private void requireTeamOrOrganizationAccess(Long userId, Long scopeId, String scopeType) {
        if (accessControlService.isSystemAdmin(userId)
                || accessControlService.isAdminOrAbove(userId, scopeId, scopeType)
                || accessControlService.isMember(userId, scopeId, scopeType)) {
            return;
        }
        throw new BusinessException(CommonErrorCode.COMMON_002);
    }

    // ============================================================
    // 種別別設定（notification_type_preferences・カタログ + ハイブリッド）
    // ============================================================

    /**
     * 通知種別設定をカタログとして取得する。
     *
     * <p>{@link NotificationType} 全種別 × DB 既存行を merge して全種別を返す
     * （真因A=空配列回帰の防止）。行のない種別は enum 既定値を返す。
     * label は MessageSource でユーザーロケール解決する。</p>
     *
     * @param userId ユーザーID
     * @return 全種別の通知種別設定レスポンスリスト
     */
    public List<TypePreferenceResponse> listTypePreferences(Long userId) {
        Map<String, NotificationTypePreferenceEntity> saved = typePreferenceRepository.findByUserId(userId).stream()
                .filter(e -> e.getNotificationType() != null)
                .collect(Collectors.toMap(
                        NotificationTypePreferenceEntity::getNotificationType,
                        Function.identity(),
                        (a, b) -> a));

        Locale locale = LocaleContextHolder.getLocale();
        List<TypePreferenceResponse> result = new ArrayList<>();
        for (NotificationType type : NotificationType.values()) {
            NotificationTypePreferenceEntity row = saved.get(type.name());
            result.add(buildTypeResponse(userId, type, row, locale));
        }
        return result;
    }

    /**
     * enum カタログと DB 行（あれば）から TypePreferenceResponse を組み立てる。
     */
    private TypePreferenceResponse buildTypeResponse(Long userId, NotificationType type,
                                                     NotificationTypePreferenceEntity row, Locale locale) {
        boolean isEnabled = row != null ? Boolean.TRUE.equals(row.getIsEnabled()) : type.isDefaultEnabled();
        boolean channelOverride = row != null && Boolean.TRUE.equals(row.getChannelOverride());
        boolean inAppEnabled = row != null ? Boolean.TRUE.equals(row.getInAppEnabled()) : true;
        boolean pushEnabled = row != null ? Boolean.TRUE.equals(row.getPushEnabled()) : true;

        return TypePreferenceResponse.builder()
                .id(row != null ? row.getId() : null)
                .userId(userId)
                .notificationType(type.name())
                .label(resolveLabel(type, locale))
                .priority(type.getPriority().name())
                .isEnabled(isEnabled)
                .channelOverride(channelOverride)
                .inAppEnabled(inAppEnabled)
                .pushEnabled(pushEnabled)
                .isLocked(type.isLocked())
                .audit(row != null
                        ? new TypePreferenceResponse.TypePrefAuditDto(row.getCreatedAt(), row.getUpdatedAt())
                        : null)
                .build();
    }

    /**
     * 種別ラベルを MessageSource で解決する。キー未定義時は enum 名にフォールバック。
     */
    private String resolveLabel(NotificationType type, Locale locale) {
        return messageSource.getMessage(type.getLabelKey(), null, type.name(), locale);
    }

    /**
     * 通知種別設定を一括更新する（UPSERT）。
     *
     * <p>各エントリの {@code channelOverride} で単一/Dual を分岐し、条件付き必須を検証する。
     * URGENT（ロック）種別はスキップし {@code ignoredLockedCount} に計上する。
     * 未知の種別・必須欠落は {@link IllegalArgumentException}（→ 400）。</p>
     *
     * @param userId  ユーザーID
     * @param request 一括更新リクエスト
     * @return 更新件数・スキップ件数
     */
    @Transactional
    public TypePreferenceBulkUpdateResponse bulkUpdateTypePreferences(Long userId,
                                                                      TypePreferenceBulkUpdateRequest request) {
        // 先に全件バリデーション（部分更新で不整合を残さない）
        for (TypePreferenceBulkUpdateRequest.TypePreferenceEntry entry : request.getPreferences()) {
            validateEntry(entry);
        }

        int updatedCount = 0;
        int ignoredLockedCount = 0;

        for (TypePreferenceBulkUpdateRequest.TypePreferenceEntry entry : request.getPreferences()) {
            NotificationType type = NotificationType.fromValue(entry.getNotificationType())
                    .orElseThrow(() -> new BusinessException(NotificationErrorCode.INVALID_TYPE_PREFERENCE));

            // URGENT（ロック）種別はスキップ
            if (type.isLocked()) {
                ignoredLockedCount++;
                continue;
            }

            boolean channelOverride = Boolean.TRUE.equals(entry.getChannelOverride());
            boolean isEnabled = channelOverride
                    ? true
                    : Boolean.TRUE.equals(entry.getIsEnabled());
            boolean inAppEnabled = channelOverride
                    ? Boolean.TRUE.equals(entry.getInAppEnabled())
                    : true;
            boolean pushEnabled = channelOverride
                    ? Boolean.TRUE.equals(entry.getPushEnabled())
                    : true;

            NotificationTypePreferenceEntity entity = typePreferenceRepository
                    .findByUserIdAndNotificationType(userId, type.name())
                    .orElse(null);

            if (entity == null) {
                entity = NotificationTypePreferenceEntity.builder()
                        .userId(userId)
                        .notificationType(type.name())
                        .isEnabled(isEnabled)
                        .channelOverride(channelOverride)
                        .inAppEnabled(inAppEnabled)
                        .pushEnabled(pushEnabled)
                        .build();
            } else {
                entity.updateHybrid(channelOverride, isEnabled, inAppEnabled, pushEnabled);
            }
            typePreferenceRepository.save(entity);
            updatedCount++;
        }

        log.info("通知種別設定一括更新: userId={}, updated={}, ignoredLocked={}",
                userId, updatedCount, ignoredLockedCount);
        return TypePreferenceBulkUpdateResponse.builder()
                .updatedCount(updatedCount)
                .ignoredLockedCount(ignoredLockedCount)
                .build();
    }

    /**
     * エントリの条件付き必須を検証する。未知種別・必須欠落は IllegalArgumentException。
     */
    private void validateEntry(TypePreferenceBulkUpdateRequest.TypePreferenceEntry entry) {
        if (NotificationType.fromValue(entry.getNotificationType()).isEmpty()) {
            throw new BusinessException(NotificationErrorCode.INVALID_TYPE_PREFERENCE);
        }
        boolean channelOverride = Boolean.TRUE.equals(entry.getChannelOverride());
        if (channelOverride) {
            if (entry.getInAppEnabled() == null || entry.getPushEnabled() == null) {
                throw new BusinessException(NotificationErrorCode.INVALID_TYPE_PREFERENCE);
            }
        } else {
            if (entry.getIsEnabled() == null) {
                throw new BusinessException(NotificationErrorCode.INVALID_TYPE_PREFERENCE);
            }
        }
    }

    // ============================================================
    // グローバル設定（notification_settings）
    // ============================================================

    /**
     * グローバル通知設定を取得する。レコードがなければ既定値（priorityAutoDelivery=true）。
     * 読み取り専用・副作用なし。
     *
     * @param userId ユーザーID
     * @return グローバル通知設定レスポンス
     */
    public NotificationSettingsResponse getSettings(Long userId) {
        boolean autoDelivery = settingsRepository.findByUserId(userId)
                .map(NotificationSettingsEntity::getPriorityAutoDelivery)
                .map(Boolean::booleanValue)
                .orElse(true);
        return NotificationSettingsResponse.builder()
                .priorityAutoDelivery(autoDelivery)
                .build();
    }

    /**
     * グローバル通知設定を更新する（UPSERT・user_id 一意）。
     *
     * @param userId  ユーザーID
     * @param request 更新リクエスト
     * @return 更新後のグローバル通知設定レスポンス
     */
    @Transactional
    public NotificationSettingsResponse updateSettings(Long userId,
                                                       NotificationSettingsUpdateRequest request) {
        NotificationSettingsEntity entity = settingsRepository.findByUserId(userId).orElse(null);
        if (entity == null) {
            entity = NotificationSettingsEntity.builder()
                    .userId(userId)
                    .priorityAutoDelivery(request.getPriorityAutoDelivery())
                    .build();
        } else {
            entity.updatePriorityAutoDelivery(request.getPriorityAutoDelivery());
        }
        NotificationSettingsEntity saved = settingsRepository.save(entity);
        log.info("グローバル通知設定更新: userId={}, priorityAutoDelivery={}",
                userId, request.getPriorityAutoDelivery());
        return NotificationSettingsResponse.builder()
                .priorityAutoDelivery(saved.getPriorityAutoDelivery())
                .build();
    }

    // ============================================================
    // 配信判定（§5 配信チャネルの決定・ハイブリッド方式）
    // ============================================================

    /**
     * 通知種別 {@code type} をユーザー {@code userId} に配信する際の許可チャネル集合を返す
     * （F04.3 §5 配信判定ロジック）。
     *
     * <pre>
     * 1. priority == URGENT          → {IN_APP, PUSH}（全設定無視・強制配信）
     * 2. channelOverride == true     → in_app_enabled / push_enabled を直接適用（手動優先）
     * 3. channelOverride == false:
     *    3a. is_enabled == false     → {}（受信しない）
     *    3b. is_enabled == true      → IN_APP は常に ON、
     *                                  PUSH は priorityAutoDelivery AND priority >= NORMAL
     * </pre>
     *
     * @param userId   ユーザーID
     * @param type     通知種別
     * @param priority 通知優先度
     * @return 許可チャネル集合（{@code IN_APP} / {@code PUSH}）
     */
    public Set<String> resolveChannels(Long userId, NotificationType type, NotificationPriority priority) {
        Set<String> channels = new LinkedHashSet<>();

        // 1. URGENT（ロック）は全チャネル強制配信
        if (priority == NotificationPriority.URGENT) {
            channels.add(CHANNEL_IN_APP);
            channels.add(CHANNEL_PUSH);
            return channels;
        }

        Optional<NotificationTypePreferenceEntity> rowOpt =
                typePreferenceRepository.findByUserIdAndNotificationType(userId, type.name());

        boolean channelOverride = rowOpt
                .map(e -> Boolean.TRUE.equals(e.getChannelOverride()))
                .orElse(false);

        // 2. Dual モード（手動優先・自動配信は適用しない）
        if (channelOverride) {
            NotificationTypePreferenceEntity row = rowOpt.get();
            if (Boolean.TRUE.equals(row.getInAppEnabled())) {
                channels.add(CHANNEL_IN_APP);
            }
            if (Boolean.TRUE.equals(row.getPushEnabled())) {
                channels.add(CHANNEL_PUSH);
            }
            return channels;
        }

        // 3. 単一モード
        boolean isEnabled = rowOpt
                .map(e -> Boolean.TRUE.equals(e.getIsEnabled()))
                .orElse(type.isDefaultEnabled());

        // 3a. 受信しない
        if (!isEnabled) {
            return channels;
        }

        // 3b. アプリ内は常に基線 ON
        channels.add(CHANNEL_IN_APP);

        boolean autoDelivery = settingsRepository.findByUserId(userId)
                .map(NotificationSettingsEntity::getPriorityAutoDelivery)
                .map(Boolean::booleanValue)
                .orElse(true);
        if (autoDelivery && isAtLeastNormal(priority)) {
            channels.add(CHANNEL_PUSH);
        }
        return channels;
    }

    private boolean isAtLeastNormal(NotificationPriority priority) {
        return priority == NotificationPriority.NORMAL
                || priority == NotificationPriority.HIGH
                || priority == NotificationPriority.URGENT;
    }

    // ============================================================
    // 後方互換 API（既存 NotificationDispatchService が利用）
    // ============================================================

    /**
     * 指定ユーザーの指定スコープで通知が有効かどうかを判定する。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 有効な場合 true
     */
    public boolean isNotificationEnabled(Long userId, String scopeType, Long scopeId) {
        return preferenceRepository.findByUserIdAndScopeTypeAndScopeId(userId, scopeType, scopeId)
                .map(NotificationPreferenceEntity::getIsEnabled)
                .orElse(true);
    }

    /**
     * 指定ユーザーの指定通知種別が有効かどうかを判定する（単一モードの受信可否）。
     *
     * @param userId           ユーザーID
     * @param notificationType 通知種別
     * @return 有効な場合 true
     */
    public boolean isTypeEnabled(Long userId, String notificationType) {
        Optional<NotificationTypePreferenceEntity> rowOpt =
                typePreferenceRepository.findByUserIdAndNotificationType(userId, notificationType);
        if (rowOpt.isPresent()) {
            return Boolean.TRUE.equals(rowOpt.get().getIsEnabled());
        }
        // 行がなければ enum 既定値（DAILY_DIGEST のみ false）
        return NotificationType.fromValue(notificationType)
                .map(NotificationType::isDefaultEnabled)
                .orElse(true);
    }
}
