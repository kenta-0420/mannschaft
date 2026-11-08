// 正式v2の512tuple内4動作契約。PNG/hash/pathの検査は既存pack検証器が担う。
export const V2_ENTRY_FIELDS = ['sourceSpeciesKey', 'habitat', 'logicalCanvas', 'careSpriteRect', 'rendererProfile', 'clips']
export const CLIP_FIELDS = ['filePath', 'sourceSha256', 'frameWidth', 'frameHeight', 'frameCount', 'durationMs', 'phaseStarts', 'phaseMs', 'loop', 'returnTo', 'floorByFrame', 'sourceFloor384', 'sourceFloorByFrame384', 'tailMarks', 'shadowByFrame']
export function verifyClipEntry(item, packVersion, { exact, fail, image, files }) {
  const same = (actual, expected) => JSON.stringify(actual) === JSON.stringify(expected)
  const finite = (value, min, max) => typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max
  const shape = (value, expected) => {
    exact(value, Object.keys(expected))
    return Object.entries(expected).every(([key, number]) => value[key] === number)
  }
  const cell = item.renderStyle === 'PIXEL' ? 96 : 384
  if (item.frameWidth !== cell || item.frameHeight !== cell || item.sourceSpeciesKey !== item.speciesKey
    || !['LAND', 'SEA', 'AIR'].includes(item.habitat) || !/^V[1-4]$/.test(item.variantKey)) fail('v2 identity or cell')
  if (item.stage === 'EGG') {
    if (item.rendererProfile !== 'COMMON_EGG_SVG_V1' || item.clips !== null || item.frameCount !== 1
      || item.filePath !== item.fallbackPath || item.sourceSha256 !== item.fallbackSha256
      || !shape(item.logicalCanvas, { width: 96, height: 96 })
      || !shape(item.careSpriteRect, { x: 0, y: 0, width: 96, height: 96 })) fail('v2 egg geometry/profile')
    return null
  }
  if (item.rendererProfile !== 'APPROVED_APPEARANCE_V4' || item.frameCount !== 4
    || !shape(item.logicalCanvas, { width: 112, height: 112 })
    || !shape(item.careSpriteRect, { x: 8, y: 8, width: 96, height: 96 })) fail('v2 clip geometry/profile')
  exact(item.clips, ['idle', 'walk', 'feed', 'happy'])
  const imageValue = (filePath, sourceSha256) => {
    const verified = image(filePath, sourceSha256)
    files.set(filePath, verified.bytes)
    return { src: `/ranch/approved/${packVersion}/${filePath}`, sourcePath: filePath,
      sourceSha256: sourceSha256.toLowerCase(), sourceWidth: verified.size.width, sourceHeight: verified.size.height }
  }
  const nullableFour = (value, check) => value === null || Array.isArray(value) && value.length === 4 && value.every(check)
  const floor = value => value === null || finite(value, 0, 384)
  const point = value => {
    exact(value, ['x', 'y'])
    return finite(value.x, 0, 112) && finite(value.y, 0, 112)
  }
  const shadow = value => {
    exact(value, ['x', 'y', 'radius'])
    return finite(value.x, 0, 112) && finite(value.y, 0, 112) && finite(value.radius, Number.MIN_VALUE, 112)
  }
  const clips = {}
  for (const action of ['idle', 'walk', 'feed', 'happy']) {
    const clip = item.clips[action]
    exact(clip, CLIP_FIELDS)
    if (clip.frameWidth !== cell || clip.frameHeight !== cell || clip.frameCount !== 4
      || !Number.isSafeInteger(clip.durationMs) || clip.durationMs < 1 || clip.durationMs > 60000
      || !Array.isArray(clip.phaseStarts) || clip.phaseStarts.length !== 4
      || !Array.isArray(clip.phaseMs) || clip.phaseMs.length !== 4 || clip.phaseStarts[0] !== 0
      || clip.loop !== (action === 'idle' || action === 'walk')
      || clip.returnTo !== (clip.loop ? null : 'idle')
      || !floor(clip.sourceFloor384) || !nullableFour(clip.floorByFrame, floor)
      || !nullableFour(clip.sourceFloorByFrame384, floor)
      || !nullableFour(clip.tailMarks, point) || !nullableFour(clip.shadowByFrame, shadow)) fail('v2 clip shape/timing')
    for (let index = 0; index < 4; index++) {
      const start = clip.phaseStarts[index], next = clip.phaseStarts[index + 1] ?? 1
      if (!finite(start, 0, 1) || start >= 1 || next <= start
        || !finite(clip.phaseMs[index], Number.MIN_VALUE, 60000)
        || Math.abs(clip.phaseMs[index] - (next - start) * clip.durationMs) > 1e-6) fail('v2 phase order/duration')
    }
    const source = imageValue(clip.filePath, clip.sourceSha256)
    if (source.sourceWidth !== cell * 4 || source.sourceHeight !== cell) fail('v2 atlas geometry')
    const { filePath, frameWidth, frameHeight, frameCount, ...metadata } = clip
    clips[action] = { ...metadata, ...source, action, cell, frames: 4 }
  }
  const idle = item.clips.idle
  if (idle.filePath !== item.filePath || idle.sourceSha256 !== item.sourceSha256
    || idle.frameWidth !== item.frameWidth || idle.frameHeight !== item.frameHeight || idle.frameCount !== item.frameCount) fail('v2 idle anchor mismatch')
  return { assetKey: item.assetKey, speciesKey: item.speciesKey, variantKey: item.variantKey,
    stage: item.stage, renderStyle: item.renderStyle, catalogVersion: null,
    approval: 'APPROVED', origin: 'PRODUCTION', sourceSpecies: item.sourceSpeciesKey,
    habitat: item.habitat, logicalCanvas: item.logicalCanvas, careSpriteRect: item.careSpriteRect,
    rendererProfile: item.rendererProfile, fallback: imageValue(item.fallbackPath, item.fallbackSha256), clips }
}
