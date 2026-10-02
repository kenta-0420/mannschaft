<script setup lang="ts">
/**
 * LP v2: PWA インストール導線ブロック。
 *
 * 既存の F12.6 実装（usePWAInstall）を再利用しつつ、どの環境でも導線が出るようにする。
 * - canInstall（Chromium でネイティブプロンプト可）: 「ホーム画面に追加」で prompt() ＋「追加方法を見る」モーダル
 * - それ以外（PC Chrome/Edge の未発火・iOS 含む非 installed）: 「ホーム画面への追加方法」モーダル
 * - isInstalled（スタンドアロン起動中）: バッジのみ
 * → インストール済み以外は必ず何らかの導線が出る。
 */
import { usePWAInstall } from '~/composables/usePWAInstall'
import LpPwaGuideModal from '~/components/landing/v2/LpPwaGuideModal.vue'

const { t } = useI18n()
const { canInstall, isInstalled, promptInstall } = usePWAInstall()

const guideVisible = ref(false)
const installing = ref(false)

async function handleInstall() {
  installing.value = true
  try {
    await promptInstall()
  } finally {
    installing.value = false
  }
}

function openGuide() {
  guideVisible.value = true
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

        <!-- インストール済み: バッジのみ -->
        <p
          v-if="isInstalled"
          class="mt-3 inline-flex items-center gap-1.5 text-sm font-medium text-primary"
        >
          <i class="pi pi-check-circle" />
          {{ t('pwa.installed') }}
        </p>

        <!-- ネイティブプロンプト可能: 追加ボタン＋追加方法リンク -->
        <div v-else-if="canInstall" class="mt-4 flex flex-col items-center gap-2">
          <Button
            :label="t('landing.v2.pwa.button')"
            icon="pi pi-plus"
            size="small"
            :loading="installing"
            @click="handleInstall"
          />
          <button
            type="button"
            class="text-xs font-medium text-primary hover:underline"
            @click="openGuide"
          >
            {{ t('landing.v2.pwa.guide.guide_button') }}
          </button>
        </div>

        <!-- 未発火（PC Chrome/Edge・iOS 等）: 必ず「追加方法」モーダル導線を出す -->
        <div v-else class="mt-4">
          <Button
            :label="t('landing.v2.pwa.guide.guide_button')"
            icon="pi pi-mobile"
            size="small"
            outlined
            @click="openGuide"
          />
        </div>
      </div>
    </div>

    <LpPwaGuideModal v-model:visible="guideVisible" />
  </section>
</template>
