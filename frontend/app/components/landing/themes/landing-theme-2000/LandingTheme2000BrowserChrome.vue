<script setup lang="ts">
// ブラウザ風クロームフレーム（装飾用、すべて aria-hidden / role=complementary）
// 振る舞いなし・純粋な見た目用コンポーネント
</script>

<template>
  <div class="browser-chrome" role="complementary" aria-label="ブラウザフレーム（装飾）">
    <div class="chrome-toolbar">
      <div class="chrome-buttons" aria-hidden="true">
        <span class="chrome-btn chrome-btn--back">◀</span>
        <span class="chrome-btn chrome-btn--forward">▶</span>
        <span class="chrome-btn chrome-btn--reload">↻</span>
      </div>
      <div class="chrome-address-bar" aria-hidden="true">
        <span class="address-icon">🔒</span>
        <span class="address-url">http://mannschaft.example.jp/</span>
      </div>
      <div class="chrome-actions" aria-hidden="true">
        <span class="chrome-action-btn">★</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* =====================
   ブラウザ風クロームフレーム
   ===================== */
.browser-chrome {
  background: linear-gradient(to bottom, #d8e4f0, #b8c8dc);
  border-bottom: 2px solid #7090b0;
  padding: 6px 12px;
}

.chrome-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 1100px;
  margin: 0 auto;
}

.chrome-buttons {
  display: flex;
  gap: 4px;
}

.chrome-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 20px;
  background: linear-gradient(to bottom, #e8f0f8, #c8d8e8);
  border: 1px solid #8090a8;
  border-radius: 3px;
  font-size: 9px;
  color: #404860;
  cursor: default;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.15), inset 0 1px 0 rgba(255, 255, 255, 0.6);
  user-select: none;
}

.chrome-address-bar {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  background: #ffffff;
  border: 1px solid #8090a8;
  border-radius: 2px;
  padding: 3px 8px;
  font-size: 11px;
  color: #334;
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.15);
}

.address-icon {
  font-size: 10px;
}

.address-url {
  color: #00008b;
}

.chrome-action-btn {
  color: #c0a000;
  font-size: 16px;
  cursor: default;
  user-select: none;
}
</style>
