<script setup lang="ts">
/**
 * F12.6 PWA: iOS Safari 向けインストール手順モーダル。
 *
 * iOS は beforeinstallprompt をサポートしないため、
 * 共有シートからホーム画面に追加する手順を図解で案内する。
 */
const visible = defineModel<boolean>('visible', { default: false })

const { t } = useI18n()

// ステップ配列は i18n キーとアイコンをセットで保持。Safari ロゴは番号バッジで代替する。
interface GuideStep {
  labelKey: string
  icon: string
}

const steps: GuideStep[] = [
  { labelKey: 'pwa.ios.step1', icon: 'pi pi-globe' },
  { labelKey: 'pwa.ios.step2', icon: 'pi pi-upload' },
  { labelKey: 'pwa.ios.step3', icon: 'pi pi-plus-circle' },
  { labelKey: 'pwa.ios.step4', icon: 'pi pi-check' },
]

const memoShortcutSteps: GuideStep[] = [
  { labelKey: 'pwa.ios.memo_shortcut_step1', icon: 'pi pi-globe' },
  { labelKey: 'pwa.ios.memo_shortcut_step2', icon: 'pi pi-upload' },
  { labelKey: 'pwa.ios.memo_shortcut_step3', icon: 'pi pi-plus-circle' },
  { labelKey: 'pwa.ios.memo_shortcut_step4', icon: 'pi pi-check' },
]

function close() {
  visible.value = false
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="t('pwa.ios.guide_title')"
    modal
    :style="{ width: '90vw', maxWidth: '500px' }"
    class="ios-install-guide-modal"
  >
    <ol class="space-y-4">
      <li
        v-for="(step, index) in steps"
        :key="step.labelKey"
        class="flex items-start gap-3"
      >
        <div class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary-500 text-sm font-bold text-white">
          {{ index + 1 }}
        </div>
        <div class="flex flex-1 items-center gap-2 pt-1">
          <i :class="step.icon" class="text-lg text-primary-600 dark:text-primary-300" />
          <span class="text-sm text-surface-800 dark:text-surface-100">
            {{ t(step.labelKey) }}
          </span>
        </div>
      </li>
    </ol>

    <!-- ポイっとメモ専用ショートカットのヒント -->
    <Divider />
    <div class="rounded-lg bg-surface-50 p-4 dark:bg-surface-800">
      <p class="mb-3 flex items-center gap-2 text-sm font-semibold text-orange-600 dark:text-orange-400">
        <i class="pi pi-bolt" />
        {{ t('pwa.ios.memo_shortcut_title') }}
      </p>
      <ol class="space-y-2">
        <li
          v-for="(step, index) in memoShortcutSteps"
          :key="step.labelKey"
          class="flex items-start gap-2"
        >
          <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-orange-400 text-xs font-bold text-white">
            {{ index + 1 }}
          </span>
          <span class="text-sm text-surface-700 dark:text-surface-200">{{ t(step.labelKey) }}</span>
        </li>
      </ol>
    </div>

    <template #footer>
      <div class="flex w-full flex-col gap-3">
        <p class="flex items-start gap-2 text-xs text-surface-500 dark:text-surface-400">
          <i class="pi pi-info-circle mt-0.5" />
          <span>{{ t('pwa.ios.note') }}</span>
        </p>
        <div class="flex justify-end">
          <Button
            :label="t('pwa.ios.close')"
            severity="secondary"
            @click="close"
          />
        </div>
      </div>
    </template>
  </Dialog>
</template>
