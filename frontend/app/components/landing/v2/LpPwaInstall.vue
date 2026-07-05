<script setup lang="ts">
/**
 * LP v2: PWA インストール導線ブロック。
 *
 * 既存の F12.6 実装（usePWAInstall / IosInstallGuideModal）を再利用する。
 * - Chromium 系: beforeinstallprompt を捕捉済みなら prompt() を発火
 * - iOS: 共有ボタン→ホーム画面に追加の手順モーダルを表示
 * - スタンドアロン起動中・イベント未発火環境: ボタンは出さず説明文のみ（UIは壊れない）
 */
import { usePWAInstall } from '~/composables/usePWAInstall'
import IosInstallGuideModal from '~/components/pwa/IosInstallGuideModal.vue'

const { t } = useI18n()
const { canInstall, isInstalled, isIOS, promptInstall } = usePWAInstall()

const iosModalVisible = ref(false)
const installing = ref(false)

// 押して意味のあるインストール導線があるときだけボタンを出す
const showInstallButton = computed(() => !isInstalled.value && (canInstall.value || isIOS.value))

async function handleClick() {
  // iOS は beforeinstallprompt 非対応のため手順モーダルへ（既存モーダルを再利用）
  if (isIOS.value && !canInstall.value) {
    iosModalVisible.value = true
    return
  }
  installing.value = true
  try {
    await promptInstall()
  } finally {
    installing.value = false
  }
}
</script>

<template>
  <section id="lp-pwa" aria-labelledby="lp-pwa-heading" class="py-14 dark:bg-surface-900">
    <div class="mx-auto max-w-xl px-4">
      <div
        class="rounded-2xl border border-surface-200 bg-white px-6 py-6 text-center dark:border-surface-700 dark:bg-surface-800"
      >
        <p id="lp-pwa-heading" class="text-base font-bold text-surface-900 dark:text-white">
          <i class="pi pi-mobile mr-1.5 text-primary" />
          <LpWrapText path="landing.v2.pwa.heading_segments" />
        </p>
        <p class="mt-2 text-sm leading-relaxed text-surface-500">
          <LpWrapText path="landing.v2.pwa.desc_segments" />
        </p>

        <!-- インストール導線（環境に応じて出し分け・未発火環境ではボタンなしでも文面が成立する） -->
        <div v-if="showInstallButton" class="mt-4">
          <Button
            :label="t('landing.v2.pwa.button')"
            icon="pi pi-plus"
            size="small"
            outlined
            :loading="installing"
            @click="handleClick"
          />
        </div>
        <p
          v-else-if="isInstalled"
          class="mt-3 inline-flex items-center gap-1.5 text-sm font-medium text-primary"
        >
          <i class="pi pi-check-circle" />
          {{ t('pwa.installed') }}
        </p>
      </div>
    </div>

    <IosInstallGuideModal v-model:visible="iosModalVisible" :show-memo-shortcut="false" />
  </section>
</template>
