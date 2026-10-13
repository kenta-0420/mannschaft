<script setup lang="ts">
/**
 * 検証専用ページ — PrimeVue 既定文言（aria-label・日付・ページ送り）の言語追従を E2E で確かめる（CMP-261007-2053）。
 *
 * 開発・E2E 環境（import.meta.dev）でのみ有効。本番ビルドでは 404 を返す。
 * 認証不要。画面に表示する文字列は持たない（ボタンはロケールコード、タブは番号、
 * その他の文言は PrimeVue 既定文言そのもの＝検証対象）。
 */
import { ref } from 'vue'

definePageMeta({ auth: false, layout: false })

if (!import.meta.dev) {
  throw createError({ statusCode: 404, fatal: true })
}

const LOCALE_CODES = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const TAB_COUNT = 30

const { applyAccountLocale } = useLocale()

const dialogVisible = ref(true)
const drawerVisible = ref(true)
const date = ref<Date | null>(null)
const doneLocale = ref('')

const apply = async (code: string) => {
  await applyAccountLocale(code)
  doneLocale.value = code
}
</script>

<template>
  <div class="p-4">
    <div class="mb-4 flex gap-2">
      <button
        v-for="code in LOCALE_CODES"
        :key="code"
        type="button"
        class="rounded border px-3 py-1"
        :data-testid="`apply-locale-${code}`"
        @click="apply(code)"
      >
        {{ code }}
      </button>
      <span data-testid="apply-locale-done">{{ doneLocale }}</span>
    </div>

    <div class="relative mb-4 h-64 overflow-hidden border" data-testid="pv-dialog">
      <Dialog v-model:visible="dialogVisible" append-to="self" closable :modal="false" header="Dialog" />
    </div>

    <div class="relative mb-4 h-64 overflow-hidden border" data-testid="pv-drawer">
      <!--
        PrimeVue の Drawer は append-to="self" でも Portal 経由で body 直下に position:fixed のマスクを描画し、
        既定 position="left" ではパネル本体（pointer-events:auto・幅 20rem・全高）が画面左端を覆う。
        modal=false ならマスク自体は pointer-events:none だが、パネルが左上の言語切替ボタンに重なり
        クリックを奪うため、右端に寄せて幅も絞り、操作対象と重ならないようにする。
        また modal=false では dismissable（既定 true）で外側クリックリスナーが付き言語切替の押下で閉じるため、dismissable=false にする。
      -->
      <Drawer
        v-model:visible="drawerVisible"
        append-to="self"
        position="right"
        :modal="false"
        :dismissable="false"
        header="Drawer"
        :pt="{ root: { style: { width: '16rem' } } }"
      />
    </div>

    <div class="mb-4 w-64" data-testid="pv-tabs">
      <Tabs value="0" scrollable>
        <TabList>
          <Tab v-for="n in TAB_COUNT" :key="n" :value="String(n - 1)">{{ n }}</Tab>
        </TabList>
      </Tabs>
    </div>

    <div class="mb-4" data-testid="pv-datepicker">
      <DatePicker v-model="date" inline show-button-bar />
    </div>

    <div data-testid="pv-paginator">
      <Paginator :rows="10" :total-records="100" />
    </div>
  </div>
</template>
