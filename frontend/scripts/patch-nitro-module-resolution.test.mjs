import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
import vm from 'node:vm'
import test from 'node:test'
import { normalize, isAbsolute } from 'pathe'
import { resolveModuleURL } from 'exsolve'
import * as esmMlly from 'mlly'

const require = createRequire(import.meta.url)
const conditions = ['node', 'import', 'production']
const importer = fileURLToPath(import.meta.url)
const resolvePublic = id => resolveModuleURL(id, { from: import.meta.url, conditions, try: true })
const serverRenderer = fileURLToPath(resolvePublic('vue/server-renderer'))

for (const [format, mlly] of [['CJS', require('mlly')], ['ESM', esmMlly]]) {
  test(`${format}: 入れ子manifestがあってもroot exportsの公開subpathを返す`, async () => {
    assert.equal(await mlly.lookupNodeModuleSubpath(serverRenderer), './server-renderer')
  })

  test(`${format}: 主entryと公開subpathは実fileへ戻り非公開subpathは拒否する`, async () => {
    const main = fileURLToPath(resolvePublic('vue'))
    assert.equal(await mlly.lookupNodeModuleSubpath(main), './')
    assert.equal(resolvePublic('vue/server-renderer/index.mjs'), undefined)
    const subpath = await mlly.lookupNodeModuleSubpath(serverRenderer)
    const guessed = resolvePublic(`vue/${subpath.slice(2)}`)
    assert.ok(guessed, '公開subpathへ逆引きしたguessが解決できる')
    assert.equal(normalize(fileURLToPath(guessed)), normalize(serverRenderer))
  })
}

// 配布物の実tryResolveだけを実行する。Nitro全体のbuild成功を表す試験ではない。
const nitro = readFileSync(new URL('../node_modules/nitropack/dist/rollup/index.mjs', import.meta.url), 'utf8')
const tryResolveSource = nitro.match(/const tryResolve = \(id, importer\) => \{[\s\S]*?\n {2}\};/)?.[0]
assert.ok(tryResolveSource)
const runTryResolve = urlToPath => vm.runInNewContext(`(() => { ${tryResolveSource}; return tryResolve })()`, {
  resolveModuleURL, fileURLToPath: urlToPath, pathToFileURL, normalize, isAbsolute,
  opts: { exportConditions: conditions, moduleDirectories: [] },
})

test('実OS pathの主entryと公開subpathがNitroの正規化済みidと一致する', () => {
  const tryResolve = runTryResolve(fileURLToPath)
  assert.equal(tryResolve('vue', importer), normalize(fileURLToPath(resolvePublic('vue'))))
  assert.equal(tryResolve('vue/server-renderer', importer), normalize(serverRenderer))
})

test('合成した逆slash変換境界でもNitroが正規化する（実Windows試験とは別）', () => {
  const tryResolve = runTryResolve(url => fileURLToPath(url).replaceAll('/', '\\'))
  assert.equal(tryResolve('vue/server-renderer', importer), normalize(serverRenderer))
})

const { TARGETS, patchBytes } = await import('./patch-nitro-module-resolution.mjs')
const digest = bytes => createHash('sha256').update(bytes).digest('hex')
// 正規npm ciで取得した実bytesだけを検体にする。配布物全体をrepoへ複製しない。
const originals = TARGETS.map(target => {
  let bytes = readFileSync(new URL(`../node_modules/${target.package}/${target.file}`, import.meta.url))
  if (digest(bytes) === target.patchedSha) {
    let source = bytes.toString('utf8')
    for (const [before, after] of target.edits) source = source.replace(after, before)
    bytes = Buffer.from(source)
  }
  assert.equal(digest(bytes), target.originalSha)
  return bytes
})

for (const [index, target] of TARGETS.entries()) {
  test(`${target.package}/${target.file}: 公式全bytesの唯一置換と冪等・未知版/bytes拒否`, () => {
    const patched = patchBytes(originals[index], target.version, target)
    assert.equal(digest(patched), target.patchedSha)
    assert.strictEqual(patchBytes(patched, target.version, target), patched)
    assert.throws(() => patchBytes(originals[index], '0.0.0', target), /NITRO_MODULE_RESOLUTION_PATCH_REFUSED/)
    assert.throws(() => patchBytes(Buffer.concat([originals[index], Buffer.from('\n')]), target.version, target), /NITRO_MODULE_RESOLUTION_PATCH_REFUSED/)
  })
}

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'mannschaft-nitro-resolution-'))
  // この試験が作った一時dirだけを所有確認できるスコープで撤去する。
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const script = join(root, 'scripts/patch-nitro-module-resolution.mjs')
  mkdirSync(dirname(script), { recursive: true })
  writeFileSync(script, readFileSync(new URL('./patch-nitro-module-resolution.mjs', import.meta.url)))
  const paths = TARGETS.map((target, index) => {
    const path = join(root, 'node_modules', target.package, target.file)
    mkdirSync(dirname(path), { recursive: true })
    writeFileSync(path, originals[index])
    writeFileSync(join(root, 'node_modules', target.package, 'package.json'), JSON.stringify({ version: target.version }))
    return path
  })
  return { root, paths, run: (...args) => spawnSync(process.execPath, [script, ...args], { encoding: 'utf8' }) }
}

test('所有実dirの全3対象を補正し再実行でも同一bytesを維持する', t => {
  const f = fixture(t)
  assert.equal(f.run().status, 0)
  assert.deepEqual(f.paths.map(path => digest(readFileSync(path))), TARGETS.map(target => target.patchedSha))
  assert.equal(f.run().status, 0)
  assert.deepEqual(f.paths.map(path => digest(readFileSync(path))), TARGETS.map(target => target.patchedSha))
})

for (const kind of ['最終targetの未知bytes', '後段packageの未知版', '任意path引数']) {
  test(`${kind}は非0で全3対象を書き込まない`, t => {
    const f = fixture(t)
    if (kind === '最終targetの未知bytes') writeFileSync(f.paths[2], Buffer.concat([originals[2], Buffer.from('\n')]))
    if (kind === '後段packageの未知版') writeFileSync(join(f.root, 'node_modules/mlly/package.json'), '{"version":"0.0.0"}')
    const before = f.paths.map(path => readFileSync(path))
    const result = f.run(...(kind === '任意path引数' ? ['outside'] : []))
    assert.notEqual(result.status, 0)
    assert.match(result.stderr, /NITRO_MODULE_RESOLUTION_PATCH_REFUSED/)
    assert.deepEqual(f.paths.map(path => readFileSync(path)), before)
  })
}

test('後段packageのdirectory linkを拒否し先行targetも書き込まない', t => {
  const f = fixture(t)
  const linked = join(f.root, 'linked-mlly')
  mkdirSync(linked)
  // 内容を持たない所有dirのjunction/symlinkを検査し、リンク先を操作しない。
  rmSync(join(f.root, 'node_modules/mlly'), { recursive: true })
  symlinkSync(linked, join(f.root, 'node_modules/mlly'), process.platform === 'win32' ? 'junction' : 'dir')
  assert.notEqual(f.run().status, 0)
  assert.deepEqual(readFileSync(f.paths[0]), originals[0])
})
