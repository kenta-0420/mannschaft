<script setup lang="ts">
/**
 * F03.10 代理出席 — マイページ代理依頼一覧。
 * 複数スケジュール/イベントをまたぐ一覧取得専用 API は存在しないため、
 * 各スケジュール・イベントの詳細ページへ誘導するシンプルなページ。
 */
definePageMeta({ middleware: 'auth' })
</script>

<template>
  <div class="mx-auto max-w-2xl px-4 py-8">
    <!-- ページタイトル -->
    <h1 class="mb-2 text-2xl font-bold text-gray-900 dark:text-gray-100">
      {{ $t('proxy.delegation.my_requests.title') }}
    </h1>

    <!-- 説明文 -->
    <p class="mb-6 text-sm text-gray-600 dark:text-gray-400">
      {{ $t('proxy.delegation.my_requests.description') }}
    </p>

    <!-- 案内カード -->
    <div class="rounded-lg border border-blue-200 bg-blue-50 p-5 dark:border-blue-800 dark:bg-blue-900/20">
      <div class="flex items-start gap-3">
        <i class="pi pi-info-circle mt-0.5 text-blue-500 dark:text-blue-400" />
        <div>
          <p class="text-sm text-blue-800 dark:text-blue-200">
            {{ $t('proxy.delegation.my_requests.how_to_check') }}
          </p>
        </div>
      </div>
    </div>

    <!-- 空状態テキスト -->
    <div class="mt-8 flex flex-col items-center gap-3 py-12 text-center">
      <i class="pi pi-users text-4xl text-gray-300 dark:text-gray-600" />
      <p class="text-sm text-gray-500 dark:text-gray-400">
        {{ $t('proxy.delegation.my_requests.empty') }}
      </p>
    </div>
  </div>
</template>
