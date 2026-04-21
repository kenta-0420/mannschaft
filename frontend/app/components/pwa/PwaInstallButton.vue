<script setup lang="ts">
/**
 * F12.6 PWA: インストール誘導カード。
 *
 * Q&A/ヘルプページの PWA セクション冒頭に配置する想定。
 * Chromium 系は beforeinstallprompt 経由、iOS は手順モーダルへ誘導、
 * インストール済みはバッジ表示、セッション内で dismiss 済みは非表示。
 */
import { usePWAInstall } from '~/composables/usePWAInstall'
import IosInstallGuideModal from '~/components/pwa/IosInstallGuideModal.vue'

const { t } = useI18n()
const notify = useNotification()
const { canInstall, isInstalled, isIOS, isDismissedThisSession, promptInstall, dismissForNow } = usePWAInstall()

const iosModalVisible = ref(false)
const installing = ref(false)

// インストール UI を出す価値があるか（済み・iOS・Chromium で install 可、のいずれか）
const shouldRender = computed(() => {
  if (isDismissedThisSession.value) return false
  if (isInstalled.value) return true
  if (isIOS.value) return true
  return canInstall.value
})

async function handleInstall() {
  installing.value = true
  try {
    const outcome = await promptInstall()
    if (outcome === 'accepted') {
      notify.success(t('pwa.install_success'))
    }
  } finally {
    installing.value = false
  }
}

function openIosGuide() {
  iosModalVisible.value = true
}

function handleDismiss() {
  dismissForNow()
}
</script>

<template>
  <Card v-if="shouldRender" class="pwa-install-card">
    <template #content>
      <div class="flex items-start gap-4">
        <div class="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-primary-100 text-primary-700 dark:bg-primary-900/40 dark:text-primary-300">
          <i class="pi pi-mobile text-2xl" />
        </div>

        <div class="flex-1">
          <!-- インストール済み -->
          <template v-if="isInstalled">
            <div class="flex items-center gap-2 text-green-700 dark:text-green-400">
              <i class="pi pi-check-circle text-lg" />
              <span class="font-semibold">{{ t('pwa.installed') }}</span>
            </div>
          </template>

          <!-- 未インストール（iOS / Chromium 共通の説明） -->
          <template v-else>
            <p class="mb-3 text-sm text-surface-700 dark:text-surface-200">
              {{ t('pwa.install_prompt') }}
            </p>

            <div class="flex flex-wrap gap-2">
              <!-- iOS: 手順モーダルへ -->
              <Button
                v-if="isIOS"
                :label="t('pwa.ios.guide_title')"
                icon="pi pi-apple"
                severity="primary"
                @click="openIosGuide"
              />

              <!-- Chromium 系: ネイティブ prompt -->
              <Button
                v-else-if="canInstall"
                :label="t('pwa.install_button')"
                icon="pi pi-download"
                severity="primary"
                :loading="installing"
                @click="handleInstall"
              />

              <Button
                :label="t('pwa.later')"
                severity="secondary"
                text
                @click="handleDismiss"
              />
            </div>
          </template>
        </div>
      </div>
    </template>
  </Card>

  <IosInstallGuideModal v-model:visible="iosModalVisible" />
</template>
