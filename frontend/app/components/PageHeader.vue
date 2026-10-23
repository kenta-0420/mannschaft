<script setup lang="ts">
import { Comment, Fragment, Text, type VNode } from 'vue'

const props = withDefaults(
  defineProps<{
    title: string
    /** タイトル行の下に表示する説明。#subtitle があればそちらを優先する。 */
    subtitle?: string
    size?: 'lg' | 'sm'
    /** 戻るリンクを描画するか。既定 true（付け忘れ防止のためデフォルト ON）。opt-out するページは :back="false" を指定する */
    back?: boolean
    /** 戻り先 URL。未指定なら router.back() */
    backTo?: string
    /** 戻るリンクのラベル。未指定なら BackButton 側の i18n デフォルト（戻る/Back…） */
    backLabel?: string
    /** 「？使い方」ボタンを描画するか。既定 false（明示的に出すページのみ opt-in する） */
    help?: boolean
    /** 使い方ボタンのラベル。未指定なら i18n button.help（使い方/How to use…） */
    helpLabel?: string
  }>(),
  {
    size: 'lg',
    subtitle: undefined,
    back: true,
    backTo: undefined,
    backLabel: undefined,
    help: false,
    helpLabel: undefined,
  },
)

// 使い方ボタンのクリックを呼び出し側へ通知する
const emit = defineEmits<{ help: [] }>()

const { t } = useI18n()
const slots = useSlots()

// v-if=false のコメントだけの slot では空の説明段落を作らない。
function hasSubtitleContent(nodes: VNode[]): boolean {
  return nodes.some(node => {
    if (node.type === Comment) return false
    if (node.type === Text) return String(node.children ?? '').trim().length > 0
    if (node.type === Fragment) return hasSubtitleContent(node.children as VNode[])
    return true
  })
}
const hasSubtitle = () => slots.subtitle
  ? hasSubtitleContent(slots.subtitle())
  : Boolean(props.subtitle)

// フォールスルー属性（class 等）は外側ラッパではなくタイトル行へ束ねる。
// これにより、従来 <PageHeader class="..."> が当たっていた要素（タイトル行の
// flex コンテナ）が戻るボタン統合の前後で変わらず、既存ページのレイアウトを維持する。
defineOptions({ inheritAttrs: false })

const titleClass = computed(() =>
  props.size === 'sm'
    ? 'text-xl font-bold tracking-tight text-surface-900 dark:text-surface-200'
    : 'text-3xl font-bold tracking-tight text-surface-900 dark:text-surface-200',
)
</script>

<template>
  <div class="min-w-0">
    <!-- 戻るリンクはタイトル行の上に描画する。既存 slot（アクション/バッジ）はタイトル行に並ぶため干渉しない -->
    <BackButton v-if="back" :to="backTo" :label="backLabel" />
    <div :class="['flex items-end gap-3 max-md:flex-wrap', { 'mb-5': !hasSubtitle() }]" v-bind="$attrs">
      <h1 class="min-w-0 [overflow-wrap:anywhere]" :class="titleClass">{{ title }}</h1>
      <!-- 既定スロットはタイトルのすぐ右（インラインのバッジ/タグ用。後方互換のため位置・挙動を変えない） -->
      <slot />
      <!-- 「？使い方」と #actions を ml-auto の右寄せグループにまとめる。並び順は help → actions -->
      <div v-if="help || $slots.actions" class="ml-auto flex min-w-0 items-center gap-2 max-md:flex-wrap">
        <Button
          v-if="help"
          icon="pi pi-question-circle"
          :label="helpLabel ?? t('button.help')"
          text
          size="small"
          data-testid="page-header-help"
          @click="emit('help')"
        />
        <slot name="actions" />
      </div>
    </div>
    <p v-if="hasSubtitle()" class="mb-5 mt-1 min-w-0 text-sm text-surface-500 [overflow-wrap:anywhere]" data-testid="page-header-subtitle">
      <slot v-if="$slots.subtitle" name="subtitle" />
      <template v-else>{{ subtitle }}</template>
    </p>
  </div>
</template>
