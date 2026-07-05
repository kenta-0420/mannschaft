<script setup lang="ts">
type ScopeKey = 'personal' | 'team' | 'org'

const { t } = useI18n()

const scopes: { key: ScopeKey; icon: string }[] = [
  { key: 'personal', icon: 'pi pi-user' },
  { key: 'team', icon: 'pi pi-users' },
  { key: 'org', icon: 'pi pi-building' },
]

const active = ref<ScopeKey>('team')

function featureList(scope: ScopeKey): string[] {
  return [0, 1, 2].map((i) => t(`landing.v2.scope.${scope}.f${i}`))
}
</script>

<template>
  <div class="mx-auto mt-12 max-w-4xl">
    <!-- タブ -->
    <div
      role="tablist"
      :aria-label="t('landing.v2.scope.heading')"
      class="mx-auto flex max-w-md rounded-full border border-surface-200 bg-white p-1 dark:border-surface-700 dark:bg-surface-800"
    >
      <button
        v-for="s in scopes"
        :key="s.key"
        type="button"
        role="tab"
        :aria-selected="active === s.key"
        class="flex flex-1 items-center justify-center gap-2 rounded-full px-3 py-2 text-sm font-semibold transition-colors duration-200"
        :class="active === s.key
          ? 'bg-primary text-white shadow-sm'
          : 'text-surface-500 hover:text-surface-800 dark:hover:text-surface-200'"
        @click="active = s.key"
      >
        <i :class="s.icon" />
        {{ t(`landing.v2.scope.tab_${s.key}`) }}
      </button>
    </div>

    <!-- タブ本体 -->
    <Transition name="lp-scope-fade" mode="out-in">
      <div :key="active" class="mt-8 grid items-center gap-8 md:grid-cols-2">
        <!-- 説明＋代表機能 -->
        <div>
          <span
            class="inline-flex items-center gap-1.5 rounded-full bg-primary/10 px-3 py-1 text-xs font-semibold text-primary"
          >
            <i class="pi pi-bookmark-fill text-[0.6rem]" />
            {{ t(`landing.v2.scope.${active}.role_badge`) }}
          </span>
          <p class="mt-3 text-lg font-bold text-surface-900 dark:text-white">
            {{ t(`landing.v2.scope.${active}.tagline`) }}
          </p>
          <ul class="mt-4 space-y-2.5">
            <li
              v-for="(f, i) in featureList(active)"
              :key="i"
              class="flex items-start gap-2.5 text-sm text-surface-600 dark:text-surface-300"
            >
              <i class="pi pi-check-circle mt-0.5 shrink-0 text-primary" />
              <span>{{ f }}</span>
            </li>
          </ul>
        </div>

        <!-- CSSミニUIモック -->
        <div
          class="rounded-2xl border border-surface-200 bg-surface-50 p-3 shadow-sm dark:border-surface-700 dark:bg-surface-800"
        >
          <!-- ウィンドウ風ヘッダー -->
          <div class="mb-3 flex items-center gap-1.5 px-1">
            <span class="h-2.5 w-2.5 rounded-full bg-surface-300 dark:bg-surface-600" />
            <span class="h-2.5 w-2.5 rounded-full bg-surface-300 dark:bg-surface-600" />
            <span class="h-2.5 w-2.5 rounded-full bg-surface-300 dark:bg-surface-600" />
            <span class="ml-2 text-[0.65rem] font-medium text-surface-400">
              {{ t(`landing.v2.scope.${active}.mock_caption`) }}
            </span>
          </div>

          <!-- 個人モック: ウォレット＋予定 -->
          <div v-if="active === 'personal'" class="space-y-2">
            <div class="rounded-lg bg-primary/90 p-3">
              <div class="mb-2 h-2 w-16 rounded bg-white/60" />
              <div class="flex items-center justify-between">
                <div class="h-8 w-8 rounded-md bg-white/80" />
                <div class="space-y-1">
                  <div class="h-1.5 w-14 rounded bg-white/70" />
                  <div class="h-1.5 w-10 rounded bg-white/40" />
                </div>
                <div class="h-6 w-6 rounded-full bg-white/80" />
              </div>
            </div>
            <div class="grid grid-cols-7 gap-1">
              <div
                v-for="d in 7"
                :key="d"
                class="aspect-square rounded"
                :class="d === 3 || d === 6 ? 'bg-primary/70' : 'bg-white dark:bg-surface-700'"
              />
            </div>
          </div>

          <!-- チームモック: シフト表＋出欠バー -->
          <div v-else-if="active === 'team'" class="space-y-2">
            <div class="rounded-lg bg-white p-2 dark:bg-surface-700">
              <div class="grid grid-cols-5 gap-1">
                <div
                  v-for="n in 15"
                  :key="n"
                  class="h-4 rounded"
                  :class="n % 4 === 0 ? 'bg-primary/70' : n % 3 === 0 ? 'bg-primary/30' : 'bg-surface-100 dark:bg-surface-600'"
                />
              </div>
            </div>
            <div class="space-y-1.5 rounded-lg bg-white p-2.5 dark:bg-surface-700">
              <div v-for="(w, i) in [70, 45, 85]" :key="i" class="flex items-center gap-2">
                <div class="h-2.5 w-2.5 rounded-full bg-surface-200 dark:bg-surface-500" />
                <div class="h-2 flex-1 overflow-hidden rounded-full bg-surface-100 dark:bg-surface-600">
                  <div class="h-full rounded-full bg-primary/70" :style="{ width: `${w}%` }" />
                </div>
              </div>
            </div>
          </div>

          <!-- 組織モック: 議決権バー＋回覧リスト -->
          <div v-else class="space-y-2">
            <div class="space-y-1.5 rounded-lg bg-white p-2.5 dark:bg-surface-700">
              <div v-for="(w, i) in [90, 60, 30]" :key="i" class="flex items-center gap-2">
                <div class="h-2 w-8 rounded bg-surface-200 dark:bg-surface-500" />
                <div class="h-2.5 flex-1 overflow-hidden rounded-full bg-surface-100 dark:bg-surface-600">
                  <div
                    class="h-full rounded-full"
                    :class="i === 0 ? 'bg-primary/80' : 'bg-primary/40'"
                    :style="{ width: `${w}%` }"
                  />
                </div>
              </div>
            </div>
            <div class="space-y-1.5 rounded-lg bg-white p-2.5 dark:bg-surface-700">
              <div v-for="n in 3" :key="n" class="flex items-center gap-2">
                <div class="h-3 w-3 rounded-full border-2 border-primary/60" :class="n < 3 ? 'bg-primary/60' : ''" />
                <div class="h-2 flex-1 rounded bg-surface-100 dark:bg-surface-600" />
                <div class="h-3 w-3 rounded-sm bg-primary/50" />
              </div>
            </div>
          </div>
        </div>
      </div>
    </Transition>

    <!-- 登録数カウンタ（控えめに統合） -->
    <div class="mt-10 border-t border-surface-200 pt-6 dark:border-surface-700">
      <LpStatsCounter />
    </div>
  </div>
</template>

<style scoped>
.lp-scope-fade-enter-active,
.lp-scope-fade-leave-active {
  transition: opacity 0.18s ease;
}
.lp-scope-fade-enter-from,
.lp-scope-fade-leave-to {
  opacity: 0;
}
@media (prefers-reduced-motion: reduce) {
  .lp-scope-fade-enter-active,
  .lp-scope-fade-leave-active {
    transition: none;
  }
}
</style>
