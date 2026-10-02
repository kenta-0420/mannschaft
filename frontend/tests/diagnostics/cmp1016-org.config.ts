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
        if (!id.replaceAll('\\', '/').split('?')[0]?.endsWith('/app/stores/useOrganizationStore.ts')) return
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
