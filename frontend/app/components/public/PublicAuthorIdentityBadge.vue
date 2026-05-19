<script setup lang="ts">
import type { PublicAuthorIdentity } from '~/types/public'

/**
 * F19.1 投稿者識別バッジ。
 *
 * バックエンド {@link IdentityVisibilityResolver} の出力 {@link PublicAuthorIdentity} を
 * 受け取って描画する。匿名フォールバック時は汎用アバター + GenericLabel を表示し、
 * 非匿名時は実アバター + displayLabel を表示する。
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §4.6 / §6.3
 */
const props = defineProps<{
  author: PublicAuthorIdentity
  /** アバター直径 (px) */
  size?: number
}>()

const { t } = useI18n()
const size = computed(() => props.size ?? 32)
</script>

<template>
  <div class="flex items-center gap-2">
    <template v-if="author.isAnonymized || !author.avatarUrl">
      <AnonymousAvatar :size="size" :alt="t('public.author.anonymousAvatarAlt')" />
    </template>
    <template v-else>
      <img
        :src="author.avatarUrl"
        :alt="author.displayLabel"
        class="shrink-0 rounded-full object-cover"
        :style="{ width: `${size}px`, height: `${size}px` }"
        loading="lazy"
      >
    </template>
    <div class="flex flex-col">
      <span class="text-sm font-medium text-surface-800 dark:text-surface-100">
        {{ author.displayLabel }}
      </span>
      <GenericLabel v-if="author.isAnonymized" kind="anonymous" />
    </div>
  </div>
</template>
