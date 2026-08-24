import fs from 'node:fs';
import vm from 'node:vm';

const prototypeDirectory = new URL('./', import.meta.url);
const dataSource = fs.readFileSync(new URL('beta-inventory-board-data.js', prototypeDirectory), 'utf8');
const html = fs.readFileSync(new URL('beta-inventory-board.html', prototypeDirectory), 'utf8');
const taskList = fs.readFileSync(new URL('../task-list.md', prototypeDirectory), 'utf8');
const context = { window: {} };
vm.runInNewContext(dataSource, context, { filename: 'beta-inventory-board-data.js' });

const data = context.window.BETA_INVENTORY_DATA;
if (!data?.verification?.passed) throw new Error('正本データの検算が完了していません。');
if (data.features.length !== data.sourceCounts.features) throw new Error('機能件数が一致しません。');
if (data.campaigns.length !== data.sourceCounts.campaigns) throw new Error('CMP件数が一致しません。');
if (new Set(data.features.map((feature) => feature.key)).size !== data.features.length) throw new Error('feature_keyが重複しています。');
if (new Set(data.campaigns.map((campaign) => campaign.id)).size !== data.campaigns.length) throw new Error('CMP IDが重複しています。');

const sourceRefs = new Map();
for (const line of taskList.split(/\r?\n/)) {
  const match = line.match(/^\|\s*(CMP(?:-|$).*?)\s*\|/);
  if (!match) continue;
  sourceRefs.set(match[1], [...new Set([...line.matchAll(/(?<![A-Za-z0-9])#(\d+)/g)].map((item) => Number(item[1])).filter((number) => number >= 100))].sort((a, b) => a - b));
}
if (sourceRefs.size !== data.campaigns.length) throw new Error('CMP正本行数と参照抽出数が一致しません');
for (const campaign of data.campaigns) {
  const expected = sourceRefs.get(campaign.id);
  if (!expected || JSON.stringify(expected) !== JSON.stringify(campaign.githubRefs || [])) throw new Error(`CMPのGitHub参照が不一致です: ${campaign.id}`);
}
const github = data.githubSync || {};
const expectedGithubNumbers = [...new Set([...sourceRefs.values()].flat())].sort((a, b) => a - b).map(String);
const actualGithubNumbers = Object.keys(github.items || {}).sort((a, b) => Number(a) - Number(b));
if (github.status === 'synced' && JSON.stringify(actualGithubNumbers) !== JSON.stringify(expectedGithubNumbers)) throw new Error('GitHubスナップショットの参照集合が正本と不一致です');
const allowedGithubKinds = new Set(['issue', 'pull_request', 'missing', 'unsynced']);
const allowedCiStatuses = new Set(['success', 'failure', 'pending', 'empty', 'unavailable']);
for (const item of Object.values(github.items || {})) {
  if (!allowedGithubKinds.has(item.kind)) throw new Error(`GitHub区分が不正です: ${item.kind}`);
  if (!['open', 'closed', 'merged', '', 'unknown'].includes(item.state)) throw new Error(`GitHub stateが不正です: ${item.state}`);
  if (item.kind === 'pull_request' && !allowedCiStatuses.has(item.ci?.status)) throw new Error(`PRのCI状態が不正です: #${item.number}`);
}
if (github.status === 'synced' && !github.synchronizedAt) throw new Error('同期済みなのに同期時刻がありません');

const gateItems = data.gateFoundation;
if (!Array.isArray(gateItems) || gateItems.length === 0) throw new Error('Gate overlayが空です。');
if (new Set(gateItems.map((item) => item.id)).size !== gateItems.length) throw new Error('Gate IDが重複しています。');
const allowedGateStatuses = new Set(['done', 'working', 'blocked', 'unknown']);
const allowedGateDecisionStatuses = new Set(['proposed', 'confirmed']);
for (const item of gateItems) {
  if (!item.id || !item.title || !item.detail || !Array.isArray(item.sourceRefs) || item.sourceRefs.length === 0) {
    throw new Error(`Gateの根拠または表示項目がありません: ${item.id}`);
  }
  if (!allowedGateStatuses.has(item.status)) throw new Error(`Gate statusが不正です: ${item.id}`);
  if (!allowedGateDecisionStatuses.has(item.decisionStatus)) throw new Error(`Gate decisionStatusが不正です: ${item.id}`);
  if (item.status !== 'unknown' && (!Array.isArray(item.evidence) || item.evidence.length === 0)) {
    throw new Error(`Gateの判定にevidenceがありません: ${item.id}`);
  }
}

const decisions = data.decisions;
if (!decisions || Object.keys(decisions.features || {}).length !== data.features.length) throw new Error('Phase 2分類が43機能と一致しません');
const allowedStages = new Set(['B0', 'B1', 'B2', 'B3', 'B4']);
const allowedAudiences = new Set(['soccer', 'alumni', 'both']);
const allowedPriorities = new Set(['must', 'should', 'could', 'defer']);
const allowedDecisionStatuses = new Set(['proposed', 'confirmed']);
for (const feature of data.features) {
  const decision = decisions.features[feature.key];
  if (!decision) throw new Error(`Phase 2分類なし: ${feature.key}`);
  if (!allowedStages.has(decision.stage) || !allowedAudiences.has(decision.audience) || !allowedPriorities.has(decision.priority)) throw new Error(`Phase 2分類の許可値不正: ${feature.key}`);
  if (!allowedDecisionStatuses.has(decision.decisionStatus || decisions.decisionStatusDefault) || !decision.reason) throw new Error(`Phase 2分類の根拠または状態なし: ${feature.key}`);
}

const allowedStatuses = new Set(['blocked', 'unknown', 'incomplete', 'verifying', 'ready']);
if (data.features.some((feature) => !allowedStatuses.has(feature.status))) throw new Error('公式5状態以外の機能があります。');

const campaignTagByStatus = { blocked: '停止中', working: '進行中', done: '完了', unknown: '未整理' };
if (data.campaigns.some((campaign) => campaign.tags?.length !== 1 || campaign.tags[0] !== campaignTagByStatus[campaign.status])) {
  throw new Error('CMPの進捗タグと正規化状態が一致しません。');
}

const inlineScripts = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)]
  .map((match) => match[1])
  .filter((source) => source.trim());
inlineScripts.forEach((source) => new Function(source));

for (const label of ['不備あり', '未棚卸', '実装未完', '検証中', 'β準備完了']) {
  if (!html.includes(label)) throw new Error(`5状態レーンが不足しています: ${label}`);
}

console.log(JSON.stringify({
  features: data.features.length,
  campaigns: data.campaigns.length,
  core: data.features.filter((feature) => feature.classification === 'core').length,
  noncore: data.features.filter((feature) => feature.classification === 'noncore').length,
  blockers: data.sourceCounts.blockers,
  coreStatus: data.sourceCounts.coreStatus,
}, null, 2));
