<script setup lang="ts">
const props = defineProps<{
  scopeType?: 'personal' | 'team' | 'organization'
  scopeTemplate?: string
}>()

interface AdCopy {
  title: string
  description: string
  tags: string
}

const TEMPLATE_COPY: Record<string, AdCopy> = {
  CLUB: {
    title: 'クラブ・サークル用品を楽天で',
    description: '練習着・トレーニング器具・チームウェアがポイントでお得に揃います。',
    tags: 'ユニフォーム / 備品 / 器具',
  },
  CLINIC: {
    title: 'クリニック備品を楽天で',
    description: '医療用品・健康グッズ・衛生用品をポイントを貯めながら調達できます。',
    tags: '医療用品 / 健康器具 / 衛生用品',
  },
  CLASS: {
    title: 'クラス活動用品を楽天で',
    description: '教材・文具・学習サポートグッズが豊富。ポイントもしっかり貯まります。',
    tags: '教材 / 文具 / 学習グッズ',
  },
  COMMUNITY: {
    title: 'コミュニティ用品を楽天で',
    description: 'イベント用品・日用品・備品をポイントを活用してお得にまとめ買いできます。',
    tags: 'イベント用品 / 日用品 / 備品',
  },
  COMPANY: {
    title: 'オフィス用品を楽天で',
    description: '事務用品・PC周辺機器・備品をポイントを貯めながら効率よく調達できます。',
    tags: '事務用品 / OA機器 / 備品',
  },
  OTHER: {
    title: 'チーム用品を楽天で',
    description: '活動に役立つアイテムをポイントを活かしてお得に揃えましょう。',
    tags: '用品 / 備品 / 消耗品',
  },
  FAMILY: {
    title: '家族用品を楽天で',
    description: '家族の日常に役立つアイテム・イベント用品をポイントでお得に揃えましょう。',
    tags: '日用品 / 育児用品 / イベント用品',
  },
  RESTAURANT: {
    title: '飲食店備品を楽天で',
    description: '調理器具・食器・厨房備品をポイントを貯めながらお得に調達できます。',
    tags: '調理器具 / 食器 / 厨房備品',
  },
  BEAUTY: {
    title: '美容・サロン用品を楽天で',
    description: 'ケア用品・施術備品・消耗品をポイントでお得に揃えられます。',
    tags: 'ケア用品 / 施術備品 / 消耗品',
  },
  STORE: {
    title: '店舗・小売用品を楽天で',
    description: '陳列用品・包装資材・POPなど店舗運営に必要なアイテムをポイントでお得に揃えましょう。',
    tags: '陳列用品 / 包装資材 / 店舗備品',
  },
  VOLUNTEER: {
    title: 'ボランティア用品を楽天で',
    description: '活動に必要な備品・イベント用品・防災グッズをポイントでお得に揃えましょう。',
    tags: '備品 / イベント用品 / 防災グッズ',
  },
  NEIGHBORHOOD: {
    title: '自治会・町内会用品を楽天で',
    description: '回覧板・掲示物・防犯グッズ・イベント備品をポイントでお得に調達できます。',
    tags: '防犯グッズ / 掲示用品 / イベント備品',
  },
  CONDO: {
    title: 'マンション管理組合用品を楽天で',
    description: '共用部備品・掲示物・点検用品・清掃用具をポイントを貯めながら調達できます。',
    tags: '共用部備品 / 清掃用具 / 掲示用品',
  },
}

const SCOPE_COPY: Record<string, AdCopy> = {
  organization: {
    title: '組織運営用品を楽天で',
    description: '運営に必要な備品・用品をポイントを貯めながらお得に調達できます。',
    tags: '備品 / 事務用品 / 消耗品',
  },
  personal: {
    title: 'おすすめ商品を楽天で',
    description: 'スポーツ・日用品など活動に役立つアイテムがポイントでお得に揃います。',
    tags: 'スポーツ / 日用品 / 備品',
  },
}

const copy = computed<AdCopy>(() => {
  if (props.scopeTemplate && TEMPLATE_COPY[props.scopeTemplate]) {
    return TEMPLATE_COPY[props.scopeTemplate]!
  }
  if (props.scopeType && SCOPE_COPY[props.scopeType]) {
    return SCOPE_COPY[props.scopeType]!
  }
  return SCOPE_COPY.personal!
})
</script>

<template>
  <a
    href="https://hb.afl.rakuten.co.jp/hsc/52836912.6a09824b.52835f89.0cf8649c/?link_type=hybrid_url&ut=eyJwYWdlIjoic2hvcCIsInR5cGUiOiJoeWJyaWRfdXJsIiwiY29sIjoxLCJjYXQiOiIxMDIiLCJiYW4iOjE1MjQxNTEsImFtcCI6ZmFsc2V9"
    target="_blank"
    rel="noopener sponsored"
    class="group relative flex flex-col overflow-hidden rounded-xl border-2 border-surface-400 bg-surface-0 p-3 shadow-sm transition-all hover:shadow-md dark:border-surface-500 dark:bg-surface-800"
  >
    <!-- PRバッジ -->
    <span
      class="absolute right-2.5 top-2.5 rounded-full bg-surface-100 px-2 py-0.5 text-[10px] font-medium text-surface-400 dark:bg-surface-700"
    >
      PR
    </span>

    <!-- ヘッダー -->
    <div class="mb-2 flex items-center gap-2.5">
      <div
        class="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-[#BF0000]/10 transition-colors group-hover:bg-[#BF0000]/20"
      >
        <svg viewBox="0 0 24 24" class="h-5 w-5 fill-[#BF0000]" xmlns="http://www.w3.org/2000/svg">
          <path
            d="M13.388 11.26h-2.137V8.397h2.137c.853 0 1.46.56 1.46 1.432 0 .871-.607 1.431-1.46 1.431zm4.418-.095c0-2.512-1.802-4.1-4.612-4.1H8.918V17h2.333v-3.607h1.46L14.638 17h2.658l-2.27-3.9c1.607-.507 2.78-1.7 2.78-3.935zM12 2C6.477 2 2 6.477 2 12s4.477 10 10 10 10-4.477 10-10S17.523 2 12 2z"
          />
        </svg>
      </div>
      <div class="min-w-0 flex-1">
        <h3 class="text-sm font-semibold text-surface-700 dark:text-surface-200">
          {{ copy.title }}
        </h3>
        <p class="text-[11px] text-surface-400">{{ copy.tags }}</p>
      </div>
    </div>

    <!-- 説明文 -->
    <p class="mb-2 text-xs text-surface-600 dark:text-surface-300">
      {{ copy.description }}
    </p>

    <!-- ポイントバッジ -->
    <div class="mb-2.5 flex items-center gap-1">
      <span
        class="rounded-full bg-[#BF0000]/10 px-2 py-0.5 text-[11px] font-semibold text-[#BF0000]"
        >ポイント還元あり</span
      >
      <span class="text-[11px] text-surface-500">お買い物マラソン対象</span>
    </div>

    <!-- CTAボタン -->
    <div class="mt-auto">
      <span
        class="inline-flex w-full items-center justify-center gap-1.5 rounded-lg bg-[#BF0000] px-3 py-1.5 text-xs font-semibold text-white shadow-sm transition-all group-hover:bg-[#a00000] group-hover:shadow-md"
      >
        楽天でチェック
        <i class="pi pi-external-link text-[10px]" />
      </span>
    </div>
  </a>
</template>
