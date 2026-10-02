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
    /** 「？使い方」ボタンを描画するか。既定 false（明示的に出すページのみ opt-in する） */
    help?: boolean
    /** 使い方ボタンのラベル。未指定なら i18n button.help（使い方/How to use…） */
    helpLabel?: string
  }>(),
  {
    size: 'lg',
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
  <div>
    <!-- 戻るリンクはタイトル行の上に描画する。既存 slot（アクション/バッジ）はタイトル行に並ぶため干渉しない -->
    <BackButton v-if="back" :to="backTo" :label="backLabel" />
    <div class="mb-5 flex items-end gap-3" v-bind="$attrs">
      <h1 :class="titleClass">{{ title }}</h1>
      <!-- 既定スロットはタイトルのすぐ右（インラインのバッジ/タグ用。後方互換のため位置・挙動を変えない） -->
      <slot />
      <!-- 「？使い方」と #actions を ml-auto の右寄せグループにまとめる。並び順は help → actions -->
      <div v-if="help || $slots.actions" class="ml-auto flex items-center gap-2">
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
  </div>
</template>
