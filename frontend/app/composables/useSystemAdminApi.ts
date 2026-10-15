import { useSystemAdminContent } from './system-admin/useSystemAdminContent'
import { useSystemAdminOperations } from './system-admin/useSystemAdminOperations'
import { useSystemAdminDashboard } from './system-admin/useSystemAdminDashboard'
import { useSystemAdminModeration } from './system-admin/useSystemAdminModeration'

/**
 * システム管理者向け API のファサード。
 *
 * 実体は責務別の 4 つのサブ composable に分割済み。
 * 公開関数の名前・シグネチャは分割前と完全互換のため、呼び出し側コードを変更する必要はない。
 *
 * - {@link useSystemAdminContent} お知らせ / テンプレート / 壁紙 / アクティビティテンプレ / ギャラリー
 * - {@link useSystemAdminOperations} 機能フラグ / メンテナンス / モジュール / バッチ / ベータ制限
 * - {@link useSystemAdminDashboard} ダッシュボード / 通知統計 / レポート / 課金 / エラーレポート / Stripe
 * - {@link useSystemAdminModeration} モデレーション / 違反 / 警告再審査 / yabai / 大会 / 安否確認
 */
export function useSystemAdminApi() {
  const content = useSystemAdminContent()
  const operations = useSystemAdminOperations()
  const dashboard = useSystemAdminDashboard()
  const moderation = useSystemAdminModeration()

  return {
    // Announcements
    getAnnouncements: content.getAnnouncements,
    createAnnouncement: content.createAnnouncement,
    updateAnnouncement: content.updateAnnouncement,
    deleteAnnouncement: content.deleteAnnouncement,
    publishAnnouncement: content.publishAnnouncement,
    // Feature Flags
    getFeatureFlags: operations.getFeatureFlags,
    updateFeatureFlag: operations.updateFeatureFlag,
    // Maintenance Schedules
    getMaintenanceSchedules: operations.getMaintenanceSchedules,
    getMaintenanceSchedule: operations.getMaintenanceSchedule,
    createMaintenanceSchedule: operations.createMaintenanceSchedule,
    updateMaintenanceSchedule: operations.updateMaintenanceSchedule,
    deleteMaintenanceSchedule: operations.deleteMaintenanceSchedule,
    activateMaintenanceSchedule: operations.activateMaintenanceSchedule,
    completeMaintenanceSchedule: operations.completeMaintenanceSchedule,
    // Templates
    createTemplate: content.createTemplate,
    updateTemplate: content.updateTemplate,
    deleteTemplate: content.deleteTemplate,
    // Modules
    getModules: operations.getModules,
    getModule: operations.getModule,
    updateModuleLevelAvailability: operations.updateModuleLevelAvailability,
    updateModulePaidPlan: operations.updateModulePaidPlan,
    updateModuleActive: operations.updateModuleActive,
    // Dashboard
    getDashboardOrganizations: dashboard.getDashboardOrganizations,
    freezeOrganization: dashboard.freezeOrganization,
    unfreezeOrganization: dashboard.unfreezeOrganization,
    getDashboardTeams: dashboard.getDashboardTeams,
    getDashboardUsers: dashboard.getDashboardUsers,
    // Batch Logs
    getBatchLogs: operations.getBatchLogs,
    // Notification Stats
    getNotificationStats: dashboard.getNotificationStats,
    // Moderation
    getModerationDashboard: moderation.getModerationDashboard,
    getModerationSettings: moderation.getModerationSettings,
    getModerationSettingsHistory: moderation.getModerationSettingsHistory,
    updateModerationSetting: moderation.updateModerationSetting,
    createModerationTemplate: moderation.createModerationTemplate,
    updateModerationTemplate: moderation.updateModerationTemplate,
    deleteModerationTemplate: moderation.deleteModerationTemplate,
    // Reports
    getReports: dashboard.getReports,
    // Promotion Billing
    getPromotionBilling: dashboard.getPromotionBilling,
    // Affiliate Configs
    getAffiliateConfigs: moderation.getAffiliateConfigs,
    createAffiliateConfig: moderation.createAffiliateConfig,
    updateAffiliateConfig: moderation.updateAffiliateConfig,
    deleteAffiliateConfig: moderation.deleteAffiliateConfig,
    toggleAffiliateConfig: moderation.toggleAffiliateConfig,
    // Tournament Presets
    getTournamentPresets: moderation.getTournamentPresets,
    createTournamentPreset: moderation.createTournamentPreset,
    getTournamentPreset: moderation.getTournamentPreset,
    updateTournamentPreset: moderation.updateTournamentPreset,
    deleteTournamentPreset: moderation.deleteTournamentPreset,
    // Safety Checks
    getSafetyCheckPresets: moderation.getSafetyCheckPresets,
    createSafetyCheckPreset: moderation.createSafetyCheckPreset,
    updateSafetyCheckPreset: moderation.updateSafetyCheckPreset,
    deleteSafetyCheckPreset: moderation.deleteSafetyCheckPreset,
    getSafetyCheckTemplates: moderation.getSafetyCheckTemplates,
    createSafetyCheckTemplate: moderation.createSafetyCheckTemplate,
    updateSafetyCheckTemplate: moderation.updateSafetyCheckTemplate,
    deleteSafetyCheckTemplate: moderation.deleteSafetyCheckTemplate,
    // Template Wallpapers
    getTemplateWallpapers: content.getTemplateWallpapers,
    createTemplateWallpaper: content.createTemplateWallpaper,
    deleteTemplateWallpaper: content.deleteTemplateWallpaper,
    // Activity Template Presets
    getActivityTemplatePresets: content.getActivityTemplatePresets,
    createActivityTemplatePreset: content.createActivityTemplatePreset,
    updateActivityTemplatePreset: content.updateActivityTemplatePreset,
    deleteActivityTemplatePreset: content.deleteActivityTemplatePreset,
    // Gallery
    regenerateThumbnails: content.regenerateThumbnails,
    // Timeline Digest
    getTimelineDigestUsage: dashboard.getTimelineDigestUsage,
    // User Violations
    getUserViolations: moderation.getUserViolations,
    unflagYabaiUser: moderation.unflagYabaiUser,
    // Warning Re-reviews
    getWarningReReviews: moderation.getWarningReReviews,
    reviewWarningReReview: moderation.reviewWarningReReview,
    escalateWarningReReview: moderation.escalateWarningReReview,
    // Yabai Unflag Requests
    getUnflagRequests: moderation.getUnflagRequests,
    reviewUnflagRequest: moderation.reviewUnflagRequest,
    // Stripe Admin
    reconcileStripePayment: dashboard.reconcileStripePayment,
    // Error Reports (F12.5)
    getErrorReports: dashboard.getErrorReports,
    getErrorReport: dashboard.getErrorReport,
    updateErrorReport: dashboard.updateErrorReport,
    bulkUpdateErrorReports: dashboard.bulkUpdateErrorReports,
    getErrorReportStats: dashboard.getErrorReportStats,
    // Beta Restriction (F00.6)
    getBetaRestrictionConfig: operations.getBetaRestrictionConfig,
    updateBetaRestrictionConfig: operations.updateBetaRestrictionConfig,
  }
}
