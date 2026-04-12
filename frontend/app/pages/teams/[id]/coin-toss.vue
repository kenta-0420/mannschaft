<script setup lang="ts">
import type { CoinTossResponse } from '~/types/coin-toss'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = Number(route.params.id)
const { toss, getHistory, shareToChat } = useCoinTossApi()
const { showError } = useNotification()

type CoinMode = 'COIN' | 'DICE' | 'CUSTOM'
const mode = ref<CoinMode>('COIN')
const question = ref('')
const customOptions = ref<string[]>(['', ''])
const result = ref<CoinTossResponse | null>(null)
const animating = ref(false)
const history = ref<CoinTossResponse[]>([])
const showHistory = ref(false)

const modeOptions = [
  { label: 'コイン', value: 'COIN', icon: 'pi pi-circle' },
  { label: 'サイコロ', value: 'DICE', icon: 'pi pi-th-large' },
  { label: 'カスタム', value: 'CUSTOM', icon: 'pi pi-list' },
]

function addOption() {
  customOptions.value.push('')
}

function removeOption(index: number) {
  if (customOptions.value.length > 2) customOptions.value.splice(index, 1)
}

async function doToss() {
  animating.value = true
  result.value = null
  try {
    const body: Record<string, unknown> = { mode: mode.value }
    if (question.value) body.question = question.value
    if (mode.value === 'CUSTOM') body.options = customOptions.value.filter((o) => o.trim())
    const res = await toss(teamId, body as Record<string, unknown>)
    // アニメーション待ち
    await new Promise((resolve) => setTimeout(resolve, 1000))
    result.value = res.data
  } catch {
    showError('コイントスに失敗しました')
  } finally {
    animating.value = false
  }
}

async function loadHistory() {
  try {
    const res = await getHistory(teamId)
    history.value = res.data
    showHistory.value = true
  } catch {
    showError('履歴の取得に失敗しました')
  }
}

async function share(item: CoinTossResponse) {
  try {
    await shareToChat(teamId, item.id)
  } catch {
    showError('共有に失敗しました')
  }
}
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="コイントス" />
      <Button label="履歴" icon="pi pi-history" text @click="loadHistory" />
    </div>

    <div class="mx-auto max-w-lg">
      <SectionCard>
        <!-- モード選択 -->
        <div class="mb-4">
          <label class="mb-2 block text-sm font-medium">モード</label>
          <SelectButton
            v-model="mode"
            :options="modeOptions"
            option-label="label"
            option-value="value"
          />
        </div>

        <!-- 質問 -->
        <div class="mb-4">
          <label class="mb-1 block text-sm font-medium">質問（任意）</label>
          <InputText v-model="question" class="w-full" placeholder="例: 今日のランチは？" />
        </div>

        <!-- カスタム選択肢 -->
        <div v-if="mode === 'CUSTOM'" class="mb-4">
          <label class="mb-2 block text-sm font-medium">選択肢</label>
          <div class="flex flex-col gap-2">
            <div v-for="(_, index) in customOptions" :key="index" class="flex gap-2">
              <InputText
                v-model="customOptions[index]"
                class="flex-1"
                :placeholder="`選択肢 ${index + 1}`"
              />
              <Button
                v-if="customOptions.length > 2"
                icon="pi pi-times"
                text
                rounded
                size="small"
                @click="removeOption(index)"
              />
            </div>
            <Button label="選択肢を追加" icon="pi pi-plus" text size="small" @click="addOption" />
          </div>
        </div>

        <!-- トスボタン -->
        <Button
          :label="
            animating ? '...' : mode === 'COIN' ? 'トス！' : mode === 'DICE' ? '振る！' : '決定！'
          "
          :loading="animating"
          class="w-full"
          size="large"
          @click="doToss"
        />

        <!-- 結果表示 -->
        <div v-if="result" class="mt-6 text-center">
          <div class="mb-2 text-6xl">
            {{
              mode === 'COIN'
                ? result.resultIndex === 0
                  ? '🪙'
                  : '💿'
                : mode === 'DICE'
                  ? '🎲'
                  : '🎯'
            }}
          </div>
          <p v-if="result.question" class="mb-1 text-sm text-surface-500">{{ result.question }}</p>
          <p class="text-2xl font-bold text-primary">{{ result.result }}</p>
          <Button
            v-if="!result.sharedToChat"
            label="チャットに共有"
            icon="pi pi-share-alt"
            text
            class="mt-2"
            @click="share(result)"
          />
        </div>
      </SectionCard>
    </div>

    <!-- 履歴ダイアログ -->
    <Dialog v-model:visible="showHistory" header="コイントス履歴" modal class="w-full max-w-lg">
      <div class="flex flex-col gap-2">
        <div
          v-for="item in history"
          :key="item.id"
          class="flex items-center justify-between rounded-lg border border-surface-100 p-3 dark:border-surface-600"
        >
          <div>
            <p v-if="item.question" class="text-sm text-surface-500">{{ item.question }}</p>
            <p class="font-semibold">{{ item.result }}</p>
            <p class="text-xs text-surface-400">
              {{ new Date(item.createdAt).toLocaleString('ja-JP') }}
            </p>
          </div>
          <Tag :value="item.mode" severity="info" />
        </div>
        <div v-if="history.length === 0" class="py-4 text-center text-surface-400">
          履歴がありません
        </div>
      </div>
    </Dialog>
  </div>
</template>
