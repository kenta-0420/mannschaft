<script setup lang="ts">
const { t, tm, rt } = useI18n()

// 見出しは意味句単位のセグメント配列で折返し制御（末尾セグメントがアクセント色）。
// 配列ロケールは tm() + rt() で解決する（t() は文字列しか返せない）
const titleSegments = computed(() => {
  // tm() は compiled message AST を返すため raw は unknown で受け、rt() で文字列化する
  const raw: unknown = tm('landing.v2.hero.title_segments')
  if (Array.isArray(raw)) return raw.map((seg) => rt(seg as Parameters<typeof rt>[0]))
  return []
})
</script>

<template>
  <section id="lp-hero" aria-labelledby="lp-hero-heading" class="pt-20 pb-16 dark:bg-surface-900">
    <div class="mx-auto max-w-5xl px-4 text-center">
      <div
        class="mb-5 inline-flex items-center gap-2 rounded-full border border-primary/30 bg-white px-4 py-1.5 text-sm font-medium text-primary shadow-sm dark:bg-surface-800"
      >
        <i class="pi pi-sparkles text-xs" />
        {{ t('landing.v2.hero.badge') }}
      </div>

      <h1
        id="lp-hero-heading"
        class="mx-auto mb-5 max-w-3xl text-3xl font-black leading-tight tracking-tight text-surface-900 dark:text-white md:text-5xl"
      >
        <span
          v-for="(seg, i) in titleSegments"
          :key="i"
          class="inline-block"
          :class="i === titleSegments.length - 1 ? 'text-primary' : ''"
        >{{ seg }}</span>
      </h1>

      <p class="mx-auto mb-8 max-w-2xl text-lg leading-relaxed text-surface-600 dark:text-surface-300">
        <LpWrapText path="landing.v2.hero.subtitle_segments" />
      </p>

      <div class="flex flex-col items-center justify-center gap-2">
        <NuxtLink to="/register">
          <Button
            :label="t('landing.v2.hero.cta_register')"
            icon="pi pi-arrow-right"
            icon-pos="right"
            size="large"
            class="px-10"
          />
        </NuxtLink>
        <p class="text-sm text-surface-500">
          {{ t('landing.v2.hero.login_prompt') }}
          <NuxtLink to="/login" class="font-medium text-primary hover:underline">
            {{ t('landing.v2.hero.login_link') }}
          </NuxtLink>
        </p>
        <p class="text-xs text-surface-400">
          {{ t('landing.v2.hero.no_download_note') }}
        </p>

        <!-- 未ログイン者向け: 公開チーム／組織の閲覧導線（主CTAを喰わない控えめなテキストリンク） -->
        <div class="mt-1 flex flex-wrap items-center justify-center gap-x-4 gap-y-1 text-sm">
          <NuxtLink
            to="/discover/teams"
            class="font-medium text-surface-500 hover:text-primary hover:underline dark:text-surface-400"
          >
            {{ t('landing.hero.discoverTeams') }}
            <i class="pi pi-arrow-right text-xs" />
          </NuxtLink>
          <NuxtLink
            to="/discover/organizations"
            class="font-medium text-surface-500 hover:text-primary hover:underline dark:text-surface-400"
          >
            {{ t('landing.hero.discoverOrganizations') }}
            <i class="pi pi-arrow-right text-xs" />
          </NuxtLink>
        </div>
      </div>

      <!-- スコープ切替（個人／チーム／組織）＋登録数カウンタ -->
      <LpScopeTabs />
    </div>
  </section>
</template>
