<script setup lang="ts">
/**
 * カード追加ページ（/wallet/cards/new）の使い方ガイド本体。
 *
 * CardAddGuideModal でラップされて表示される。
 * ProjectGuideContent と同一のカード方式（色付き丸アイコン＋SectionCard）を踏襲。
 */
const { t, tm } = useI18n()
type StepRecord = Record<string, string>

/**
 * 連番ステップ（step1, step2...）を順序付き配列へ正規化する。
 * tm() の値を t() で個別解決することで i18n の補間（{var} など）を処理できる。
 */
function resolveSteps(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map((k) => t(`${key}.${k}`))
}

const easiestSteps = computed<string[]>(() => resolveSteps('wallet.add.help.easiest.steps'))
const formatGuideSteps = computed<string[]>(() => resolveSteps('wallet.add.help.format_guide.steps'))
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('wallet.add.help.description') }}
    </p>

    <!-- カード1: 一番かんたんな手順 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400"
        >
          <i class="pi pi-star text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('wallet.add.help.easiest.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('wallet.add.help.easiest.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in easiestSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード2: バーコード形式が分からない時の早見表 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400"
        >
          <i class="pi pi-question-circle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('wallet.add.help.format_guide.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('wallet.add.help.format_guide.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in formatGuideSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード3: 登録後の動作確認 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400"
        >
          <i class="pi pi-check-circle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('wallet.add.help.verify.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('wallet.add.help.verify.body') }}
          </p>
          <!-- 注意ボックス: 形式変更不可の制約を明示 -->
          <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <p class="text-sm text-surface-600 dark:text-surface-300">
              <i class="pi pi-info-circle mr-1 text-amber-500" aria-hidden="true" />
              {{ t('wallet.add.help.verify.hint') }}
            </p>
          </div>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
