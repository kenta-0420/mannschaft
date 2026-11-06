import type { ConfigEnv } from 'vite'
import { configDefaults } from 'vitest/config'
import baseConfig from './vitest.config'
import { ranchClipDevelopmentSpecs } from './vitest.ranch-clips-specs'

// 本番相当のdefault試験を保ち、v3の実development catalogだけを別実行で検証する。
export default async (environment: ConfigEnv) => {
  const config = await baseConfig(environment)
  return {
    ...config,
    define: { ...config.define, 'import.meta.dev': 'true' },
    test: { ...config.test, include: ranchClipDevelopmentSpecs, exclude: configDefaults.exclude },
  }
}
