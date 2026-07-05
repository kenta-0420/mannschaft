<script setup lang="ts">
/**
 * LP v2: 全プラットフォーム対応「ホーム画面への追加方法」モーダル。
 *
 * iOS / Android / PC(Chrome・Edge) の手順をタブで切り替える。
 * 現在の端末を UA から判定して該当タブを最初に開く（他タブも閲覧可）。
 * iOS 手順は既存 pwa.json(pwa.ios.*) を共用参照する。
 * 既存 IosInstallGuideModal.vue（F12.6・アプリ内利用）は壊さないため LP 専用に新設。
 */
import { usePWAInstall } from '~/composables/usePWAInstall'

const visible = defineModel<boolean>('visible', { default: false })

const { t } = useI18n()
const { isIOS } = usePWAInstall()

type PlatformKey = 'ios' | 'android' | 'desktop'

interface PlatformTab {
  key: PlatformKey
  labelKey: string
  icon: string
  steps: string[]
}

// iOS 手順は既存 pwa.json を共用参照
const iosTab: PlatformTab = {
  key: 'ios',
  labelKey: 'landing.v2.pwa.guide.tab_ios',
  icon: 'pi pi-apple',
  steps: ['pwa.ios.step1', 'pwa.ios.step2', 'pwa.ios.step3', 'pwa.ios.step4'],
}
const androidTab: PlatformTab = {
  key: 'android',
  labelKey: 'landing.v2.pwa.guide.tab_android',
  icon: 'pi pi-android',
  steps: [
    'landing.v2.pwa.guide.android_step1',
    'landing.v2.pwa.guide.android_step2',
    'landing.v2.pwa.guide.android_step3',
    'landing.v2.pwa.guide.android_step4',
  ],
}
const desktopTab: PlatformTab = {
  key: 'desktop',
  labelKey: 'landing.v2.pwa.guide.tab_desktop',
  icon: 'pi pi-desktop',
  steps: [
    'landing.v2.pwa.guide.desktop_step1',
    'landing.v2.pwa.guide.desktop_step2',
    'landing.v2.pwa.guide.desktop_step3',
    'landing.v2.pwa.guide.desktop_step4',
  ],
}
const tabs: PlatformTab[] = [iosTab, androidTab, desktopTab]

function detectPlatform(): PlatformKey {
  if (isIOS.value) return 'ios'
  if (typeof navigator !== 'undefined' && /Android/i.test(navigator.userAgent)) return 'android'
  return 'desktop'
}

const active = ref<PlatformKey>('desktop')

// モーダルを開くたびに現在の端末に合わせたタブを開く
watch(
  visible,
  (v) => {
    if (v) active.value = detectPlatform()
  },
  { immediate: true },
)

const activeTab = computed<PlatformTab>(() => tabs.find((tab) => tab.key === active.value) ?? desktopTab)
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="t('landing.v2.pwa.guide.guide_title')"
    modal
    dismissable-mask
    :style="{ width: '90vw', maxWidth: '32rem' }"
    class="mx-4"
  >
    <div class="flex flex-col gap-4 text-left">
      <!-- プラットフォーム切替タブ -->
      <div
        role="tablist"
        :aria-label="t('landing.v2.pwa.guide.guide_title')"
        class="flex rounded-full border border-surface-200 bg-surface-50 p-1 dark:border-surface-700 dark:bg-surface-900"
      >
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          role="tab"
          :aria-selected="active === tab.key"
          class="flex flex-1 items-center justify-center gap-1.5 rounded-full px-2 py-1.5 text-xs font-semibold transition-colors duration-200"
          :class="active === tab.key
            ? 'bg-primary text-white shadow-sm'
            : 'text-surface-500 hover:text-surface-800 dark:hover:text-surface-200'"
          @click="active = tab.key"
        >
          <i :class="tab.icon" />
          {{ t(tab.labelKey) }}
        </button>
      </div>

      <!-- 手順 -->
      <ol class="space-y-3">
        <li
          v-for="(stepKey, index) in activeTab.steps"
          :key="stepKey"
          class="flex items-start gap-3"
        >
          <span
            class="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-bold text-white"
          >
            {{ index + 1 }}
          </span>
          <span class="pt-0.5 text-sm leading-relaxed text-surface-800 dark:text-surface-100">
            {{ t(stepKey) }}
          </span>
        </li>
      </ol>

      <!-- iOS の補足（Chrome/Firefox からは追加不可） -->
      <p
        v-if="active === 'ios'"
        class="flex items-start gap-2 rounded-lg bg-surface-50 p-3 text-xs text-surface-500 dark:bg-surface-900 dark:text-surface-400"
      >
        <i class="pi pi-info-circle mt-0.5 shrink-0" />
        <span>{{ t('pwa.ios.note') }}</span>
      </p>
    </div>

    <template #footer>
      <Button
        :label="t('landing.v2.pwa.guide.close')"
        severity="secondary"
        outlined
        size="small"
        @click="visible = false"
      />
    </template>
  </Dialog>
</template>
