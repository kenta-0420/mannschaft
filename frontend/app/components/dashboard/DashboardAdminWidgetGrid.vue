<script setup lang="ts">
/**
 * F10.1.1 L1 管理者レンズ — 管理者向けウィジェットグリッド。
 *
 * 設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §1.4 / §2.2 / §2.3 / §5
 *
 * - F22.1 メンバー向け `DashboardSwipeWidgetGrid` とは**別コンポーネント**に切り出し、管理者ロジックが
 *   メンバー向けグリッドへ混ざるのを防ぐ（02 §1.4 / README §1.1 原則3）。
 * - 本グリッドは管理者レンズ ON のときに `DashboardTeamPanel` / `DashboardOrgPanel` でメンバーグリッドと
 *   シート差替される（v-if="store.isAdminLensOn(...)"）。
 *
 * P3a で点火するウィジェット:
 *   - 承認待ち（ADMIN_*_APPROVALS）: `DashboardAdminApprovalsWidget`（getAdminActionRequired 消費・degraded 区別）。
 *   - 管理コンソール導線（ADMIN_*_CONSOLE）: L2 ハブ（/teams|organizations/[slug]/admin）への遷移カード。
 *
 * P3b 送り（「準備中（近日公開）」プレースホルダ・本グリッドではデータ取得しない）:
 *   team: 予約 / 予算 / メンバー統計 / 業務アラート / 通報 / モジュール
 *   org : 予算 / 支払 / メンバー統計 / 業務アラート / 通報 / ポイントカード
 *   これらは P3b で BE 集約 API（dashboard レスポンスの管理者拡張フィールド等）整備後に点火する。
 *   L1 は読み取り専用グランスのため、点火後も実操作は L2/L3 へ遷移してから行う（02 §5）。
 */
import type { ScopeTabType } from '~/types/dashboard-scope'

const props = defineProps<{
  scopeType: ScopeTabType
  /** スコープの slug（承認待ち API・ハブ導線・メンバー視点へ戻る導線に使用）。 */
  slug: string
}>()

const store = useScopeDashboardStore()

/** L2 管理コンソールハブ（ADMIN_*_CONSOLE 導線）。 */
const consoleRoute = computed(() => {
  const base = props.scopeType === 'TEAM' ? 'teams' : 'organizations'
  return `/${base}/${props.slug}/admin`
})

/**
 * P3b 送りの「準備中」プレースホルダウィジェット（02 §2.2 / §2.3 の表のラベルのみ・データ取得しない）。
 * クリックしても何も起きない（リンク切れにしない）。P3b で BE 集約 API 整備後に点火する。
 */
const placeholderWidgets = computed<{ key: string; labelKey: string; icon: string }[]>(() => {
  if (props.scopeType === 'TEAM') {
    return [
      { key: 'reservations', labelKey: 'adminConsole.lens.widgets.reservations', icon: 'pi pi-calendar-clock' },
      { key: 'budget', labelKey: 'adminConsole.lens.widgets.budget', icon: 'pi pi-wallet' },
      { key: 'members', labelKey: 'adminConsole.lens.widgets.members', icon: 'pi pi-users' },
      { key: 'alert', labelKey: 'adminConsole.lens.widgets.alert', icon: 'pi pi-bell' },
      { key: 'reports', labelKey: 'adminConsole.lens.widgets.reports', icon: 'pi pi-flag' },
      { key: 'modules', labelKey: 'adminConsole.lens.widgets.modules', icon: 'pi pi-th-large' },
    ]
  }
  return [
    { key: 'budget', labelKey: 'adminConsole.lens.widgets.budget', icon: 'pi pi-wallet' },
    { key: 'payments', labelKey: 'adminConsole.lens.widgets.payments', icon: 'pi pi-credit-card' },
    { key: 'members', labelKey: 'adminConsole.lens.widgets.members', icon: 'pi pi-users' },
    { key: 'alert', labelKey: 'adminConsole.lens.widgets.alert', icon: 'pi pi-bell' },
    { key: 'reports', labelKey: 'adminConsole.lens.widgets.reports', icon: 'pi pi-flag' },
    { key: 'pointCards', labelKey: 'adminConsole.lens.widgets.pointCards', icon: 'pi pi-id-card' },
  ]
})

/** メンバー視点へ戻る（02 §1.5）。再タップ相当の導線をグリッド先頭に置く。 */
function backToMember() {
  store.setAdminLens(props.scopeType, props.slug, false)
}
</script>

<template>
  <div :data-testid="`admin-widget-grid-${scopeType}`" class="flex flex-col gap-4">
    <!-- メンバービューに戻るサブリンク（02 §1.5） -->
    <div class="flex justify-end">
      <button
        type="button"
        class="inline-flex items-center gap-1 text-sm text-surface-500 hover:text-primary"
        :data-testid="`admin-lens-back-${scopeType}`"
        @click="backToMember"
      >
        <i class="pi pi-arrow-left text-xs" aria-hidden="true" />
        {{ $t('adminConsole.lens.backToMember') }}
      </button>
    </div>

    <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
      <!-- ① 承認待ち（点火・getAdminActionRequired 消費） -->
      <DashboardAdminApprovalsWidget :scope-type="scopeType" :slug="slug" />

      <!-- ② 管理コンソール導線（点火・L2 ハブへ遷移） -->
      <SectionCard :title="$t('adminConsole.lens.widgets.console')">
        <NuxtLink
          :to="consoleRoute"
          class="flex items-center justify-between hover:text-primary"
          :data-testid="`admin-console-link-${scopeType}`"
        >
          <span class="flex items-center gap-3">
            <i class="pi pi-sliders-h text-2xl text-primary" aria-hidden="true" />
            <span class="text-sm font-medium">{{ $t('adminConsole.lens.openConsole') }}</span>
          </span>
          <i class="pi pi-chevron-right text-xs" aria-hidden="true" />
        </NuxtLink>
      </SectionCard>

      <!-- ③〜 準備中プレースホルダ（P3b 送り・データ取得しない・クリック無効） -->
      <SectionCard
        v-for="w in placeholderWidgets"
        :key="w.key"
        :title="$t(w.labelKey)"
      >
        <div class="flex items-center justify-between opacity-60">
          <i :class="w.icon" class="text-2xl text-surface-400" aria-hidden="true" />
          <span class="rounded-full bg-surface-100 px-2 py-0.5 text-xs text-surface-500 dark:bg-surface-800">
            {{ $t('adminConsole.comingSoon') }}
          </span>
        </div>
      </SectionCard>
    </div>
  </div>
</template>
