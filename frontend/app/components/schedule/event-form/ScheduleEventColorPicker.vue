<script setup lang="ts">
const color = defineModel<string>('color', { required: true })
</script>

<template>
  <div>
    <label class="mb-1 block text-sm font-medium">色</label>
    <div class="flex gap-2">
      <button
        v-for="c in ['#6366f1', '#ef4444', '#22c55e', '#f59e0b', '#3b82f6', '#ec4899']"
        :key="c"
        class="h-8 w-8 rounded-full border-2"
        :class="
          color === c ? 'border-primary ring-2 ring-primary/30' : 'border-surface-300'
        "
        :style="{ backgroundColor: c }"
        @click="color = c"
      />
    </div>
  </div>
</template>
