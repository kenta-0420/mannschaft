<script setup lang="ts">
/**
 * F19.1 公開ページ用 layout。
 *
 * - 未ログインアクセス可
 * - 認証必要メニュー（通知ベル / サイドバー等）は表示しない
 * - ヘッダー: ロゴ + ログイン / 新規登録 + 言語切替
 * - フッター: 簡素
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §8.1 / §8.6
 */
const { t, locale, setLocale, availableLocales } = useI18n()
const showMobileMenu = ref(false)

const localeLabels: Record<string, string> = {
  ja: '日本語',
  en: 'English',
  zh: '中文',
  ko: '한국어',
  es: 'Español',
  de: 'Deutsch',
}
</script>

<template>
  <div class="min-h-screen bg-white dark:bg-surface-900">
    <!-- スキップリンク -->
    <a
      href="#main-content"
      class="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[100] focus:rounded focus:bg-primary focus:px-4 focus:py-2 focus:text-white"
    >
      {{ t('public.layout.skipToContent') }}
    </a>

    <!-- ヘッダー -->
    <header
      class="sticky top-0 z-50 border-b border-surface-200 bg-white/90 backdrop-blur-sm dark:border-surface-700 dark:bg-surface-900/90"
    >
      <nav
        :aria-label="t('public.layout.siteName')"
        class="mx-auto flex h-16 max-w-6xl items-center justify-between px-4"
      >
        <NuxtLink to="/" class="text-2xl font-bold text-primary">
          {{ t('public.layout.siteName') }}
        </NuxtLink>

        <!-- デスクトップ -->
        <div class="hidden items-center gap-3 md:flex">
          <Select
            :model-value="locale"
            :options="availableLocales"
            :option-label="(l: string) => localeLabels[l] ?? l"
            class="w-32 text-sm"
            size="small"
            @change="setLocale($event.value)"
          />
          <NuxtLink to="/login">
            <Button
              :label="t('public.layout.login')"
              severity="secondary"
              outlined
              size="small"
            />
          </NuxtLink>
          <NuxtLink to="/register">
            <Button :label="t('public.layout.register')" size="small" />
          </NuxtLink>
        </div>

        <!-- モバイル -->
        <Button
          icon="pi pi-bars"
          text
          rounded
          severity="secondary"
          class="md:hidden"
          :aria-label="t('public.layout.openMenu')"
          @click="showMobileMenu = true"
        />
      </nav>
    </header>

    <Drawer v-model:visible="showMobileMenu" position="right" class="w-64">
      <template #header>
        <span class="font-bold text-primary">{{ t('public.layout.siteName') }}</span>
      </template>
      <div class="flex flex-col gap-3 pt-4">
        <NuxtLink to="/login" @click="showMobileMenu = false">
          <Button
            :label="t('public.layout.login')"
            severity="secondary"
            outlined
            class="w-full"
          />
        </NuxtLink>
        <NuxtLink to="/register" @click="showMobileMenu = false">
          <Button :label="t('public.layout.register')" class="w-full" />
        </NuxtLink>
        <div class="border-t border-surface-200 pt-3">
          <Select
            :model-value="locale"
            :options="availableLocales"
            :option-label="(l: string) => localeLabels[l] ?? l"
            class="w-full text-sm"
            @change="setLocale($event.value)"
          />
        </div>
      </div>
    </Drawer>

    <main id="main-content" class="mx-auto max-w-5xl px-4 py-8">
      <slot />
    </main>

    <footer
      class="border-t border-surface-200 bg-surface-50 py-6 text-center text-xs text-surface-400 dark:border-surface-700 dark:bg-surface-800"
    >
      © {{ new Date().getFullYear() }} Mannschaft. All rights reserved.
    </footer>
  </div>
</template>
