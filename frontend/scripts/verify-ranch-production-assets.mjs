import { createHash, randomUUID } from 'node:crypto'
import { readFileSync, realpathSync, mkdirSync, writeFileSync, existsSync, readdirSync, mkdtempSync, renameSync } from 'node:fs'
import { inflateSync } from 'node:zlib'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { parseArgs } from 'node:util'

const stages = ['EGG', 'BABY', 'JUVENILE', 'ADULT']
const styles = ['PIXEL', 'PAINT_2D']
const token = /^[A-Za-z0-9._-]{1,80}$/
const digest = /^[a-f0-9]{64}$/i
const relativeFile = /^[A-Za-z0-9_-]+(?:\/[A-Za-z0-9_-]+)*\.[A-Za-z0-9]+$/
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex')
const fail = reason => { throw new Error(`Approved ranch asset pack rejected: ${reason}`) }
const tuple = item => [item.speciesKey, item.variantKey, item.stage, item.renderStyle].join('\u0000')
function exact(value, keys) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
    || Object.keys(value).sort().join(',') !== [...keys].sort().join(',')) fail('schema fields')
}
function positive(value) { return Number.isSafeInteger(value) && value > 0 && value <= 2147483647 }
const verifiedInputs = new WeakMap()
function parsePack(bytes) {
  return JSON.parse(bytes.toString('utf8'), (_key, value, context) => {
    if (typeof value === 'number' && (!context?.source || !/^(0|[1-9][0-9]*)$/.test(context.source))) fail('integer JSON literal required')
    return value
  })
}
function hashFile(file, expected) {
  if (!digest.test(expected)) fail('explicit SHA256 is required')
  const bytes = readFileSync(file)
  if (sha256(bytes) !== expected.toLowerCase()) fail('file SHA256 mismatch')
  return bytes
}
function readPackFile(root, name, expected) {
  if (typeof name !== 'string' || !relativeFile.test(name) || name.includes('..')) fail('relative fixed file path')
  const file = realpathSync(path.join(root, name))
  const relative = path.relative(root, file)
  if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) fail('file escapes pack root')
  const bytes = hashFile(file, expected)
  if (bytes.length > 20 * 1024 * 1024) fail('asset exceeds verification bound')
  return bytes
}
function crc32(bytes) {
  let crc = 0xffffffff
  for (const byte of bytes) {
    crc ^= byte
    for (let bit = 0; bit < 8; bit++) crc = crc >>> 1 ^ (crc & 1 ? 0xedb88320 : 0)
  }
  return (crc ^ 0xffffffff) >>> 0
}
// 現在は静的8bit RGB/RGBA・非interlace PNGのみ。CRC、zlib、各scanlineを検証する。
function pngSize(bytes) {
  if (bytes.length < 33 || !bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10]))
    || bytes.readUInt32BE(8) !== 13 || bytes.toString('ascii', 12, 16) !== 'IHDR') fail('PNG atlas required')
  const width = bytes.readUInt32BE(16), height = bytes.readUInt32BE(20)
  const channels = bytes[25] === 2 ? 3 : bytes[25] === 6 ? 4 : 0
  if (!width || !height || bytes[24] !== 8 || !channels || bytes[26] || bytes[27] || bytes[28]) fail('static 8bit RGB/RGBA PNG required')
  const rowBytes = width * channels + 1, decodedBytes = rowBytes * height
  // 承認geometryの上限ではなく、この検証processの展開memoryを制限する。
  if (!Number.isSafeInteger(decodedBytes) || decodedBytes > 128 * 1024 * 1024) fail('PNG decoding memory bound')
  let offset = 8, ended = false, idatClosed = false
  const data = []
  while (offset + 12 <= bytes.length) {
    const length = bytes.readUInt32BE(offset), end = offset + length + 12
    if (end > bytes.length) fail('truncated PNG chunk')
    const kind = bytes.toString('ascii', offset + 4, offset + 8)
    if (!/^[A-Za-z]{4}$/.test(kind) || crc32(bytes.subarray(offset + 4, end - 4)) !== bytes.readUInt32BE(end - 4)) fail('PNG chunk CRC')
    if (kind === 'IHDR' && offset !== 8 || ['acTL', 'fcTL', 'fdAT'].includes(kind)) fail('nonstatic PNG')
    if (/^[A-Z]/.test(kind) && !['IHDR', 'PLTE', 'IDAT', 'IEND'].includes(kind)) fail('unknown critical PNG chunk')
    if (kind === 'PLTE' && (!length || length % 3 || length > 768 || data.length)) fail('PNG palette chunk')
    if (kind === 'tRNS' && (channels === 4 || length !== 6 || data.length)) fail('PNG transparency chunk')
    if (kind === 'IDAT') {
      if (idatClosed) fail('noncontiguous PNG IDAT')
      data.push(bytes.subarray(offset + 8, end - 4))
    } else if (data.length) idatClosed = true
    offset = end
    if (kind === 'IEND') {
      if (length || offset !== bytes.length) fail('PNG IEND boundary')
      ended = true; break
    }
  }
  if (!ended || !data.length) fail('PNG IDAT/IEND missing')
  const decoded = inflateSync(Buffer.concat(data), { maxOutputLength: decodedBytes })
  if (decoded.length !== decodedBytes) fail('PNG scanline length')
  for (let row = 0; row < height; row++) if (decoded[row * rowBytes] > 4) fail('PNG scanline filter')
  return { width, height }
}

