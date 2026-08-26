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
  // useRoleAccess 内の watch(scopeId) は scopeId が「変化」したときのみ発火し、初回マウント時は
  // 走らない。そのため初回権限取得はここで明示的に 1 回呼ぶ（onMounted 呼び出しと内部 watch は
  // 重複ではなく役割分担: 初回=ここ / slug 切替時=内部 watch）。
  // 取得失敗は useRoleAccess が roleName=null（=非管理者扱い）で穏当にフォールバックする。
  // トグルは「表示制御のみ」のため、取得失敗時にトグルが出ないのは安全側（最終認可は BE）。
  void loadPermissions()
})

const isOn = computed(() => store.isAdminLensOn(props.scopeType, props.slug))

function setLens(on: boolean) {
  store.setAdminLens(props.scopeType, props.slug, on)
  dismissOnboardingHint()
}

// --- 初回オンボーディングヒント（§1.5・🟡）---
// ADMIN/DEPUTY が初めてトグルを見たとき、「管理者ビューに切り替え」の小ヒントを 1 回だけ出す。
// localStorage の単一キーで既読管理し、2 回目以降は出さない（再設定で復旧可能な軽量 UI 状態）。
const ONBOARDING_STORAGE_KEY = 'admin-lens-onboarding-seen'
const showOnboardingHint = ref(false)

function dismissOnboardingHint() {
  if (!showOnboardingHint.value) return
  showOnboardingHint.value = false
  if (import.meta.client) {
    try {
      localStorage.setItem(ONBOARDING_STORAGE_KEY, '1')
    } catch {
      // localStorage 不可（プライベートモード等）でも機能は壊さない。次回また出るだけ。
    }
  }
}

onMounted(() => {
  if (!import.meta.client) return
  let seen = false
  try {
    seen = localStorage.getItem(ONBOARDING_STORAGE_KEY) === '1'
  } catch {
    seen = false
  }
  if (!seen) showOnboardingHint.value = true
})

// --- ジェスチャ競合排他＋touch/click 二重発火の根絶（§1.3）---
//
// 【背景・修正前の不具合（検分🔴）】
//   touch デバイスでは touchend の後に「ghost click」が発火する（ブラウザ仕様）。
//   修正前は @click も @touchend も両方 toggle() を呼んでおり、かつスワイプ判定時
//   （touchMoved=true）は onTouchEnd が return するだけで preventDefault しなかったため、
//   後続の ghost click が @click="toggle" を発火させてしまっていた。
//   結果「スワイプしたつもりでトグルが切替わる」（§1.3 が禁じた事故）が起きうる。
//
// 【修正方針 — touch / mouse 経路を明確に二重化し ghost click を無視する】
//   1) touch 由来の操作は onTouchEnd 内で完結させる:
//      - スワイプ判定（touchMoved=true）: e.preventDefault() で後続 ghost click を確実に殺し、
//        toggle() は呼ばない（カルーセルへジェスチャを委譲）。
//      - タップ判定: e.preventDefault() で ghost click を殺してから toggle() を 1 回だけ呼ぶ。
//      いずれの場合も usedTouch=true をセットし、直後に来る @click が touch 由来であることを示す。
//   2) @click ハンドラ（onClick）は usedTouch=true なら何もしない（ghost click を無視）。
//      マウス操作（usedTouch=false）のときのみ toggle() を呼ぶ。
//   これにより「タップ=toggle 1 回・スワイプ=toggle 0 回・ghost click 無視」が成立する。
//
// ※ 実機（iOS Safari / Android Chrome）でのタップ/スワイプ挙動はこの環境では確認不可能。
//   マージ後の QA で実機タップ/スワイプ確認を要実施。ロジックの正しさは下記＋単体テストで担保する。
let touchStartX = 0
let touchStartY = 0
let touchMoved = false
// 直近の操作が touch 由来か。touchend で true にし、後続 ghost click を onClick で無視するためのフラグ。
let usedTouch = false

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
  usedTouch = true
  e.preventDefault()
  if (touchMoved) {
    touchMoved = false
    return
  }
  // タップ判定: どちらのボタンがタップされたか data-lens 属性から取得してレンズを設定する。
  const btn = (e.target as HTMLElement).closest('[data-lens]')
  if (btn) {
    setLens(btn.getAttribute('data-lens') === 'admin')
  }
}

function onClickLens(on: boolean) {
  if (usedTouch) {
    usedTouch = false
    return
  }
  setLens(on)
}
</script>

<template>
  <!-- ADMIN/DEPUTY のときのみ描画（非管理者には DOM ごと存在しない） -->
  <div v-if="isAdminOrDeputy" class="relative inline-flex">
    <div
      role="group"
      :aria-label="$t('adminConsole.lens.toggleAriaLabel')"
      class="inline-flex overflow-hidden rounded-full border border-surface-300 dark:border-surface-600"
      @touchstart.passive="onTouchStart"
      @touchmove.passive="onTouchMove"
      @touchend="onTouchEnd"
    >
      <!-- メンバーボタン -->
      <button
        type="button"
        :aria-pressed="!isOn"
        data-lens="member"
        :data-testid="`admin-lens-member-${scopeType}`"
        class="inline-flex min-h-[44px] items-center gap-1.5 px-3 py-1.5 text-xs font-medium transition-colors"
        :class="
          !isOn
            ? 'bg-primary text-primary-contrast'
            : 'bg-surface-100 text-surface-600 hover:bg-surface-200 dark:bg-surface-800 dark:text-surface-300 dark:hover:bg-surface-700'
        "
        @click="onClickLens(false)"
      >
        <i class="pi pi-user text-sm" aria-hidden="true" />
        <span>{{ $t('adminConsole.lens.member') }}</span>
      </button>
      <!-- 管理者ボタン -->
      <button
        type="button"
        :aria-pressed="isOn"
        data-lens="admin"
        :data-testid="`admin-lens-toggle-${scopeType}`"
        class="inline-flex min-h-[44px] items-center gap-1.5 px-3 py-1.5 text-xs font-medium transition-colors"
        :class="
          isOn
            ? 'bg-primary text-primary-contrast'
            : 'bg-surface-100 text-surface-600 hover:bg-surface-200 dark:bg-surface-800 dark:text-surface-300 dark:hover:bg-surface-700'
        "
        @click="onClickLens(true)"
      >
        <i class="pi pi-shield text-sm" aria-hidden="true" />
        <span>{{ $t('adminConsole.lens.admin') }}</span>
      </button>
    </div>

    <!-- 初回オンボーディングヒント（§1.5・🟡）。localStorage で 1 回だけ。タップで dismiss。 -->
    <div
      v-if="showOnboardingHint"
      role="status"
      :data-testid="`admin-lens-onboarding-${scopeType}`"
      class="absolute right-0 top-full z-20 mt-2 w-max max-w-[14rem] rounded-md bg-surface-900 px-3 py-2 text-xs text-white shadow-lg dark:bg-surface-700"
    >
      <span class="flex items-center gap-2">
        <i class="pi pi-info-circle text-[0.85rem]" aria-hidden="true" />
        {{ $t('adminConsole.lens.onboardingHint') }}
        <button
          type="button"
          class="ml-1 shrink-0 rounded p-0.5 hover:bg-white/20"
          :aria-label="$t('button.close')"
          @click="dismissOnboardingHint"
        >
          <i class="pi pi-times text-[0.7rem]" aria-hidden="true" />
        </button>
      </span>
    </div>
  </div>
</template>
