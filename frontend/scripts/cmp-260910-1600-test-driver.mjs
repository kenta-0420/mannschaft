// verification-run.mjs配下で使う限定試練driver。依存導入・コピー・サーバー起動は行わない。
import { spawn } from 'node:child_process'
import { access, readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { join, resolve } from 'node:path'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const evidence = process.env.VERIFICATION_EVIDENCE_DIR
if (!evidence) throw new Error('公式verification-runのVERIFICATION_EVIDENCE_DIRが必要です')
const vitest = join(frontend, 'node_modules/vitest/vitest.mjs')
await access(vitest)
await access(join(frontend, '.nuxt/tsconfig.json'))
const resultPath = resolve(evidence, 'cmp1600-vitest.json')
const spec = 'app/pages/teams/[slug]/shifts.create-refresh.spec.ts'
const expected = [
  'AC-1/2 空の一覧から作成するとF5なしで既存行と新規行を各1件表示する',
  'AC-1/2 既存行ありから作成するとF5なしで既存行と新規行を各1件表示する',
  'AC-3 POST失敗では既存一覧を維持し成功通知と追加GETを出さない',
  'AC-3 ダイアログ取消ではPOSTも追加GETも行わない',
  'AC-4 作成後GET失敗を空状態にせず既存の再試行で復旧しPOSTを再送しない',
  'AC-5 一般メンバーには新規作成操作を表示しない',
  'AC-6 チーム遷移で再mountした後に旧チームのPOST応答が届いても混線しない',
  'AC-6 チーム遷移で再mountした後に旧チームのGET応答が届いても混線しない',
]
const native = await new Promise((done, reject) => {
  const child = spawn(process.execPath, [vitest, 'run', spec, '--maxWorkers=1', '--reporter=json', `--outputFile=${resultPath}`], {
    cwd: frontend, env: process.env, stdio: 'inherit',
  })
  child.once('error', reject)
  child.once('exit', (code, signal) => done({ code, signal }))
})
// 起動失敗・suite欠落・skipを受け入れ条件のredやgreenとして数えない。
const results = JSON.parse(await readFile(resultPath, 'utf8'))
const cases = results.testResults.flatMap(suite => suite.assertionResults)
const names = cases.map(test => test.title)
const complete = cases.length === expected.length
  && expected.every(name => names.filter(actual => actual === name).length === 1)
  && cases.every(test => ['passed', 'failed'].includes(test.status))
await writeFile(resolve(evidence, 'cmp1600-summary.json'), JSON.stringify({
  spec, native, complete,
  cases: cases.map(test => ({ name: test.title, status: test.status })),
  // assertion failureか環境失敗かの判定はvitest原本のfailureMessagesをrootが照合する。
  judgment: '未判定: rootがfailureMessagesと実行状態を照合する',
}, null, 2) + '\n')
process.exitCode = complete && native.signal === null ? (native.code ?? 2) : 2