/** 同一immutable artifactのhash・実bytes・全512tupleを検査し、出力前に全件を確定する。 */
export function verifyProductionPack({ packDirectory, manifestSha256, masterFile, masterSha256 }) {
  const root = realpathSync(packDirectory)
  const manifestBytes = hashFile(path.join(root, 'manifest.json'), manifestSha256)
  const manifest = parsePack(manifestBytes)
  exact(manifest, ['schemaVersion', 'catalogVersion', 'packVersion', 'approvalStatus', 'environment', 'entries'])
  if (manifest.schemaVersion !== 1 || !Number.isSafeInteger(manifest.catalogVersion) || manifest.catalogVersion < 1
    || !token.test(manifest.packVersion) || manifest.packVersion.includes('..')
    || manifest.approvalStatus !== 'APPROVED' || manifest.environment !== 'PRODUCTION'
    || !Array.isArray(manifest.entries) || manifest.entries.length !== 512) fail('unapproved or incomplete manifest')
  const master = JSON.parse(hashFile(masterFile, masterSha256).toString('utf8'))
  if (master.approved !== true || master.catalogVersion !== manifest.catalogVersion
    || !Array.isArray(master.assets) || master.assets.length !== 512) fail('master version or coverage')
  const masterTuples = new Map()
  for (const row of master.assets) {
    const key = tuple({ ...row, renderStyle: row.style })
    if (masterTuples.has(key)) fail('duplicate master tuple')
    masterTuples.set(key, row)
  }
  const keys = new Set(), tuples = new Set(), pairs = new Map(), files = new Map()
  const images = new Map()
  const image = (name, expected) => {
    if (!digest.test(expected)) fail('explicit SHA256 is required')
    const previous = images.get(name)
    if (previous) {
      if (previous.sha256 !== expected.toLowerCase()) fail('conflicting file hash')
      return previous
    }
    const bytes = readPackFile(root, name, expected)
    const verified = { bytes, size: pngSize(bytes), sha256: expected.toLowerCase() }
    images.set(name, verified)
    return verified
  }
  const entries = manifest.entries.map(item => {
    exact(item, ['speciesKey', 'variantKey', 'stage', 'renderStyle', 'assetKey', 'filePath', 'sourceSha256',
      'frameWidth', 'frameHeight', 'frameCount', 'fallbackPath', 'fallbackSha256'])
    if (![item.speciesKey, item.variantKey, item.assetKey].every(key => typeof key === 'string' && token.test(key)
      && !key.startsWith('DEV_')) || !stages.includes(item.stage) || !styles.includes(item.renderStyle)
      || !positive(item.frameWidth) || !positive(item.frameHeight) || !positive(item.frameCount)
      || item.renderStyle === 'PIXEL' && (item.frameWidth !== 96 || item.frameHeight !== 96)) fail('entry identity or geometry')
    const identity = tuple(item)
    if (tuples.has(identity) || keys.has(item.assetKey)) fail('duplicate tuple or assetKey')
    tuples.add(identity); keys.add(item.assetKey)
    if (!pairs.has(item.speciesKey)) pairs.set(item.speciesKey, new Set())
    pairs.get(item.speciesKey).add(item.variantKey)
    const masterRow = masterTuples.get(identity)
    if (!masterRow || masterRow.assetKey !== item.assetKey
      || masterRow.sha256?.toLowerCase() !== item.sourceSha256?.toLowerCase()
      || masterRow.staticFallbackSha256?.toLowerCase() !== item.fallbackSha256?.toLowerCase()) fail('master tuple/hash mismatch')
    const sourceImage = image(item.filePath, item.sourceSha256), fallbackImage = image(item.fallbackPath, item.fallbackSha256)
    const source = sourceImage.bytes, fallback = fallbackImage.bytes
    const size = sourceImage.size, fallbackSize = fallbackImage.size
    if (size.width % item.frameWidth || size.height % item.frameHeight
      || size.width / item.frameWidth * (size.height / item.frameHeight) !== item.frameCount
      || fallbackSize.width !== item.frameWidth || fallbackSize.height !== item.frameHeight) fail('atlas/fallback geometry mismatch')
    for (const [name, bytes] of [[item.filePath, source], [item.fallbackPath, fallback]]) {
      if (files.has(name) && !files.get(name).equals(bytes)) fail('conflicting file path')
      files.set(name, bytes)
    }
    if ([...files.values()].reduce((total, bytes) => total + bytes.length, 0) > 256 * 1024 * 1024) fail('pack exceeds verification memory bound')
    return { assetKey: item.assetKey, speciesKey: item.speciesKey, variantKey: item.variantKey,
      stage: item.stage, renderStyle: item.renderStyle, catalogVersion: String(manifest.catalogVersion),
      approval: 'APPROVED', origin: 'PRODUCTION', src: `/ranch/approved/${manifest.packVersion}/${item.filePath}`,
      fallbackSrc: `/ranch/approved/${manifest.packVersion}/${item.fallbackPath}`,
      sourceWidth: size.width, sourceHeight: size.height,
      columnBoundaries: Array.from({ length: size.width / item.frameWidth + 1 }, (_, index) => index * item.frameWidth),
      rowBoundaries: Array.from({ length: size.height / item.frameHeight + 1 }, (_, index) => index * item.frameHeight),
      frames: item.frameCount, sourceSha256: item.sourceSha256.toLowerCase() }
  })
  if (pairs.size !== 16 || [...pairs.values()].some(variants => variants.size !== 4)) fail('16 species × 4 variants required')
  for (const [species, variants] of pairs) for (const variant of variants) for (const stage of stages) for (const style of styles) {
    if (!tuples.has(tuple({ speciesKey: species, variantKey: variant, stage, renderStyle: style }))) fail('missing stage/style tuple')
  }
  files.set('manifest.json', manifestBytes)
  const actualFiles = listFiles(root)
  if (actualFiles.length !== files.size || actualFiles.some(file => !files.has(file))) fail('pack file set differs from manifest')
  const verified = Object.freeze({ entries: Object.freeze(entries.map(entry => Object.freeze({ ...entry,
    columnBoundaries: Object.freeze(entry.columnBoundaries), rowBoundaries: Object.freeze(entry.rowBoundaries) }))),
    packVersion: manifest.packVersion, catalogVersion: String(manifest.catalogVersion),
    manifestSha256: sha256(manifestBytes), masterSha256: masterSha256.toLowerCase() })
  // 出力関数に未検証objectを渡したり、検証後bytesを差し替えたりできないよう私有する。
  verifiedInputs.set(verified, files)
  return verified
}

