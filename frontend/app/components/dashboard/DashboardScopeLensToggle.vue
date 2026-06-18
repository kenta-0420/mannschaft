<script setup lang="ts">
/**
 * F10.1.1 L1 管理者レンズトグル（メンバー / 管理者の 2 値切替）。
 *
 * 設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §1.2 / §1.3 / §1.5
 *
 * - **ADMIN/DEPUTY（および SYSTEM_ADMIN）のときのみ DOM ごと描画**する（`useRoleAccess.isAdminOrDeputy`）。
 *   非管理者には存在しない（FE 表示制御。最終認可は BE 側の `checkAdminOrAbove` が担保）。
 * - `role="switch"` / `aria-checked` のトグルボタン。タップ域は最小 44×44px（モバイル誤タップ防止）。
 * - **ジェスチャ競合排他（§1.3）**: トグルはタグ行右端（DashboardScopeCarousel のスワイプ領域内）に置くが、
 *   `touchstart` では押下状態にせず、`touchend` 時の移動量が閾値未満（= タップ）のときのみ発火する。
 *   横方向に閾値以上ドラッグした場合はカルーセルのパネル送りが優先され、トグルは発火しない。
 *   閾値は F22.1 カルーセル（DashboardScopeCarousel）の方針に整合（横移動 8px 超 かつ |Δx| > |Δy|*1.5）。
 * - メンバー視点へ戻る導線（§1.5）: 管理者レンズ中も常時表示され、再タップでメンバーへ戻れる。
 */
import type { ScopeTabType } from '~/types/dashboard-scope'

const props = defineProps<{
  scopeType: ScopeTabType
  /** スコープの slug（adminLens の scopeKey 用・useRoleAccess の scopeId 用）。 */
  slug: string
}>()

const store = useScopeDashboardStore()

// useRoleAccess は scopeType を 'team' | 'organization'（小文字）で受ける。
const roleScope = computed<'team' | 'organization'>(() =>
  props.scopeType === 'TEAM' ? 'team' : 'organization',
)
const slugRef = computed(() => props.slug)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess(roleScope.value, slugRef)

onMounted(() => {
  // 取得失敗は useRoleAccess が roleName=null（=非管理者扱い）で穏当にフォールバックする。
  // トグルは「表示制御のみ」のため、取得失敗時にトグルが出ないのは安全側（最終認可は BE）。
  void loadPermissions()
})

const isOn = computed(() => store.isAdminLensOn(props.scopeType, props.slug))

function toggle() {
  store.setAdminLens(props.scopeType, props.slug, !isOn.value)
}

// --- ジェスチャ競合排他（§1.3）---
// touchstart の座標を保持し、touchend での移動量が閾値未満のときだけ toggle を発火する。
// 横方向に大きく動いた場合はカルーセルの swipe を優先し、トグルは発火しない。
let touchStartX = 0
let touchStartY = 0
let touchMoved = false

function onTouchStart(e: TouchEvent) {
  const touch = e.touches[0]
  if (!touch) return
  touchStartX = touch.clientX
  touchStartY = touch.clientY
  touchMoved = false
}

function onTouchMove(e: TouchEvent) {
  const touch = e.touches[0]
  if (!touch) return
  const dx = Math.abs(touch.clientX - touchStartX)
  const dy = Math.abs(touch.clientY - touchStartY)
  // F22.1 カルーセルの横ロック条件（横 8px 超 かつ |Δx| > |Δy|*1.5）に整合。
  // これを満たしたら「スワイプ」とみなし、touchend でトグルを発火しない。
  if (dx > 8 && dx > dy * 1.5) {
    touchMoved = true
  }
}

function onTouchEnd(e: TouchEvent) {
  if (touchMoved) {
    // 横スワイプ判定: カルーセルへジェスチャを委譲し、トグルは発火しない。
    touchMoved = false
    return
  }
  // タップ判定: 既定の click 発火（後続の click イベント）と二重発火しないよう、
  // ここで preventDefault して click を抑止しつつ自前で toggle する。
  e.preventDefault()
  toggle()
}
</script>

<template>
  <!-- ADMIN/DEPUTY のときのみ描画（非管理者には DOM ごと存在しない） -->
  <button
    v-if="isAdminOrDeputy"
    type="button"
    role="switch"
    :aria-checked="isOn"
    :aria-label="$t('adminConsole.lens.toggleAriaLabel')"
    :data-testid="`admin-lens-toggle-${scopeType}`"
    class="inline-flex min-h-[44px] min-w-[44px] items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs font-medium transition-colors"
    :class="
      isOn
        ? 'border-primary bg-primary text-primary-contrast'
        : 'border-surface-300 bg-surface-100 text-surface-600 hover:bg-surface-200 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-300 dark:hover:bg-surface-700'
    "
    :title="$t('adminConsole.lens.tooltip')"
    @click="toggle"
    @touchstart.passive="onTouchStart"
    @touchmove.passive="onTouchMove"
    @touchend="onTouchEnd"
  >
    <i
      :class="isOn ? 'pi pi-shield' : 'pi pi-user'"
      class="text-sm"
      aria-hidden="true"
    />
    <span>{{ isOn ? $t('adminConsole.lens.admin') : $t('adminConsole.lens.member') }}</span>
  </button>
</template>
