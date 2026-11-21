import { createHash } from 'node:crypto'
import { lstatSync, readFileSync, realpathSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// 公式固定配布物の外部化解決だけを補正する。対象追加や未知bytesの受理は禁止。
export const TARGETS = [
  {
    package: 'nitropack', version: '2.13.4', file: 'dist/rollup/index.mjs',
    originalSha: '847ad3149fd532c8241293de7d8d08b1b181fe2ddc4793f1b1add59c4ad5a5ae',
    patchedSha: 'fad016c0a5be7bbf31874aee444272e02807ceab06107c942cfe7f61aee743aa',
    edits: [['return res?.startsWith("file://") ? fileURLToPath(res) : res;', 'return res?.startsWith("file://") ? normalize(fileURLToPath(res)) : res;']],
  },
  {
    package: 'mlly', version: '1.8.2', file: 'dist/index.cjs',
    originalSha: '5eff0719b8c589ec720f013928a4b61063077b6220e5babc6133d81febf8d901',
    patchedSha: '280232f73546025e6cb314bd618b0b0798e883fa78a0cffd7d129c62cd4cd0c1',
    edits: [
      ['const { name, subpath } = parseNodeModulePath(path);', 'const { dir, name, subpath } = parseNodeModulePath(path);'],
      ['await pkgTypes.readPackageJSON(path).catch(() => {', 'await pkgTypes.readPackageJSON(`${dir}${name}/package.json`).catch(() => {'],
    ],
  },
  {
    package: 'mlly', version: '1.8.2', file: 'dist/index.mjs',
    originalSha: '170c8b21a62adf2803ad08305b5b7d1c0ad1c45db35136f5ffaa4572bf961656',
    patchedSha: 'a828dca32ee48caad4a6fe14c4d699ddbb5e305cc99e777d25ceef6030963612',
    edits: [
      ['const { name, subpath } = parseNodeModulePath(path);', 'const { dir, name, subpath } = parseNodeModulePath(path);'],
      ['await readPackageJSON(path).catch(() => {', 'await readPackageJSON(`${dir}${name}/package.json`).catch(() => {'],
    ],
  },
]
const digest = bytes => createHash('sha256').update(bytes).digest('hex')
const refuse = () => { throw new Error('NITRO_MODULE_RESOLUTION_PATCH_REFUSED') }

export function patchBytes(bytes, version, target) {
  if (!TARGETS.includes(target) || version !== target.version || !Buffer.isBuffer(bytes)) refuse()
  const sha = digest(bytes)
  if (sha === target.patchedSha) return bytes
  if (sha !== target.originalSha) refuse()
  let text = bytes.toString('utf8')
  for (const [before, after] of target.edits) {
    if (text.split(before).length !== 2) refuse()
    text = text.replace(before, after)
  }
  const result = Buffer.from(text, 'utf8')
  if (digest(result) !== target.patchedSha) refuse()
  return result
}

export function applyPinnedPatch() {
  if (process.argv.length !== 2) refuse()
  const frontend = dirname(dirname(fileURLToPath(import.meta.url)))
  if (realpathSync(frontend) !== resolve(frontend)) refuse()
  // 全targetの版・実path・bytesを先に検証し、未知のものが1つでもあれば全無書込。
  const prepared = TARGETS.map(target => {
    const components = ['node_modules', target.package, ...target.file.split('/')]
    let current = frontend
    for (const [index, component] of components.entries()) {
      current = join(current, component)
      const stat = lstatSync(current)
      if (stat.isSymbolicLink() || (index === components.length - 1 ? !stat.isFile() : !stat.isDirectory())) refuse()
    }
    const packagePath = join(frontend, 'node_modules', target.package, 'package.json')
    const stat = lstatSync(packagePath)
    if (!stat.isFile() || stat.isSymbolicLink()) refuse()
    const version = JSON.parse(readFileSync(packagePath, 'utf8')).version
    const original = readFileSync(current)
    return { target, path: current, original, patched: patchBytes(original, version, target) }
  })
  for (const item of prepared) {
    if (item.patched !== item.original) writeFileSync(item.path, item.patched)
    if (digest(readFileSync(item.path)) !== item.target.patchedSha) refuse()
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { applyPinnedPatch() } catch { console.error('NITRO_MODULE_RESOLUTION_PATCH_REFUSED'); process.exitCode = 1 }
}
