package com.mannschaft.app.dashboard;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.dashboard.dto.UpdateWidgetSettingsRequest;
import com.mannschaft.app.dashboard.dto.WidgetSettingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ダッシュボードウィジェット設定の管理サービス。
 * ウィジェットの表示/非表示・並び順のCRUDを担当する。
 * レコードが存在しないウィジェットはデフォルト表示として扱い、PUT実行時にUPSERTする遅延作成方式。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class DashboardWidgetService {

    private final DashboardWidgetSettingRepository widgetSettingRepository;
    private final DashboardMapper dashboardMapper;

    /** ウィジェット名のマッピング（将来的にはi18n対応） */
    private static final Map<String, String> WIDGET_NAMES = Map.ofEntries(
            Map.entry("NOTICES", "お知らせ"),
            Map.entry("PLATFORM_ANNOUNCEMENTS", "運営からのお知らせ"),
            Map.entry("UPCOMING_EVENTS", "直近イベント"),
            Map.entry("MY_POSTS", "自分の投稿一覧"),
            Map.entry("UNREAD_THREADS", "未読スレッド"),
            Map.entry("RECENT_ACTIVITY", "最近のアクティビティ"),
            Map.entry("PERFORMANCE_SUMMARY", "パフォーマンスサマリー"),
            Map.entry("PERSONAL_CALENDAR", "個人カレンダー"),
            Map.entry("PERSONAL_TODO", "個人TODO"),
            Map.entry("PERSONAL_PROJECT_PROGRESS", "プロジェクト進捗"),
            Map.entry("CHAT_HUB", "チャットハブ"),
            Map.entry("BILLING_PERSONAL", "課金サマリー（個人）"),
            Map.entry("TEAM_NOTICES", "チームお知らせ"),
            Map.entry("TEAM_UPCOMING_EVENTS", "直近イベント"),
            Map.entry("TEAM_TODO", "チームTODO"),
            Map.entry("TEAM_PROJECT_PROGRESS", "プロジェクト進捗"),
            Map.entry("TEAM_ACTIVITY", "チーム活動サマリー"),
            Map.entry("TEAM_LATEST_POSTS", "最新投稿"),
            Map.entry("TEAM_UNREAD_THREADS", "未読スレッド数"),
            Map.entry("TEAM_MEMBER_ATTENDANCE", "メンバー出欠状況"),
            Map.entry("TEAM_BILLING", "課金サマリー"),
            Map.entry("TEAM_PAGE_VIEWS", "アクセス解析"),
            Map.entry("ORG_TEAM_LIST", "傘下チーム一覧"),
            Map.entry("ORG_NOTICES", "組織お知らせ"),
            Map.entry("ORG_TODO", "組織TODO"),
            Map.entry("ORG_PROJECT_PROGRESS", "プロジェクト進捗"),
            Map.entry("ORG_STATS", "組織統計サマリー"),
            Map.entry("ORG_BILLING", "課金サマリー")
    );

    /**
     * 指定スコープのウィジェット設定一覧を取得する。
     * レコードが存在しないウィジェットはデフォルト値で補完する。
     * ロール制限ウィジェットは isAdmin=false のユーザーには返却しない。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID（PERSONALの場合は0）
     * @param isAdmin   ADMIN/DEPUTY_ADMIN かどうか
     */
    public List<WidgetSettingResponse> getWidgetSettings(Long userId, ScopeType scopeType, Long scopeId, boolean isAdmin) {
        List<DashboardWidgetSettingEntity> saved =
                widgetSettingRepository.findByUserIdAndScopeTypeAndScopeIdOrderBySortOrder(userId, scopeType, scopeId);

        Map<String, DashboardWidgetSettingEntity> savedMap = saved.stream()
                .collect(Collectors.toMap(DashboardWidgetSettingEntity::getWidgetKey, e -> e));

        List<WidgetKey> scopeWidgets = WidgetKey.forScope(scopeType);
        List<WidgetSettingResponse> result = new ArrayList<>();

        for (WidgetKey wk : scopeWidgets) {
            // ロール制限ウィジェットは権限なしユーザーに返却しない
            if (wk.isRoleRestricted() && !isAdmin) {
                continue;
            }

            String name = WIDGET_NAMES.getOrDefault(wk.name(), wk.name());
            // TODO: モジュール有効/無効の判定はF01.3のテンプレートモジュールシステムと連携して実装
            boolean moduleEnabled = true;
            String disabledReason = null;

            DashboardWidgetSettingEntity entity = savedMap.get(wk.name());
            if (entity != null) {
                result.add(dashboardMapper.toWidgetSettingResponse(entity, name, moduleEnabled, disabledReason));
            } else {
                result.add(dashboardMapper.toDefaultWidgetSettingResponse(wk, name, moduleEnabled, disabledReason));
            }
        }

        return result;
    }

    /**
     * ウィジェット設定を一括更新する（UPSERT）。
     * リクエストに含まれないウィジェットの設定は変更しない（差分更新）。
     */
    @Transactional
    public List<WidgetSettingResponse> updateWidgetSettings(Long userId, UpdateWidgetSettingsRequest request) {
        ScopeType scopeType = parseScopeType(request.getScopeType());
        Long scopeId = resolveScopeId(scopeType, request.getScopeId());

        for (UpdateWidgetSettingsRequest.WidgetSettingItem item : request.getWidgets()) {
            validateWidgetKey(item.getWidgetKey(), scopeType);
            if (item.getSortOrder() < 0) {
                throw new BusinessException(DashboardErrorCode.DASHBOARD_015);
            }

            WidgetKey wk;
            try {
                wk = WidgetKey.valueOf(item.getWidgetKey());
            } catch (IllegalArgumentException e) {
                // ロール制限ウィジェットを権限なしユーザーが送信した場合は無視
                continue;
            }

            // ロール制限ウィジェットの設定は無視（エラーにしない）
            if (wk.isRoleRestricted()) {
                // TODO: ロール判定。現時点では保存を許可する
            }

            widgetSettingRepository.findByUserIdAndScopeTypeAndScopeIdAndWidgetKey(
                    userId, scopeType, scopeId, item.getWidgetKey()
            ).ifPresentOrElse(
                    existing -> {
                        existing.changeVisibility(item.getIsVisible());
                        existing.changeSortOrder(item.getSortOrder());
                    },
                    () -> {
                        DashboardWidgetSettingEntity newEntity = DashboardWidgetSettingEntity.builder()
                                .userId(userId)
                                .scopeType(scopeType)
                                .scopeId(scopeId)
                                .widgetKey(item.getWidgetKey())
                                .isVisible(item.getIsVisible())
                                .sortOrder(item.getSortOrder())
                                .build();
                        widgetSettingRepository.save(newEntity);
                    }
            );
        }

        // TODO: isAdmin判定。現時点では true で返却
        return getWidgetSettings(userId, scopeType, scopeId, true);
    }

    /**
     * 指定スコープのウィジェット設定を全削除しデフォルトにリセットする。
     */
    @Transactional
    public void resetWidgetSettings(Long userId, ScopeType scopeType, Long scopeId) {
        Long resolvedScopeId = resolveScopeId(scopeType, scopeId);
        widgetSettingRepository.deleteByUserIdAndScopeTypeAndScopeId(userId, scopeType, resolvedScopeId);
        log.info("ウィジェット設定リセット userId={}, scopeType={}, scopeId={}", userId, scopeType, resolvedScopeId);
    }

    /**
     * スコープタイプ文字列をEnumにパースする。
     */
    ScopeType parseScopeType(String scopeTypeStr) {
        try {
            return ScopeType.valueOf(scopeTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(DashboardErrorCode.DASHBOARD_014);
        }
    }

    /**
     * スコープIDを解決する。PERSONALの場合は0を返す。
     */
    Long resolveScopeId(ScopeType scopeType, Long scopeId) {
        if (scopeType == ScopeType.PERSONAL) {
            return 0L;
        }
        if (scopeId == null) {
            throw new BusinessException(DashboardErrorCode.DASHBOARD_014);
        }
        return scopeId;
    }

    /**
     * ウィジェットキーが指定スコープで有効か検証する。
     */
    private void validateWidgetKey(String widgetKeyStr, ScopeType scopeType) {
        WidgetKey wk;
        try {
            wk = WidgetKey.valueOf(widgetKeyStr);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(DashboardErrorCode.DASHBOARD_001);
        }

        if (wk.getScopeType() != scopeType) {
            throw new BusinessException(DashboardErrorCode.DASHBOARD_002);
        }
    }
}
