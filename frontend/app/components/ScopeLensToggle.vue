<script setup lang="ts">
/**
 * 管理者/メンバーレンズトグル — 軽量版（v-model 制御）。
 *
 * DashboardScopeLensToggle の軽量版として、チーム/組織ページ用に作成。
 * - useScopeDashboardStore への結合なし（v-model で制御）
 * - タッチジェスチャ処理なし（カルーセルの外に存在するため不要）
 * - オンボーディングヒントなし
 * - useRoleAccess なし（呼び出し元ページで制御済み）
 *
 * ビジュアルは DashboardScopeLensToggle と同一。
 * i18n キーは既存の adminConsole.lens.member / adminConsole.lens.admin を流用。
 */

const props = defineProps<{
  /** true = 管理者モード, false = メンバーモード */
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

function setLens(on: boolean) {
  emit('update:modelValue', on)
}
</script>

<template>
  <div
    role="group"
    :aria-label="$t('adminConsole.lens.toggleAriaLabel')"
    class="inline-flex overflow-hidden rounded-full border border-surface-300 dark:border-surface-600"
  >
    <!-- メンバーボタン -->
    <button
      type="button"
      :aria-pressed="!props.modelValue"
      data-lens="member"
      data-testid="scope-lens-member"
      class="inline-flex min-h-[44px] items-center gap-1.5 px-3 py-1.5 text-xs font-medium transition-colors"
      :class="
        !props.modelValue
          ? 'bg-primary text-primary-contrast'
          : 'bg-surface-100 text-surface-600 hover:bg-surface-200 dark:bg-surface-800 dark:text-surface-300 dark:hover:bg-surface-700'
      "
      @click="setLens(false)"
    >
      <i class="pi pi-user text-sm" aria-hidden="true" />
      <span>{{ $t('adminConsole.lens.member') }}</span>
    </button>
    <!-- 管理者ボタン -->
    <button
      type="button"
      :aria-pressed="props.modelValue"
      data-lens="admin"
      data-testid="scope-lens-admin"
      class="inline-flex min-h-[44px] items-center gap-1.5 px-3 py-1.5 text-xs font-medium transition-colors"
      :class="
        props.modelValue
          ? 'bg-primary text-primary-contrast'
          : 'bg-surface-100 text-surface-600 hover:bg-surface-200 dark:bg-surface-800 dark:text-surface-300 dark:hover:bg-surface-700'
      "
      @click="setLens(true)"
    >
      <i class="pi pi-shield text-sm" aria-hidden="true" />
      <span>{{ $t('adminConsole.lens.admin') }}</span>
    </button>
  </div>
</template>
