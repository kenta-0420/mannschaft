import fs from 'node:fs';
import vm from 'node:vm';

const prototypeDirectory = new URL('./', import.meta.url);
const dataSource = fs.readFileSync(new URL('beta-inventory-board-data.js', prototypeDirectory), 'utf8');
const html = fs.readFileSync(new URL('beta-inventory-board.html', prototypeDirectory), 'utf8');
const taskList = fs.readFileSync(new URL('../task-list.md', prototypeDirectory), 'utf8');
const b0PlanSource = JSON.parse(fs.readFileSync(new URL('beta-inventory-board-b0-alicization.json', prototypeDirectory), 'utf8'));
const context = { window: {} };
vm.runInNewContext(dataSource, context, { filename: 'beta-inventory-board-data.js' });

const data = context.window.BETA_INVENTORY_DATA;
if (!data?.verification?.passed) throw new Error('正本データの検算が完了していません。');
if (data.features.length !== data.sourceCounts.features) throw new Error('機能件数が一致しません。');
if (!Array.isArray(data.capabilities) || data.capabilities.length <= data.features.length) throw new Error('能力単位の表示データが親43件から展開されていません');
if (data.sourceCounts.features !== data.verification.raw.features) throw new Error('正本親feature件数の集計がraw件数と一致しません');
if (data.sourceCounts.capabilities !== data.capabilities.length) throw new Error('能力件数の集計が不一致です');
if (new Set(data.capabilities.map((item) => item.key)).size !== data.capabilities.length) throw new Error('能力keyが重複しています');
const parentKeys = new Set(data.features.map((item) => item.key));
if (data.capabilities.some((item) => !parentKeys.has(item.parentFeatureKey))) throw new Error('能力の親feature_keyが正本にありません');
const splitParents = new Set(data.capabilities.filter((item) => item.isCapability).map((item) => item.parentFeatureKey));
for (const parent of ['account-settings', 'auth', 'organization-manage', 'organization-members', 'notification-inbox', 'pointcard', 'tournament', 'facility', 'corkboard', 'property-repairplan', 'weather-health', 'billing-payment', 'shift', 'skill-resume', 'succession-proxy', 'translation-search', 'todo-memo', 'promotion', 'workflow-forms', 'family-care', 'moderation-incident', 'webhook-sync', 'gamification', 'team-create', 'team-invite', 'team-admin', 'team-modules', 'village-join', 'village-members', 'village-events', 'survey']) {
  if (!splitParents.has(parent)) throw new Error(`複合親が未分割です: ${parent}`);
}
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
// 同期失敗時は外部APIの古いスナップショットを保持する。成功扱いでの不一致だけを回帰として検出する。
if (github.status === 'synced' && github.lastAttempt?.status !== 'error' && JSON.stringify(actualGithubNumbers) !== JSON.stringify(expectedGithubNumbers)) throw new Error('GitHubスナップショットの参照集合が正本と不一致です');
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
if (decisions.capabilityOverrides && Object.keys(decisions.capabilityOverrides).some((key) => !data.capabilities.some((item) => item.key === key))) throw new Error('capabilityOverridesに存在しない能力keyがあります');
if (Object.keys(decisions.capabilities || {}).length !== data.capabilities.length) throw new Error('能力単位のPhase 2分類が表示能力と一致しません');
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
for (const capability of data.capabilities) {
  const decision = decisions.capabilities[capability.key];
  if (!decision || !['proposed', 'confirmed'].includes(decision.decisionStatus) || !decision.reason) throw new Error(`能力単位のdecisionがありません: ${capability.key}`);
  if (capability.isCapability && (!capability.parentFeatureKey || !capability.statusSource.includes('子能力未実測'))) throw new Error(`親由来状態の明示がありません: ${capability.key}`);
}

const b0Plan = data.b0Alicization;
if (JSON.stringify(b0Plan) !== JSON.stringify(b0PlanSource)) throw new Error('B0アリシゼーション計画と生成データが不一致です');
if (!b0Plan || b0Plan.stage !== 'B0' || b0Plan.status !== 'planned') throw new Error('B0アリシゼーション計画が未定義または未実測状態でありません');
const b0CapabilityKeys = new Set(data.capabilities.map((capability) => capability.key));
for (const journey of b0Plan.journeys || []) {
  if (!['planned', 'runnable', 'running', 'passed', 'failed'].includes(journey.status)) throw new Error(`B0 journey状態が不正です: ${journey.id}`);
  if ((journey.capabilities || []).some((key) => !b0CapabilityKeys.has(key))) throw new Error(`B0 journeyの能力keyが未分割です: ${journey.id}`);
  for (const key of journey.capabilities || []) {
    const decision = decisions.capabilities[key];
    if (!decision || decision.stage !== 'B0' || decision.priority !== 'must') throw new Error(`B0 journey能力がB0/mustではありません: ${journey.id} / ${key}`);
  }
}
if (!b0CapabilityKeys.has('village-events-attendance-response') || !b0CapabilityKeys.has('village-events-attendance-summary')) throw new Error('出欠回答・集計の分割能力がありません');
if (!b0CapabilityKeys.has('survey-response') || !b0CapabilityKeys.has('survey-results')) throw new Error('アンケート回答・結果の分割能力がありません');
if ((b0Plan.journeys || []).some((journey) => (journey.capabilities || []).includes('reservation'))) throw new Error('予約を出欠journeyに含めています');
for (const key of ['timeline-post', 'timeline-view', 'timeline-sharing', 'notification-inbox-notification-delivery', 'notification-inbox-inbox', 'todo-memo-todo-create', 'todo-memo-todo-share', 'todo-memo-memo-quick-create', 'todo-memo-memo-view', 'village-events-calendar-view', 'village-events-calendar-sharing-level', 'village-events-calendar-visibility-boundary']) {
  if (!b0CapabilityKeys.has(key)) throw new Error(`B0共有機能の能力がありません: ${key}`);
  const decision = decisions.capabilities[key];
  if (!decision || decision.stage !== 'B0' || decision.priority !== 'must' || !['proposed', 'confirmed'].includes(decision.decisionStatus || decisions.decisionStatusDefault) || !decision.reason) throw new Error(`B0共有機能の判断がB0/must/reason非空ではありません: ${key}`);
  if (!(b0Plan.journeys || []).some((journey) => (journey.capabilities || []).includes(key))) throw new Error(`B0 journeyに共有機能がありません: ${key}`);
}
if ((b0Plan.journeys || []).some((journey) => (journey.capabilities || []).some((key) => key.startsWith('village-events-calendar-') && key.includes('attendance')))) throw new Error('カレンダー能力に出欠能力を混在させないでください');
const allowedStatuses = new Set(['blocked', 'unknown', 'incomplete', 'verifying', 'ready']);
for (const [key, decision] of Object.entries(decisions.features || {}).concat(Object.entries(decisions.capabilityOverrides || {}))) {
  if (decision.decisionStatus === 'confirmed') {
    if (decision.confirmedBy !== 'user' || !decision.confirmedAt || Number.isNaN(Date.parse(decision.confirmedAt)) || !decision.confirmationNote?.trim()) {
      throw new Error(`confirmed判断にはconfirmedBy=user、confirmedAt、confirmationNoteが必要です: ${key}`);
    }
  }
}
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
