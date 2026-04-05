<script setup lang="ts">
const { t } = useI18n()
const { locale, setLocale, availableLocales } = useI18n()
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
      {{ t('landing.layout.skip_to_content') }}
    </a>

    <!-- ヘッダー -->
    <header
      class="sticky top-0 z-50 border-b border-surface-200 bg-white/90 backdrop-blur-sm dark:border-surface-700 dark:bg-surface-900/90"
    >
      <nav :aria-label="t('landing.layout.nav_label')" class="mx-auto flex h-16 max-w-6xl items-center justify-between px-4">
        <NuxtLink to="/" class="text-2xl font-bold text-primary">Mannschaft</NuxtLink>

        <!-- デスクトップナビ -->
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
            <Button :label="t('landing.layout.login')" severity="secondary" outlined size="small" />
          </NuxtLink>
          <NuxtLink to="/register">
            <Button :label="t('landing.layout.register')" size="small" />
          </NuxtLink>
        </div>

        <!-- モバイルハンバーガー -->
        <Button
          icon="pi pi-bars"
          text
          rounded
          severity="secondary"
          class="md:hidden"
          :aria-label="t('landing.layout.open_menu')"
          @click="showMobileMenu = true"
        />
      </nav>
    </header>

    <!-- モバイルメニュー -->
    <Drawer v-model:visible="showMobileMenu" position="right" class="w-64">
      <template #header>
        <span class="font-bold text-primary">Mannschaft</span>
      </template>
      <div class="flex flex-col gap-3 pt-4">
        <NuxtLink to="/login" @click="showMobileMenu = false">
          <Button :label="t('landing.layout.login')" severity="secondary" outlined class="w-full" />
        </NuxtLink>
        <NuxtLink to="/register" @click="showMobileMenu = false">
          <Button :label="t('landing.layout.register')" class="w-full" />
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

    <!-- ページコンテンツ -->
    <main id="main-content">
      <slot />
    </main>

    <!-- フッター -->
    <footer class="border-t border-surface-200 bg-surface-50 py-10 dark:border-surface-700 dark:bg-surface-800">
      <div class="mx-auto max-w-6xl px-4">
        <div class="mb-8 grid grid-cols-2 gap-8 md:grid-cols-4">
          <div>
            <div class="mb-3 text-lg font-bold text-primary">Mannschaft</div>
            <p class="text-sm text-surface-500">{{ t('landing.layout.footer_tagline') }}</p>
          </div>
          <div>
            <div class="mb-3 text-sm font-semibold text-surface-700">{{ t('landing.layout.footer_product') }}</div>
            <ul class="space-y-2 text-sm text-surface-500">
              <li><NuxtLink to="/register" class="hover:text-primary">{{ t('landing.layout.footer_start_free') }}</NuxtLink></li>
              <li><NuxtLink to="/login" class="hover:text-primary">{{ t('landing.layout.login') }}</NuxtLink></li>
              <li><a href="#features" class="hover:text-primary">{{ t('landing.layout.footer_features') }}</a></li>
              <li><a href="#faq" class="hover:text-primary">{{ t('landing.layout.footer_faq') }}</a></li>
            </ul>
          </div>
          <div>
            <div class="mb-3 text-sm font-semibold text-surface-700">{{ t('landing.layout.footer_use_cases') }}</div>
            <ul class="space-y-2 text-sm text-surface-500">
              <li>{{ t('landing.layout.footer_sports') }}</li>
              <li>{{ t('landing.layout.footer_community') }}</li>
              <li>{{ t('landing.layout.footer_enterprise') }}</li>
              <li>{{ t('landing.layout.footer_education') }}</li>
            </ul>
          </div>
          <div>
            <div class="mb-3 text-sm font-semibold text-surface-700">{{ t('landing.layout.footer_legal') }}</div>
            <ul class="space-y-2 text-sm text-surface-500">
              <li><a href="#" class="hover:text-primary">{{ t('landing.layout.footer_terms') }}</a></li>
              <li><a href="#" class="hover:text-primary">{{ t('landing.layout.footer_privacy') }}</a></li>
              <li><a href="#" class="hover:text-primary">{{ t('landing.layout.footer_contact') }}</a></li>
            </ul>
          </div>
        </div>
        <div class="border-t border-surface-200 pt-6 text-center text-xs text-surface-400">
          © {{ new Date().getFullYear() }} Mannschaft. All rights reserved.
        </div>
      </div>
    </footer>
  </div>
</template>
