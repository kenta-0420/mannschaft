<template>
  <div>
    <!--
      pageTransition(out-in) は単一の「要素」ルートを要求する。<NuxtPage/> 裸ルートは
      非要素（フラグメント）ルートとなりアニメ不能→out-in の leave 完了が壊れ、戻り先
      ページが mount されない。単一 <div> で包んで要素ルート化する。
    -->
    <NuxtPage />
  </div>
</template>
