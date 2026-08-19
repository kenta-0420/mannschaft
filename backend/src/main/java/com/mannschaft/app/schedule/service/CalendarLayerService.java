package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.dto.CalendarColorSource;
import com.mannschaft.app.schedule.dto.CalendarLayerResponse;
import com.mannschaft.app.schedule.dto.CalendarLayerUpdateRequest;
import com.mannschaft.app.schedule.entity.UserCalendarLayerSettingEntity;
import com.mannschaft.app.schedule.repository.UserCalendarLayerSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * カレンダーレイヤー設定サービス（F03.19 §4.3〜4.5）。
 *
 * <h2>認可</h2>
 * <p>本サービスは<b>常に呼び出し本人の設定だけ</b>を読み書きする。{@code userId} は
 * Controller が {@code SecurityUtils.getCurrentUserId()} から渡す認証主体であり、
 * リクエストボディ・パス・クエリのいずれからも受け取らない（§10.5 IDOR 防止）。</p>
 *
 * <p>所属判定は {@link AccessControlService#findAffiliatedScopeIds}（{@code user_roles} ∪
 * {@code memberships} の共通窓口）に委譲する【R3】。<b>本サービス専用の所属判定を書き起こさない。</b>
 * これにより「{@code GET /me/teams} の一覧には出るのに色を設定すると 403」という
 * 集合のズレを構造的に排除する（AC-10b2）。非所属スコープは存在／非存在を区別せず
 * 一律 {@link ScheduleErrorCode#CALENDAR_LAYER_NOT_MEMBER}（403）とし、ID 総当たり探索を防ぐ。</p>
 */
@Service
@RequiredArgsConstructor
public class CalendarLayerService {

    /** PERSONAL レイヤーの scopeId センチネル（DB・API・URL・FE キーで統一。R7）。 */
    public static final long PERSONAL_SCOPE_ID = 0L;

    /** PERSONAL レイヤー名の i18n キー（BE は日本語を返さない。§4.3.2 の注記）。 */
    public static final String PERSONAL_SCOPE_NAME_KEY = "schedule.calendar.layer.personal";

    /** 1ユーザーあたりの設定行数の上限（§10.1 / R17。サービス層で担保する）。 */
    public static final long MAX_LAYER_SETTINGS_PER_USER = 1000L;

    private static final String SCOPE_PERSONAL = "PERSONAL";
    private static final String SCOPE_TEAM = "TEAM";
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    /** {@code #RRGGBB}（大小文字許容）。保存時は大文字へ正規化する。 */
    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final UserCalendarLayerSettingRepository repository;
    private final AccessControlService accessControlService;
    private final NameResolverService nameResolverService;

    // ------------------------------------------------------------------
    // §4.3 GET /me/calendar-layers
    // ------------------------------------------------------------------

    /**
     * 本人のレイヤー一覧（所属スコープ ＋ 解決済み色 ＋ 表示可否）を返す。
     *
     * <p>並び順は PERSONAL → ORGANIZATION（scopeId 昇順）→ TEAM（scopeId 昇順）で安定させる
     * （予定の有無に依存しない・AC-04）。設定行が無いレイヤーは自動色（§3.3）で埋まる（P1）。
     * ページングしない（レイヤーは全部見えていること自体が要件・§4.2）。</p>
     */
    @Transactional(readOnly = true)
    public List<CalendarLayerResponse> listLayers(Long userId) {
        // 設定行の取得は常に本人の user_id 限定（他人の設定へ到達する経路を作らない）。
        Map<String, UserCalendarLayerSettingEntity> settings = new HashMap<>();
        for (UserCalendarLayerSettingEntity s : repository.findByUserId(userId)) {
            settings.put(settingKey(s.getScopeType(), s.getScopeId()), s);
        }

        // 所属列挙は AccessControlService の共通窓口（= /me/teams・/me/organizations と同じ 2 系統の和集合）。
        List<Long> orgIds = sortedAsc(accessControlService.findAffiliatedScopeIds(userId, SCOPE_ORGANIZATION));
        List<Long> teamIds = sortedAsc(accessControlService.findAffiliatedScopeIds(userId, SCOPE_TEAM));

        // 名前・アイコンはバッチ解決（N+1 を作らない）。
        Map<Long, String> orgNames = nameResolverService.resolveOrganizationNames(orgIds);
        Map<Long, String> orgIcons = nameResolverService.resolveOrganizationIconUrls(orgIds);
        Map<Long, String> teamNames = nameResolverService.resolveTeamNames(teamIds);
        Map<Long, String> teamIcons = nameResolverService.resolveTeamIconUrls(teamIds);

        List<CalendarLayerResponse> layers = new ArrayList<>();
        layers.add(toResponse(SCOPE_PERSONAL, PERSONAL_SCOPE_ID, SCOPE_PERSONAL, PERSONAL_SCOPE_NAME_KEY,
                null, settings.get(settingKey(SCOPE_PERSONAL, PERSONAL_SCOPE_ID))));

        for (Long orgId : orgIds) {
            String name = orgNames.get(orgId);
            if (name == null) {
                // 名前が解決できない（削除済み等）スコープは一覧に出さない。
                continue;
            }
            layers.add(toResponse(SCOPE_ORGANIZATION, orgId, name, null, orgIcons.get(orgId),
                    settings.get(settingKey(SCOPE_ORGANIZATION, orgId))));
        }
        for (Long teamId : teamIds) {
            String name = teamNames.get(teamId);
            if (name == null) {
                continue;
            }
            layers.add(toResponse(SCOPE_TEAM, teamId, name, null, teamIcons.get(teamId),
                    settings.get(settingKey(SCOPE_TEAM, teamId))));
        }
        return layers;
    }

    // ------------------------------------------------------------------
    // §4.4 PATCH /me/calendar-layers/{scopeType}/{scopeId}
    // ------------------------------------------------------------------

    /**
     * レイヤー設定を<b>部分更新</b>する（R2）。
     *
     * <p>{@code request} の各項目の {@code null} は「変更しない」を意味し、送られなかった項目は
     * 現在値を維持する（色を変えただけで {@code hidden} が巻き戻る P2 違反を作らない）。
     * 設定行がまだ無い場合は作成し、送られなかった項目は既定値
     * （{@code color=null}＝自動色 / {@code hidden=false}）で埋める。</p>
     */
    @Transactional
    public CalendarLayerResponse updateLayer(Long userId, String scopeType, Long scopeId,
                                             CalendarLayerUpdateRequest request) {
        String type = validateScope(scopeType, scopeId);
        long id = normalizedScopeId(type, scopeId);
        // 認可: 非所属スコープの設定は作らせない（存在秘匿のため存在／非存在を区別しない）。
        checkAffiliation(userId, type, id);

        CalendarLayerUpdateRequest body =
                request != null ? request : new CalendarLayerUpdateRequest(null, null);
        String normalizedColor = validateAndNormalizeColor(body.color());

        Optional<UserCalendarLayerSettingEntity> existing =
                repository.findByUserIdAndScopeTypeAndScopeId(userId, type, id);

        UserCalendarLayerSettingEntity entity;
        if (existing.isPresent()) {
            // 既存行の更新は行数上限に関係なく成功する（§10.1）。
            entity = existing.get();
        } else {
            if (repository.countByUserId(userId) >= MAX_LAYER_SETTINGS_PER_USER) {
                throw new BusinessException(ScheduleErrorCode.CALENDAR_LAYER_LIMIT_EXCEEDED);
            }
            entity = UserCalendarLayerSettingEntity.builder()
                    .userId(userId)
                    .scopeType(type)
                    .scopeId(id)
                    .color(null)
                    .hidden(false)
                    .build();
        }

        // 部分更新: null は「変更しない」。
        if (normalizedColor != null) {
            entity.setColor(normalizedColor);
        }
        if (body.hidden() != null) {
            entity.setHidden(body.hidden());
        }
        if (entity.getHidden() == null) {
            entity.setHidden(false);
        }

        UserCalendarLayerSettingEntity saved = repository.save(entity);
        return toResponse(type, id, scopeDisplayName(type, id), scopeNameKey(type),
                scopeIconUrl(type, id), saved);
    }

    // ------------------------------------------------------------------
    // §4.5 DELETE /me/calendar-layers/{scopeType}/{scopeId}
    // ------------------------------------------------------------------

    /**
     * レイヤー設定を物理削除して自動色・{@code hidden=false} に戻す。
     *
     * <p>行が存在しなくても例外を投げない（冪等・{@code 204}。404 は返さない）。</p>
     */
    @Transactional
    public void deleteLayer(Long userId, String scopeType, Long scopeId) {
        String type = validateScope(scopeType, scopeId);
        long id = normalizedScopeId(type, scopeId);
        checkAffiliation(userId, type, id);

        repository.deleteByUserIdAndScopeTypeAndScopeId(userId, type, id);
    }

    // ------------------------------------------------------------------
    // 認可・バリデーション
    // ------------------------------------------------------------------

    /**
     * 呼び出し本人が当該スコープに所属していることを検証する。
     *
     * <p>PERSONAL は本人自身のレイヤーであり所属という概念を持たないため検証不要
     * （scopeId=0 であることは {@link #validateScope} が保証済み）。</p>
     */
    private void checkAffiliation(Long userId, String scopeType, long scopeId) {
        if (SCOPE_PERSONAL.equals(scopeType)) {
            return;
        }
        Set<Long> affiliated = accessControlService.findAffiliatedScopeIds(userId, scopeType);
        if (affiliated == null || !affiliated.contains(scopeId)) {
            // 非所属も存在しないIDも同一コード（存在秘匿・ID 総当たり探索の防止）。
            throw new BusinessException(ScheduleErrorCode.CALENDAR_LAYER_NOT_MEMBER);
        }
    }

    /** scopeType / scopeId の形を検証し、正規化した scopeType を返す。 */
    private String validateScope(String scopeType, Long scopeId) {
        if (scopeType == null) {
            throw new BusinessException(ScheduleErrorCode.CALENDAR_LAYER_INVALID_SCOPE);
        }
        switch (scopeType) {
            case SCOPE_PERSONAL -> {
                if (scopeId == null || scopeId != PERSONAL_SCOPE_ID) {
                    // PERSONAL の scopeId は全境界で 0 固定（R7）。
                    throw new BusinessException(ScheduleErrorCode.CALENDAR_LAYER_INVALID_SCOPE);
                }
                return SCOPE_PERSONAL;
            }
            case SCOPE_TEAM, SCOPE_ORGANIZATION -> {
                if (scopeId == null || scopeId <= 0L) {
                    throw new BusinessException(ScheduleErrorCode.CALENDAR_LAYER_INVALID_SCOPE);
                }
                return scopeType;
            }
            default -> throw new BusinessException(ScheduleErrorCode.CALENDAR_LAYER_INVALID_SCOPE);
        }
    }

    private long normalizedScopeId(String scopeType, Long scopeId) {
        return SCOPE_PERSONAL.equals(scopeType) ? PERSONAL_SCOPE_ID : scopeId;
    }

    /**
     * 色の形式（{@code #RRGGBB}）を検証し大文字へ正規化する。
     *
     * @return 正規化済みの色。{@code null}（変更しない）ならそのまま {@code null}
     */
    private String validateAndNormalizeColor(String color) {
        if (color == null) {
            return null;
        }
        if (!COLOR_PATTERN.matcher(color).matches()) {
            throw new BusinessException(ScheduleErrorCode.CALENDAR_LAYER_INVALID_COLOR);
        }
        return color.toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // 組み立て
    // ------------------------------------------------------------------

    private static String settingKey(String scopeType, Long scopeId) {
        return scopeType + ":" + (scopeId == null ? PERSONAL_SCOPE_ID : scopeId);
    }

    private static List<Long> sortedAsc(Set<Long> ids) {
        List<Long> sorted = new ArrayList<>(ids == null ? Set.<Long>of() : ids);
        sorted.sort(Comparator.naturalOrder());
        return sorted;
    }

    private static String scopeNameKey(String scopeType) {
        return SCOPE_PERSONAL.equals(scopeType) ? PERSONAL_SCOPE_NAME_KEY : null;
    }

    /**
     * 表示名を解決する。PERSONAL は BE が日本語を持たない（FE が {@code scopeNameKey} を翻訳する）ため
     * 種別名をそのまま返す。名前が解決できない場合も種別名で埋める（PATCH 応答を欠かさない）。
     */
    private String scopeDisplayName(String scopeType, long scopeId) {
        if (SCOPE_PERSONAL.equals(scopeType)) {
            return SCOPE_PERSONAL;
        }
        String name = nameResolverService.resolveScopeName(scopeType, scopeId);
        return name != null ? name : scopeType;
    }

    private String scopeIconUrl(String scopeType, long scopeId) {
        if (SCOPE_PERSONAL.equals(scopeType)) {
            return null;
        }
        return nameResolverService.resolveIconUrl(scopeType, scopeId);
    }

    /**
     * レイヤー1件の応答を組み立てる。設定行の色が {@code null}（未設定）なら自動色へフォールバックし、
     * {@code colorSource} も {@code LAYER_AUTO} になる（§3.4 の優先1 or 4）。
     */
    private CalendarLayerResponse toResponse(String scopeType, long scopeId, String scopeName,
                                             String scopeNameKey, String scopeIconUrl,
                                             UserCalendarLayerSettingEntity setting) {
        String userColor = setting != null ? setting.getColor() : null;
        boolean hidden = setting != null && Boolean.TRUE.equals(setting.getHidden());

        String color = userColor != null ? userColor : CalendarLayerAutoColor.resolve(scopeType, scopeId);
        CalendarColorSource source = userColor != null
                ? CalendarColorSource.LAYER_USER
                : CalendarColorSource.LAYER_AUTO;

        return new CalendarLayerResponse(scopeType, scopeId, scopeName, scopeNameKey,
                scopeIconUrl, color, source, hidden);
    }
}
