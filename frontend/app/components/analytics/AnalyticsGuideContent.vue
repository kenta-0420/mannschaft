<template>
  <div class="space-y-4">
    <!-- セクション1: アクセス解析とは -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-chart-bar text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('analytics.guide.what.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('analytics.guide.what.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- セクション2: 計測されるデータ -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-eye text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('analytics.guide.metrics.title') }}
          </h2>
          <ul class="space-y-2 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-green-500" aria-hidden="true" />
              <span>{{ $t('analytics.guide.metrics.total_views') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-green-500" aria-hidden="true" />
              <span>{{ $t('analytics.guide.metrics.unique_visitors') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-green-500" aria-hidden="true" />
              <span>{{ $t('analytics.guide.metrics.member_views') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-green-500" aria-hidden="true" />
              <span>{{ $t('analytics.guide.metrics.guest_views') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- セクション3: データの更新タイミング -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-clock text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('analytics.guide.timing.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('analytics.guide.timing.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- セクション4: グラフの見方 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-chart-line text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('analytics.guide.chart.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('analytics.guide.chart.body') }}
          </p>
          <div class="grid gap-3 sm:grid-cols-2">
            <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
              <p class="mb-1 text-xs font-semibold text-surface-700 dark:text-surface-200">
                {{ $t('analytics.guide.chart.daily_title') }}
              </p>
              <p class="text-xs text-surface-500 dark:text-surface-400">
                {{ $t('analytics.guide.chart.daily_desc') }}
              </p>
            </div>
            <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
              <p class="mb-1 text-xs font-semibold text-surface-700 dark:text-surface-200">
                {{ $t('analytics.guide.chart.monthly_title') }}
              </p>
              <p class="text-xs text-surface-500 dark:text-surface-400">
                {{ $t('analytics.guide.chart.monthly_desc') }}
              </p>
            </div>
          </div>
        </div>
      </div>
    </SectionCard>

    <!-- セクション5: 人気コンテンツ -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400">
          <i class="pi pi-list text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('analytics.guide.top_content.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('analytics.guide.top_content.body') }}
          </p>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
