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
    title: 'クラブ・サークル用品をAmazonで',
    description: '練習着・トレーニング器具・チームウェアがお得に揃います。',
    tags: 'ユニフォーム / 備品 / 器具',
  },
  CLINIC: {
    title: 'クリニック備品をAmazonで',
    description: '医療用品・健康グッズ・衛生用品をまとめて調達できます。',
    tags: '医療用品 / 健康器具 / 衛生用品',
  },
  CLASS: {
    title: 'クラス活動用品をAmazonで',
    description: '教材・文具・学習サポートグッズが豊富に揃います。',
    tags: '教材 / 文具 / 学習グッズ',
  },
  COMMUNITY: {
    title: 'コミュニティ用品をAmazonで',
    description: 'イベント用品・日用品・備品をお得にまとめ買いできます。',
    tags: 'イベント用品 / 日用品 / 備品',
  },
  COMPANY: {
    title: 'オフィス用品をAmazonで',
    description: '事務用品・PC周辺機器・備品を効率よく調達できます。',
    tags: '事務用品 / OA機器 / 備品',
  },
  OTHER: {
    title: 'チーム用品をAmazonで',
    description: '活動に役立つアイテムをまとめてお得に揃えましょう。',
    tags: '用品 / 備品 / 消耗品',
  },
  FAMILY: {
    title: '家族用品をAmazonで',
    description: '家族の日常に役立つアイテム・イベント用品が揃います。',
    tags: '日用品 / 育児用品 / イベント用品',
  },
  RESTAURANT: {
    title: '飲食店備品をAmazonで',
    description: '調理器具・食器・厨房備品をまとめてお得に調達できます。',
    tags: '調理器具 / 食器 / 厨房備品',
  },
  BEAUTY: {
    title: '美容・サロン用品をAmazonで',
    description: 'ケア用品・施術備品・消耗品をまとめてお得に揃えられます。',
    tags: 'ケア用品 / 施術備品 / 消耗品',
  },
  VOLUNTEER: {
    title: 'ボランティア用品をAmazonで',
    description: '活動に必要な備品・イベント用品・防災グッズが揃います。',
    tags: '備品 / イベント用品 / 防災グッズ',
  },
  NEIGHBORHOOD: {
    title: '自治会・町内会用品をAmazonで',
    description: '回覧板・掲示物・防犯グッズ・イベント備品をお得に調達できます。',
    tags: '防犯グッズ / 掲示用品 / イベント備品',
  },
  CONDO: {
    title: 'マンション管理組合用品をAmazonで',
    description: '共用部備品・掲示物・点検用品・清掃用具をまとめて調達できます。',
    tags: '共用部備品 / 清掃用具 / 掲示用品',
  },
}

const SCOPE_COPY: Record<string, AdCopy> = {
  organization: {
    title: '組織運営用品をAmazonで',
    description: '運営に必要な備品・用品をまとめてお得に調達できます。',
    tags: '備品 / 事務用品 / 消耗品',
  },
  personal: {
    title: 'おすすめ商品をAmazonで',
    description: 'スポーツ・日用品など活動に役立つアイテムが揃っています。',
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
    href="https://amzn.to/47IUOcC"
    target="_blank"
    rel="noopener sponsored"
    class="group relative flex flex-col overflow-hidden rounded-xl border border-surface-200 bg-surface-0 p-3 shadow-sm transition-all hover:shadow-md dark:border-surface-700 dark:bg-surface-800"
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
        class="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-[#FF9900]/10 transition-colors group-hover:bg-[#FF9900]/20"
      >
        <svg viewBox="0 0 24 24" class="h-5 w-5 fill-[#FF9900]" xmlns="http://www.w3.org/2000/svg">
          <path
            d="M13.958 10.09c0 1.232.029 2.256-.591 3.351-.502.891-1.301 1.439-2.186 1.439-1.214 0-1.922-.924-1.922-2.292 0-2.692 2.415-3.182 4.699-3.182v.684zm3.186 7.705c-.209.189-.512.201-.745.074-1.047-.872-1.234-1.276-1.813-2.106-1.733 1.769-2.96 2.298-5.208 2.298-2.658 0-4.729-1.641-4.729-4.925 0-2.565 1.391-4.309 3.37-5.164 1.715-.754 4.11-.891 5.942-1.099v-.41c0-.753.06-1.642-.383-2.294-.384-.578-1.124-.816-1.775-.816-1.205 0-2.277.618-2.54 1.897-.054.285-.261.567-.547.582l-3.065-.333c-.259-.056-.545-.266-.47-.661C5.945 2.042 8.918 1 11.588 1c1.42 0 3.275.378 4.394 1.458C17.371 3.627 17.25 5.209 17.25 6.934v4.867c0 1.466.607 2.106 1.178 2.894.2.282.244.619-.01.828-.638.532-1.77 1.521-2.39 2.073l-.884-.801zM20.691 19.585c-1.938 1.372-4.75 2.101-7.171 2.101-3.393 0-6.449-1.254-8.759-3.341-.182-.164-.02-.389.199-.261 2.494 1.451 5.579 2.321 8.767 2.321 2.15 0 4.511-.445 6.686-1.367.328-.138.602.215.278.547z"
          />
          <path
            d="M21.525 18.635c-.248-.319-1.637-.151-2.261-.076-.19.023-.219-.143-.048-.263 1.107-.778 2.923-.554 3.135-.293.212.262-.055 2.083-1.096 2.952-.16.134-.312.063-.241-.114.234-.584.759-1.887.511-2.206z"
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

    <!-- 星評価 -->
    <div class="mb-2.5 flex items-center gap-0.5">
      <span v-for="n in 5" :key="n" class="text-xs text-[#FF9900]">★</span>
      <span class="ml-1 text-[11px] text-surface-500">人気商品多数</span>
    </div>

    <!-- CTAボタン -->
    <div class="mt-auto">
      <span
        class="inline-flex w-full items-center justify-center gap-1.5 rounded-lg bg-[#FF9900] px-3 py-1.5 text-xs font-semibold text-white shadow-sm transition-all group-hover:bg-[#e68900] group-hover:shadow-md"
      >
        今すぐチェック
        <i class="pi pi-external-link text-[10px]" />
      </span>
    </div>
  </a>
</template>