/** 検証済みbytesを両アプリへ配置する。異なる既存packの上書きは拒否する。 */
export function installVerifiedPack(verified, repository) {
  const files = verifiedInputs.get(verified)
  if (!files) fail('unverified installation input')
  const root = realpathSync(repository)
  if (!token.test(verified.packVersion) || verified.packVersion.includes('..')) fail('output pack version')
  const frontend = path.join(root, 'frontend/public/ranch/approved', verified.packVersion)
  const backend = path.join(root, 'backend/src/main/resources/ranch/approved/asset-packs', verified.packVersion)
  const destinations = [frontend, backend]
  const moduleFile = path.join(root, 'frontend/app/utils/ranch-production-assets.ts')
  requireOutputInside(root, moduleFile)
  for (const output of destinations) for (const [name, bytes] of files) {
    if (!relativeFile.test(name) || name.includes('..')) fail('output relative file path')
    const file = path.join(output, name)
    requireOutputInside(root, file)
    if (existsSync(file) && !readFileSync(file).equals(bytes)) fail('existing pack has different bytes')
  }
  for (const output of destinations) if (existsSync(output)) {
    const existing = listFiles(output)
    if (existing.length !== files.size || existing.some(file => !files.has(file))) fail('existing pack file set differs')
  }
  const metadata = { packVersion: verified.packVersion, catalogVersion: verified.catalogVersion,
    manifestSha256: verified.manifestSha256, masterSha256: verified.masterSha256 }
  const module = `// 承認pack検証CLIが同一artifactから生成。恐竜素材の承認そのものを代行しない。\nimport type { RanchAssetEntry } from './ranch-assets'\nconst entries = ${JSON.stringify(verified.entries, null, 2)} as const satisfies readonly RanchAssetEntry[]\nexport const productionRanchAssets: readonly RanchAssetEntry[] = Object.freeze(entries.map(entry => Object.freeze({ ...entry, columnBoundaries: Object.freeze(entry.columnBoundaries), rowBoundaries: Object.freeze(entry.rowBoundaries) })))\nexport const productionRanchPack = Object.freeze(${JSON.stringify(metadata)})\n`
  const stageParent = path.join(root, 'work')
  requireOutputInside(root, stageParent); mkdirSync(stageParent, { recursive: true })
  const stage = mkdtempSync(path.join(stageParent, 'ranch-approved-pack-'))
  const stagedPacks = destinations.map((_, index) => path.join(stage, String(index)))
  for (const output of stagedPacks) for (const [name, bytes] of files) {
    const file = path.join(output, name)
    mkdirSync(path.dirname(file), { recursive: true }); writeFileSync(file, bytes, { flag: 'wx' })
  }
  // TSは同directoryのstageへ完成させ、両namespaceの配置後に原子的に置換する。
  // 途中失敗でも旧TSは旧正当packのまま、新packがreadyと見えることはない。
  const stagedModule = path.join(path.dirname(moduleFile), `.ranch-production-assets-${randomUUID()}.tmp`)
  requireOutputInside(root, stagedModule); writeFileSync(stagedModule, module, { flag: 'wx' })
  for (let index = 0; index < destinations.length; index++) {
    const output = destinations[index]
    if (!existsSync(output)) {
      requireOutputInside(root, output)
      mkdirSync(path.dirname(output), { recursive: true }); renameSync(stagedPacks[index], output)
    }
  }
  renameSync(stagedModule, moduleFile)
}

