<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    title: string
    size?: 'lg' | 'sm'
    /** 戻るリンクを描画するか。既定 true（付け忘れ防止のためデフォルト ON）。opt-out するページは :back="false" を指定する */
    back?: boolean
    /** 戻り先 URL。未指定なら router.back() */
    backTo?: string
    /** 戻るリンクのラベル。未指定なら BackButton 側の i18n デフォルト（戻る/Back…） */
    backLabel?: string
  }>(),
  {
    size: 'lg',
    back: true,
    backTo: undefined,
    backLabel: undefined,
  },
)

const titleClass = computed(() =>
  props.size === 'sm' ? 'text-2xl font-bold' : 'text-4xl font-bold',
)
</script>

<template>
  <div>
    <!-- 戻るリンクはタイトル行の上に描画する。既存 slot（アクション/バッジ）はタイトル行に並ぶため干渉しない -->
    <BackButton v-if="back" :to="backTo" :label="backLabel" />
    <div class="mb-6 flex items-end gap-3">
      <h1 :class="titleClass">{{ title }}</h1>
      <slot />
    </div>
  </div>
</template>
