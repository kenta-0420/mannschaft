<script setup lang="ts">
defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  select: ['personal' | 'event']
}>()

function choose(type: 'personal' | 'event') {
  emit('select', type)
  emit('update:visible', false)
}

function close() {
  emit('update:visible', false)
}
</script>

<template>
  <Dialog
    :visible="visible"
    header="追加する種類を選択"
    :style="{ width: '440px' }"
    modal
    @update:visible="close"
  >
    <div class="grid grid-cols-2 gap-4 py-2">
      <button
        class="flex flex-col items-center gap-3 rounded-xl border-2 border-surface-200 p-6 text-center transition hover:border-primary hover:bg-primary/5 focus:outline-none focus:ring-2 focus:ring-primary/30"
        @click="choose('personal')"
      >
        <span class="flex h-14 w-14 items-center justify-center rounded-full bg-primary/10">
          <i class="pi pi-user text-2xl text-primary" />
        </span>
        <span class="text-base font-semibold">予定を追加</span>
        <span class="text-sm text-surface-500">個人のスケジュール</span>
      </button>

      <button
        class="flex flex-col items-center gap-3 rounded-xl border-2 border-surface-200 p-6 text-center transition hover:border-primary hover:bg-primary/5 focus:outline-none focus:ring-2 focus:ring-primary/30"
        @click="choose('event')"
      >
        <span class="flex h-14 w-14 items-center justify-center rounded-full bg-primary/10">
          <i class="pi pi-users text-2xl text-primary" />
        </span>
        <span class="text-base font-semibold">イベントを作成</span>
        <span class="text-sm text-surface-500">チーム・組織のイベント</span>
      </button>
    </div>
  </Dialog>
</template>
