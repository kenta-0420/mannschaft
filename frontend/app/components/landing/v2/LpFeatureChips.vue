<script setup lang="ts">
import type { LpFeature } from './LpFeatureDialog.vue'

const { t } = useI18n()

// slug が非 null のものは既存 /features/{slug} ページが実在する（team/calendar/chat/shift/forms/gallery/billing/matching）。
// slug が null のものはモーダルで説明のみ表示し、詳細リンクは出さない。
const features: LpFeature[] = [
  { key: 'team', icon: 'pi pi-users', slug: 'team' },
  { key: 'schedule', icon: 'pi pi-calendar', slug: 'calendar' },
  { key: 'reservation', icon: 'pi pi-bookmark', slug: null },
  { key: 'shift', icon: 'pi pi-clock', slug: 'shift' },
  { key: 'chat', icon: 'pi pi-comments', slug: 'chat' },
  { key: 'timeline', icon: 'pi pi-hashtag', slug: null },
  { key: 'circulation', icon: 'pi pi-inbox', slug: null },
  { key: 'survey', icon: 'pi pi-chart-bar', slug: 'forms' },
  { key: 'files', icon: 'pi pi-folder', slug: 'gallery' },
  { key: 'billing', icon: 'pi pi-credit-card', slug: 'billing' },
  { key: 'matching', icon: 'pi pi-trophy', slug: 'matching' },
  { key: 'safety', icon: 'pi pi-shield', slug: null },
  { key: 'qr', icon: 'pi pi-qrcode', slug: null },
  { key: 'wallet', icon: 'pi pi-wallet', slug: null },
  { key: 'karte', icon: 'pi pi-heart', slug: null },
  { key: 'village', icon: 'pi pi-map', slug: null },
]

const dialogVisible = ref(false)
const selected = ref<LpFeature | null>(null)

function openFeature(f: LpFeature) {
  selected.value = f
  dialogVisible.value = true
}
</script>

<template>
  <section id="lp-features" aria-labelledby="lp-features-heading" class="py-16 dark:bg-surface-900">
    <div class="mx-auto max-w-4xl px-4">
      <div class="mb-8 text-center">
        <h2 id="lp-features-heading" class="text-2xl font-bold text-surface-900 dark:text-white md:text-3xl">
          <LpWrapText path="landing.v2.features.heading_segments" />
        </h2>
        <p class="mt-3 text-surface-500">
          <LpWrapText path="landing.v2.features.subheading_segments" />
        </p>
      </div>

      <div class="flex flex-wrap justify-center gap-2.5">
        <button
          v-for="f in features"
          :key="f.key"
          type="button"
          class="inline-flex items-center gap-2 rounded-full border border-surface-200 bg-white px-4 py-2 text-sm font-medium text-surface-700 transition-all duration-200 hover:-translate-y-0.5 hover:border-primary/50 hover:text-primary hover:shadow-sm dark:border-surface-700 dark:bg-surface-800 dark:text-surface-200"
          @click="openFeature(f)"
        >
          <i :class="[f.icon, 'text-primary']" />
          {{ t(`landing.v2.features.items.${f.key}.title`) }}
        </button>
      </div>

      <!-- 料金の正直な説明 -->
      <p class="mt-6 text-center text-sm text-surface-500">
        <i class="pi pi-info-circle mr-1 text-xs" />
        <LpWrapText path="landing.v2.features.pricing_note_segments" />
      </p>

      <!-- みんなで作っていく（成長中の正直な一言） -->
      <!-- 本文ブロックは中央揃えをやめ左揃えに（中央揃え＋和文で語中改行が起きるため） -->
      <div
        class="mx-auto mt-10 flex max-w-xl items-start gap-3 rounded-2xl border border-dashed border-primary/40 bg-primary/5 px-6 py-5 text-left"
      >
        <i class="pi pi-wrench mt-0.5 shrink-0 text-primary" />
        <div>
          <p class="text-sm font-semibold text-surface-800 dark:text-surface-100">
            {{ t('landing.v2.features.growing_title') }}
          </p>
          <p class="mt-1.5 text-sm leading-relaxed text-surface-500">
            <LpWrapText path="landing.v2.features.growing_note_segments" />
          </p>
        </div>
      </div>
    </div>

    <LpFeatureDialog v-model:visible="dialogVisible" :feature="selected" />
  </section>
</template>
