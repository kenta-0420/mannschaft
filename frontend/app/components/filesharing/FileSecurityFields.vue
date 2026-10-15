<script setup lang="ts">
import type { FileVisibilityRole } from '~/types/filesharing'

/**
 * F05.5 (B/C) フォルダ・ファイル共通のセキュリティ設定フィールド。
 * - minVisibleRole: 最低可視ロール（未指定＝制限なし＝所属者全員）
 * - downloadDisabled: ダウンロード禁止フラグ
 * v-model:minVisibleRole / v-model:downloadDisabled で双方向バインドする。
 */
const minVisibleRole = defineModel<FileVisibilityRole | null>('minVisibleRole', { default: null })
const downloadDisabled = defineModel<boolean>('downloadDisabled', { default: false })

const { t } = useI18n()

// Select 用の選択肢。value=null が「所属者全員（既定）」を表す。
const roleOptions = computed<Array<{ label: string; value: FileVisibilityRole | null }>>(() => [
  { label: t('file_sharing.visibility.all'), value: null },
  { label: t('file_sharing.visibility.SUPPORTERS_AND_ABOVE'), value: 'SUPPORTERS_AND_ABOVE' },
  { label: t('file_sharing.visibility.MEMBERS_AND_ABOVE'), value: 'MEMBERS_AND_ABOVE' },
  { label: t('file_sharing.visibility.ADMINS_AND_ABOVE'), value: 'ADMINS_AND_ABOVE' },
])
</script>

<template>
  <div class="flex flex-col gap-4">
    <!-- 最低可視ロール (B) -->
    <div>
      <label class="mb-1 block text-sm font-medium">{{ t('file_sharing.visibility.label') }}</label>
      <Select
        v-model="minVisibleRole"
        :options="roleOptions"
        option-label="label"
        option-value="value"
        class="w-full"
        data-testid="file-security-visibility"
      />
      <p class="mt-1 text-xs text-surface-500 dark:text-surface-400">
        {{ t('file_sharing.visibility.help') }}
      </p>
    </div>

    <!-- ダウンロード禁止 (C) -->
    <div>
      <div class="flex items-center justify-between gap-3">
        <label class="text-sm font-medium">{{ t('file_sharing.download.disabledLabel') }}</label>
        <ToggleSwitch v-model="downloadDisabled" data-testid="file-security-download-disabled" />
      </div>
      <p class="mt-1 text-xs text-surface-500 dark:text-surface-400">
        {{ t('file_sharing.download.disabledHelp') }}
      </p>
    </div>
  </div>
</template>
