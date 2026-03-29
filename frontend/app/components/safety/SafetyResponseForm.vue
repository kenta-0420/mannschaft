<script setup lang="ts">
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  checkId: number
  title: string
  isDrill: boolean
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  responded: []
}>()

const safetyApi = useSafetyCheckApi()
const notification = useNotification()

const submitting = ref(false)
const selectedStatus = ref<string | null>(null)
const message = ref('')
const shareLocation = ref(false)
const latitude = ref<number | null>(null)
const longitude = ref<number | null>(null)

const responseOptions = [
  { status: 'SAFE', label: '無事です', icon: 'pi pi-check-circle', color: 'bg-green-500' },
  {
    status: 'NEED_SUPPORT',
    label: '支援が必要',
    icon: 'pi pi-exclamation-circle',
    color: 'bg-red-500',
  },
  { status: 'OTHER', label: 'その他', icon: 'pi pi-info-circle', color: 'bg-yellow-500' },
]

async function getCurrentLocation() {
  if (!navigator.geolocation) return
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      latitude.value = pos.coords.latitude
      longitude.value = pos.coords.longitude
    },
    () => {
      shareLocation.value = false
    },
  )
}

watch(shareLocation, (val) => {
  if (val) getCurrentLocation()
  else {
    latitude.value = null
    longitude.value = null
  }
})

async function submit() {
  if (!selectedStatus.value) return
  submitting.value = true
  try {
    await safetyApi.respondToSafetyCheck(props.checkId, {
      status: selectedStatus.value,
      message: message.value.trim() || undefined,
      latitude: shareLocation.value ? (latitude.value ?? undefined) : undefined,
      longitude: shareLocation.value ? (longitude.value ?? undefined) : undefined,
    })
    notification.success('安否を回答しました')
    emit('responded')
    emit('update:visible', false)
  } catch {
    notification.error('回答に失敗しました')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="(isDrill ? '【訓練】' : '') + '安否確認'"
    :style="{ width: '400px' }"
    modal
    :closable="false"
    @update:visible="emit('update:visible', $event)"
  >
    <div class="flex flex-col gap-4">
      <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-700/50">
        <p class="text-sm font-semibold">{{ title }}</p>
      </div>

      <!-- 回答選択 -->
      <div class="space-y-2">
        <button
          v-for="opt in responseOptions"
          :key="opt.status"
          class="flex w-full items-center gap-3 rounded-lg border-2 p-3 transition-all"
          :class="
            selectedStatus === opt.status
              ? 'border-primary bg-primary/5'
              : 'border-surface-200 hover:border-surface-300 dark:border-surface-600'
          "
          @click="selectedStatus = opt.status"
        >
          <div
            class="flex h-10 w-10 items-center justify-center rounded-full text-white"
            :class="opt.color"
          >
            <i :class="opt.icon" />
          </div>
          <span class="text-sm font-medium">{{ opt.label }}</span>
        </button>
      </div>

      <!-- メッセージ -->
      <div>
        <label class="mb-1 block text-sm font-medium">メッセージ（任意）</label>
        <Textarea v-model="message" rows="2" class="w-full" placeholder="状況を入力..." />
      </div>

      <!-- GPS共有 -->
      <div class="flex items-center gap-2">
        <ToggleSwitch v-model="shareLocation" />
        <label class="text-sm">現在地を共有する</label>
      </div>
      <p v-if="shareLocation && latitude" class="text-xs text-surface-400">
        <i class="pi pi-map-marker mr-1" />位置情報を取得しました
      </p>
    </div>

    <template #footer>
      <Button
        label="回答する"
        icon="pi pi-check"
        :loading="submitting"
        :disabled="!selectedStatus"
        class="w-full"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
