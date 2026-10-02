// 一時的な例外観測専用。通常の金型／合格証拠として使わない。
import original from '../../vitest.config'
import type { ConfigEnv } from 'vite'

export default async (env: ConfigEnv) => {
  const config = await (typeof original === 'function' ? original(env) : original)
  return {
    ...config,
    plugins: [...(config.plugins ?? []), {
      name: 'cmp1016-org-caught-exception-diagnostic',
      enforce: 'post' as const,
      transform(code: string, id: string) {
        const source = id.replaceAll('\\', '/').split('?')[0] ?? ''
        const observed = ['/app/stores/useOrganizationStore.ts', '/app/stores/useTeamStore.ts', '/app/stores/useAuthStore.ts', '/app/composables/useApi.ts']
          .find(path => source.endsWith(path))
        if (observed) {
          console.warn('CMP1016_MODULE_IMPORTS', JSON.stringify({
            source: observed,
            moduleId: source,
            imports: code.match(/import[^;\n]*(?:useApi|useAuthStore|useOrganizationStore|useTeamStore)[^;\n]*/g) ?? [],
          }))
          // 評価時にimport bindingは読まない。循環参照のTDZ/順序へ介入しない。
          code = `console.warn('CMP1016_MODULE_EVALUATED', JSON.stringify({ source: ${JSON.stringify(observed)}, stage: globalThis.__cmp1016FactoryStage ?? 'unregistered', moduleId: import.meta.url.split('?')[0], originalQuery: import.meta.url.includes('_vitest_original') }));\n` + code
        }
        const spec = ['/tests/unit/plugins/scope-route-sync.spec.ts', '/tests/unit/components/admin/AdminSettingsHub.spec.ts']
          .find(path => source.endsWith(path))
        if (spec) {
          const factory = "const actual = await importOriginal<typeof import('~/composables/useApi')>()"
          const factoryJs = 'const actual = await importOriginal()'
          const start = code.includes(factory) ? factory : factoryJs
          if (!code.includes(start) || !code.includes('return { ...actual, useApi: () => api }')) throw new Error('API mock diagnostic location not found')
          return { code: code.replace(start, `globalThis.__cmp1016FactoryStage = 'start'; console.warn('CMP1016_MOCK_FACTORY_START', ${JSON.stringify(spec)}); ${start}; globalThis.__cmp1016OriginalApi = actual.useApi; globalThis.__cmp1016FactoryStage = 'ready'; console.warn('CMP1016_MOCK_FACTORY_READY', ${JSON.stringify(spec)})`)
            .replace('return { ...actual, useApi: () => api }', `globalThis.__cmp1016MockedApi = () => { console.warn('CMP1016_MOCK_USE_API_CALL', ${JSON.stringify(spec)}); return api }; return { ...actual, useApi: globalThis.__cmp1016MockedApi }`), map: null }
        }
        if (source.endsWith('/app/composables/useApi.ts')) {
          const start = 'export function useApi() {'
          if (!code.includes(start)) throw new Error('Actual useApi diagnostic location not found')
          return { code: code.replace(start, `${start}
            console.warn('CMP1016_ACTUAL_USE_API_CALL', JSON.stringify({ source: '/app/composables/useApi.ts', callers: new Error().stack?.split('\\n').filter(line => /useOrganizationStore|useTeamStore/.test(line)).slice(0, 2).map(line => line.replace(/\\?[^\\s)]*/g, '')) ?? [] }));`), map: null }
        }
        if (source.endsWith('/app/stores/useTeamStore.ts') || source.endsWith('/app/stores/useOrganizationStore.ts')) {
          if (!code.includes('const api = useApi()')) throw new Error('Store API binding diagnostic location not found')
          code = code.replace('const api = useApi()', `console.warn('CMP1016_STORE_API_BINDING', JSON.stringify({ source: ${JSON.stringify(observed)}, stage: globalThis.__cmp1016FactoryStage ?? 'unregistered', original: useApi === globalThis.__cmp1016OriginalApi, mocked: useApi === globalThis.__cmp1016MockedApi })); const api = useApi()`)
          if (source.endsWith('/app/stores/useTeamStore.ts')) return { code, map: null }
        }
        if (!source.endsWith('/app/stores/useOrganizationStore.ts')) return
        if (!code.includes('catch {')) throw new Error('ORG diagnostic catch location not found')
        // API本文・Cookie・token・envは観測しない。想定外のメッセージは出さない。
        const observation = `catch (cmpOrgDiagnosticError) {
          const cmpError = cmpOrgDiagnosticError;
          const cmpMessage = typeof cmpError?.message === 'string' ? cmpError.message : '';
          const cmpAllowed = /^(useApi is not defined|useApi is not a function|\\[nuxt\\].*instance unavailable|\\[nuxt\\].*composable|\\[🍍\\].*getActivePinia)/u.test(cmpMessage);
          const cmpSource = typeof cmpError?.stack === 'string'
            ? cmpError.stack.split('\\n').filter(line => /useOrganizationStore|useApi\\.ts|nuxt.*[\\/]app|pinia/.test(line)).slice(0, 3).map(line => line.replace(/\\?[^\\s)]*/g, '')) : [];
          console.warn('CMP1016_ORG_CAUGHT_EXCEPTION', JSON.stringify({
            name: typeof cmpError?.name === 'string' ? cmpError.name : typeof cmpError,
            message: cmpAllowed ? cmpMessage.split('\\n')[0].slice(0, 180) : '想定外メッセージは本文漏洩防止のため除外',
            source: cmpSource,
          }));`
        console.warn('CMP1016_ORG_DIAGNOSTIC_TRANSFORM', JSON.stringify({
          source: '/app/stores/useOrganizationStore.ts',
          useApiImport: code.match(/import[^;\n]*useApi[^;\n]*/g) ?? [],
        }))
        return { code: code.replace('catch {', observation), map: null }
      },
    }],
  }
}
