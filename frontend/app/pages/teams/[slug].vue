<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})
</script>

<template>
  <div>
    <!--
      pageTransition(out-in) は単一の「要素」ルートを要求する。<NuxtPage/> を裸の
      ルートにすると Suspense 経由で非要素（フラグメント）ルートになりアニメ不能となり、
      out-in の leave 完了コールバックが壊れて次ページ(dashboard 等)が mount されない
      （SPA ブラウザバックでダッシュボードが空白になる事象の根治）。単一 <div> で包む。
      ※コメントも要素ルートの内側に置く（ルート直下に置くと複数ルートノード扱いになりうる）。
    -->
    <NuxtPage />
  </div>
</template>
