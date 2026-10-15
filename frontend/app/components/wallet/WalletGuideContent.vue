<script setup lang="ts">
const { t, tm } = useI18n()
type StepRecord = Record<string, string>

function resolveSteps(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map((k) => t(`${key}.${k}`))
}

const addCardSteps = computed<string[]>(() => resolveSteps('wallet.wallet_guide.add_card.steps'))
const groupSteps = computed<string[]>(() => resolveSteps('wallet.wallet_guide.groups.steps'))
const presentSteps = computed<string[]>(() => resolveSteps('wallet.wallet_guide.present.steps'))
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('wallet.wallet_guide.description') }}
    </p>

    <!-- カード1: ウォレットとは -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400"
        >
          <i class="pi pi-wallet text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('wallet.wallet_guide.overview.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('wallet.wallet_guide.overview.body') }}
          </p>
          <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <p class="text-sm text-surface-600 dark:text-surface-300">
              <i class="pi pi-info-circle mr-1 text-blue-500" />
              {{ t('wallet.wallet_guide.overview.hint') }}
            </p>
          </div>
        </div>
      </div>
    </SectionCard>

    <!-- カード2: カードを登録する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400"
        >
          <i class="pi pi-plus-circle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('wallet.wallet_guide.add_card.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('wallet.wallet_guide.add_card.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in addCardSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード3: グループで整理 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400"
        >
          <i class="pi pi-folder text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('wallet.wallet_guide.groups.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('wallet.wallet_guide.groups.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in groupSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード4: 店舗で提示する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400"
        >
          <i class="pi pi-qrcode text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('wallet.wallet_guide.present.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('wallet.wallet_guide.present.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in presentSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