function listFiles(root, prefix = '', collected = []) {
  if (prefix.split('/').length > 64 || collected.length > 4096) fail('pack traversal memory bound')
  for (const entry of readdirSync(path.join(root, prefix), { withFileTypes: true })) {
    const name = prefix ? `${prefix}/${entry.name}` : entry.name
    if (entry.isDirectory()) listFiles(root, name, collected)
    else if (entry.isFile()) collected.push(name)
    else fail('pack symlink or special file')
  }
  return collected
}

function requireOutputInside(root, file) {
  let existing = file
  while (!existsSync(existing)) {
    const parent = path.dirname(existing)
    if (parent === existing) fail('output parent absent')
    existing = parent
  }
  const relative = path.relative(root, realpathSync(existing))
  if (relative.startsWith('..') || path.isAbsolute(relative)) fail('output escapes repository')
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const { values } = parseArgs({ options: { pack: { type: 'string' }, 'manifest-sha256': { type: 'string' },
    master: { type: 'string' }, 'master-sha256': { type: 'string' }, repository: { type: 'string' } } })
  if (!values.pack || !values['manifest-sha256'] || !values.master || !values['master-sha256']) fail('explicit pack/master inputs required')
  const verified = verifyProductionPack({ packDirectory: values.pack, manifestSha256: values['manifest-sha256'],
    masterFile: values.master, masterSha256: values['master-sha256'] })
  if (values.repository) installVerifiedPack(verified, values.repository)
  process.stdout.write(`${JSON.stringify({ packVersion: verified.packVersion, manifestSha256: verified.manifestSha256,
    catalogVersion: verified.catalogVersion, verifiedTuples: verified.entries.length, installed: Boolean(values.repository) })}\n`)
}
