<script setup lang="ts">
// 機能カードグリッド
interface FeatureItem {
  icon: string
  key: number
}

defineProps<{
  features: ReadonlyArray<FeatureItem>
}>()

const { t } = useI18n()
</script>

<template>
  <section class="features-grid-section" aria-labelledby="features2000-heading">
    <h2 id="features2000-heading" class="section-heading">{{ t('landing.features.heading') }}</h2>
    <div class="features-grid">
      <div
        v-for="f in features"
        :key="f.key"
        class="glass-panel feature-card"
      >
        <div class="card-icon" aria-hidden="true">{{ f.icon }}</div>
        <div class="card-title">{{ t(`landing.features.items.${f.key}.title`) }}</div>
        <div class="card-desc">{{ t(`landing.features.items.${f.key}.desc`) }}</div>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* =====================
   ガラスパネル
   ===================== */
.glass-panel {
  background: rgba(255, 255, 255, 0.15);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border: 1px solid rgba(255, 255, 255, 0.3);
  border-radius: 6px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
}

/* =====================
   セクション見出し
   ===================== */
.section-heading {
  font-size: 1rem;
  font-weight: 700;
  color: #e0f0ff;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.4);
  margin-bottom: 10px;
}

/* =====================
   機能グリッド
   ===================== */
.features-grid-section {
  margin-bottom: 16px;
}

.features-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
}

@media (max-width: 600px) {
  .features-grid {
    grid-template-columns: 1fr;
  }
}

.feature-card {
  padding: 14px;
}

.card-icon {
  font-size: 1.3rem;
  margin-bottom: 6px;
}

.card-title {
  font-size: 0.8rem;
  font-weight: 700;
  color: #e0f0ff;
  margin-bottom: 4px;
}

.card-desc {
  font-size: 0.75rem;
  color: #b8d8f0;
  line-height: 1.5;
}
</style>
