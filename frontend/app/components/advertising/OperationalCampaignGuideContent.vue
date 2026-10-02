<template>
  <div class="space-y-4">
    <!-- セクション1: 運用型キャンペーンとは -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-megaphone text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('advertising.operational_campaigns.guide.what.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('advertising.operational_campaigns.guide.what.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- セクション2: 出稿の流れ -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-list-check text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('advertising.operational_campaigns.guide.flow.title') }}
          </h2>
          <ol class="space-y-2 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary text-xs text-white">1</span>
              <span>{{ $t('advertising.operational_campaigns.guide.flow.step1') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary text-xs text-white">2</span>
              <span>{{ $t('advertising.operational_campaigns.guide.flow.step2') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary text-xs text-white">3</span>
              <span>{{ $t('advertising.operational_campaigns.guide.flow.step3') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary text-xs text-white">4</span>
              <span>{{ $t('advertising.operational_campaigns.guide.flow.step4') }}</span>
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- セクション3: 料金と日予算 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-money-bill text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('advertising.operational_campaigns.guide.pricing.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('advertising.operational_campaigns.guide.pricing.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- セクション4: 状態と操作 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-sync text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('advertising.operational_campaigns.guide.status.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('advertising.operational_campaigns.guide.status.body') }}
          </p>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
