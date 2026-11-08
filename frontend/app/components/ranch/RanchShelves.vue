<script setup lang="ts">
import type { RanchSlot } from '~/types/ranch'
const props = defineProps<{ slots: RanchSlot[]; active: boolean }>()
const { t, te } = useI18n()
const keys: RanchSlot['slotKey'][] = ['SHELF_1', 'SHELF_2', 'SHELF_3']
// 承認済み静止assetだけを登録する。現在は空で、catalogの任意文字列からURLを作らない。
const assets: Readonly<Record<string, string>> = Object.freeze({})
const shelves = computed(() => keys.map(key => {
 const slot = props.slots.find(value => value.slotKey === key)
 const decoration = slot?.decoration
 return { key, occupied: !!slot?.inventoryId,
  label: decoration && te(decoration.labelKey) ? t(decoration.labelKey) : t('ranch.decorations.placed'),
  src: props.active && decoration && Object.hasOwn(assets, decoration.assetKey) ? assets[decoration.assetKey] : null }
}))
</script>
<template>
 <section :aria-label="t('ranch.decorations.slots')" class="grid grid-cols-3 gap-2 mt-3">
  <div v-for="(shelf, index) in shelves" :key="shelf.key" class="min-w-0 rounded border-b-4 p-2 text-center">
   <p class="text-xs">{{ t('ranch.decorations.slot', { n: index + 1 }) }}</p>
   <template v-if="shelf.occupied">
    <img v-if="shelf.src" :src="shelf.src" :alt="shelf.label" width="48" height="48" class="mx-auto">
    <span v-else aria-hidden="true" class="inline-block text-xl">◇</span>
    <p class="break-words text-xs">{{ shelf.label }}</p>
   </template>
   <p v-else class="text-xs">{{ t('ranch.decorations.empty') }}</p>
  </div>
 </section>
</template>
