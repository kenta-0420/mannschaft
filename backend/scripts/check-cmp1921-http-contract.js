// CMP1921: 実 Boot の HTTP 契約を有限確認する CI 専用試験。
// fixture は既 seed の所有。SQL は read-only、通常 login 以外は GET のみ。
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');

const sha = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
const invariant = (ok, label) => {
  if (!ok) { const error = new Error(label); error.failureCode = label; throw error; }
};
const git = (...args) => execFileSync('git', args, { encoding: 'utf8' }).trim();
const labels = ['member', 'admin', 'outsider'];
const receipt = { state: 'STARTED', requests: [], limitations: [
  'ブラウザ・住民・UI直接URLは未検証', 'admin fixture は SYSTEM_ADMIN 兼任、pure ADMIN は未検証',
] };
let output;
let privateDir;

function setup() {
  invariant(process.env.GITHUB_ACTIONS === 'true' && process.env.CI === 'true', 'CI 専用');
  const base = new URL(process.env.CMP1921_BASE_URL || 'http://localhost:8080');
  invariant(base.origin === 'http://localhost:8080' && base.pathname === '/' && !base.username && !base.password && !base.search && !base.hash, 'localhost CI 以外は拒否');
  for (const key of ['GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT']) invariant(/^\d+$/.test(process.env[key] || ''), 'run metadata 不足');
  const checkout = git('rev-parse', 'HEAD');
  invariant(checkout === process.env.GITHUB_SHA, 'checkout と workflow SHA 不一致');
  invariant(/^[a-f0-9]{40}$/.test(process.env.CMP1921_PR_HEAD || ''), 'head metadata 不足');
  receipt.binding = {
    repository: process.env.GITHUB_REPOSITORY, runId: process.env.GITHUB_RUN_ID,
    attempt: process.env.GITHUB_RUN_ATTEMPT, event: process.env.GITHUB_EVENT_NAME,
    head: process.env.CMP1921_PR_HEAD, checkout, tree: git('rev-parse', 'HEAD^{tree}'),
    parents: git('show', '-s', '--format=%P', 'HEAD'), profile: 'ci',
    workflow: process.env.GITHUB_WORKFLOW, workflowSha: process.env.GITHUB_WORKFLOW_SHA,
    artifact: `cmp1921-http-contract-${process.env.GITHUB_RUN_ID}-${process.env.GITHUB_RUN_ATTEMPT}`,
    scriptSha256: sha(fs.readFileSync(__filename)),
  };
  if (receipt.binding.event === 'pull_request') {
    invariant(receipt.binding.parents.split(' ').includes(receipt.binding.head), 'PR head と merge checkout parent 不一致');
  } else invariant(receipt.binding.head === checkout, '非 PR head と checkout 不一致');
  receipt.sourcePins = [
    '.github/workflows/e2e-real-smoke.yml', 'backend/scripts/seed-e2e-data.js',
    'backend/scripts/check-cmp1921-http-contract.js',
    'backend/src/main/java/com/mannschaft/app/config/OpenApiConfig.java',
    'backend/src/main/java/com/mannschaft/app/match/controller/MatchRecordController.java',
    'backend/src/main/java/com/mannschaft/app/match/controller/MatchStatsController.java',
    'backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryTemplateController.java',
  ].map(relative => {
    const source = execFileSync('git', ['show', `HEAD:${relative}`]);
    invariant(sha(fs.readFileSync(path.resolve(__dirname, '../..', relative))) === sha(source), 'checkout source bytes 不一致');
    return { path: relative, blob: git('rev-parse', `HEAD:${relative}`), bytes: source.length, sha256: sha(source) };
  });
  const temp = fs.realpathSync(process.env.RUNNER_TEMP);
  output = path.join(temp, 'cmp1921-http-contract');
  privateDir = path.join(temp, `cmp1921-http-private-${receipt.binding.runId}-${receipt.binding.attempt}`);
  // 再実行時の原本上書きは拒否。失敗 body は privateDir にだけ残す。
  fs.mkdirSync(output, { mode: 0o700 });
  fs.mkdirSync(privateDir, { mode: 0o700 });
  fs.mkdirSync(path.join(output, 'raw'), { mode: 0o700 });
  return base;
}

async function fixture() {
  const mysql = require('mysql2/promise');
  const db = await mysql.createConnection({ host: '127.0.0.1', port: 3306,
    user: 'mannschaft', password: 'mannschaft', database: 'mannschaft',
    connectTimeout: 10000, supportBigNumbers: true, bigNumberStrings: true });
  try {
    await db.query('START TRANSACTION READ ONLY');
    const query = async (sql, values = []) => (await db.execute({ sql, timeout: 10000 }, values))[0];
    const one = async (sql, values, label) => {
      const rows = await query(sql, values); invariant(rows.length === 1, label); return rows[0];
    };
    const team = await one('SELECT id, slug FROM teams WHERE name = ? AND lifecycle_status = ? AND deleted_at IS NULL', ['FC東京U-18（テスト）', 'ACTIVE'], 'team fixture 不一致');
    const org = await one('SELECT id, slug FROM organizations WHERE name = ? AND lifecycle_status = ? AND deleted_at IS NULL', ['FC東京ユースアカデミー（テスト）', 'ACTIVE'], 'org fixture 不一致');
    const jfa = await one('SELECT id, slug FROM organizations WHERE name = ? AND lifecycle_status = ? AND deleted_at IS NULL', ['日本サッカー協会（テスト）', 'ACTIVE'], 'JFA fixture 不一致');
    for (const scope of [team, org, jfa]) {
      invariant(/^\d+$/.test(String(scope.id)) && /^[a-z0-9-]+$/.test(scope.slug), 'scope identifier 不正');
    }
    // 現 scope に public_id はない。UUID形式の未登録slugを負例とする。
    const uuidNegative = '00000000-0000-4000-8000-000000001921';
    const teamUuid = await query('SELECT COUNT(*) AS count FROM teams WHERE slug = ?', [uuidNegative]);
    const orgUuid = await query('SELECT COUNT(*) AS count FROM organizations WHERE slug = ?', [uuidNegative]);
    invariant(Number(teamUuid[0].count) === 0 && Number(orgUuid[0].count) === 0 && team.slug !== uuidNegative && org.slug !== uuidNegative, 'UUID 未登録slug precondition 不成立');
    await one('SELECT team_id FROM team_org_memberships WHERE team_id = ? AND organization_id = ? AND status = ?', [team.id, org.id, 'ACTIVE'], 'direct org pair 不一致');
    invariant((await query('SELECT team_id FROM team_org_memberships WHERE team_id = ? AND organization_id = ? AND status = ?', [team.id, jfa.id, 'ACTIVE'])).length === 0, 'JFA 負例 precondition 不成立');
    const users = {};
    for (const label of labels) {
      const name = label === 'member' ? 'user' : label;
      users[label] = await one('SELECT id FROM users WHERE email = ? AND status = ?', [`e2e-${name}@test.mannschaft.local`, 'ACTIVE'], 'user fixture 不一致');
    }
    invariant(new Set(labels.map(label => String(users[label].id))).size === 3, 'actor fixture が重複');
    for (const label of ['member', 'admin']) await one('SELECT role_kind FROM memberships WHERE user_id = ? AND scope_type = ? AND scope_id = ? AND left_at IS NULL AND role_kind = ?', [users[label].id, 'TEAM', team.id, 'MEMBER'], 'target membership 不一致');
    invariant((await query('SELECT id FROM memberships WHERE user_id = ? AND left_at IS NULL', [users.outsider.id])).length === 0, 'outsider membership が存在');
    invariant((await query('SELECT id FROM user_roles WHERE user_id = ?', [users.outsider.id])).length === 0, 'outsider role が存在');
    const roles = await query('SELECT r.name, ur.team_id, ur.organization_id FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ?', [users.admin.id]);
    invariant(roles.some(r => r.name === 'SYSTEM_ADMIN') && roles.some(r => r.name === 'ADMIN' && String(r.team_id) === String(team.id)) && roles.some(r => r.name === 'ADMIN' && String(r.organization_id) === String(jfa.id)), 'admin 兼任 fixture 不一致');
    const memberRoles = await query('SELECT r.name, ur.team_id, ur.organization_id FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ?', [users.member.id]);
    invariant(!memberRoles.some(r => r.name === 'SYSTEM_ADMIN' || (String(r.team_id) === String(team.id) && ['ADMIN', 'DEPUTY_ADMIN'].includes(r.name))), 'member target role precondition 不成立');
    // 空 seed fixture に固定。非空 DB の氏名・試合情報を artifact に出さない。
    const matches = await query('SELECT COUNT(*) AS count FROM matches WHERE team_id = ? AND deleted_at IS NULL', [team.id]);
    const templates = await query('SELECT COUNT(*) AS count FROM tournament_entry_templates WHERE team_id = ? AND deleted_at IS NULL', [team.id]);
    invariant(Number(matches[0].count) === 0 && Number(templates[0].count) === 0, '空 fixture precondition 不成立');
    receipt.fixture = { team, org, jfa, users, adminRoles: roles, memberRoles, uuidNegative, matches: 0, templates: 0 };
    return receipt.fixture;
  } finally {
    await db.rollback();
    await db.end();
  }
}

async function login(base, label) {
  const name = label === 'member' ? 'user' : label;
  const response = await fetch(new URL('/api/v1/auth/login', base), {
    method: 'POST', redirect: 'error', signal: AbortSignal.timeout(15000),
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: `e2e-${name}@test.mannschaft.local`, password: 'TestPass2026!' }),
  });
  // login body/header/cookie はファイル・console・receipt に保存しない。
  invariant(response.status === 200, `login ${label} status 不一致`);
  const reader = response.body.getReader(); const chunks = []; let size = 0;
  try {
    for (;;) { const { done, value } = await reader.read(); if (done) break;
      size += value.length; invariant(size <= 65536, `login ${label} body 上限`); chunks.push(value); }
  } finally { await reader.cancel(); }
  const json = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  invariant(typeof json.data?.accessToken === 'string' && json.data.accessToken.length > 0, `login ${label} token 不足`);
  return json.data.accessToken;
}

async function get(base, label, route, token, limit = 32768, timeoutMs = 15000) {
  const id = `${String(receipt.requests.length + 1).padStart(2, '0')}-${label}`;
  const filename = `${id}.body`;
  const destination = path.join(privateDir, filename);
  const record = { id, route, status: null, bytes: 0, complete: false, artifactRaw: null };
  receipt.requests.push(record);
  const startedAt = performance.now();
  try {
    const response = await fetch(new URL(route, base), { redirect: 'error',
      signal: AbortSignal.timeout(timeoutMs), headers: token ? { Authorization: `Bearer ${token}` } : {} });
    record.status = response.status;
    const fd = fs.openSync(destination, 'wx', 0o600); const reader = response.body.getReader();
    try {
      for (;;) { const { done, value } = await reader.read(); if (done) { record.complete = true; break; }
        invariant(record.bytes + value.length <= limit, `${id} body 上限`);
        fs.writeSync(fd, value); record.bytes += value.length; }
    } finally {
      fs.closeSync(fd); await reader.cancel();
      record.sha256 = sha(fs.readFileSync(destination));
    }
    const raw = fs.readFileSync(destination);
    record.sha256 = sha(raw);
    // 原HTTP bodyの保存と SHA の後にのみ parse する。
    return { record, raw, filename, json: () => JSON.parse(raw.toString('utf8')) };
  } catch (error) {
    // 例外本文を記録せず、取得予算と許可した中断名だけを残す。
    record.failure = {
      budgetMs: timeoutMs,
      elapsedMs: Math.min(2147483647, Math.max(0, Math.round(performance.now() - startedAt))),
    };
    if (['TimeoutError', 'AbortError'].includes(error?.name)) record.failure.errorName = error.name;
    throw error;
  }
}

function publish(result) {
  fs.copyFileSync(path.join(privateDir, result.filename), path.join(output, 'raw', result.filename), fs.constants.COPYFILE_EXCL);
  result.record.artifactRaw = `raw/${result.filename}`;
  result.record.passed = true;
}

function errorBody(result, status, code, expectedMessage) {
  invariant(result.record.status === status, `${result.record.id} status 不一致`);
  const json = result.json();
  invariant(Object.keys(json).length === 1 && json.error && Object.keys(json.error).sort().join(',') === 'code,fieldErrors,message', `${result.record.id} envelope 不一致`);
  invariant(json.error.code === code && typeof json.error.message === 'string' && Array.isArray(json.error.fieldErrors) && json.error.fieldErrors.length === 0, `${result.record.id} error 不一致`);
  const safeMessage = expectedMessage || ({ COMMON_002: 'この操作を行う権限がありません', TOUR_026: 'チームがこの組織に所属していません' })[code];
  const allowedMessages = Array.isArray(safeMessage) ? safeMessage : [safeMessage];
  invariant(allowedMessages.includes(json.error.message), `${result.record.id} error message 不一致`);
  return json;
}

function anonymousBody(result, route) {
  invariant(result.record.status === 401, `${result.record.id} anonymous status 不一致`);
  if (result.raw.length === 0) return;
  // Boot の error dispatch が返す既定形だけ許可。extra fields は公開せず FAIL。
  const json = result.json();
  invariant(json && Object.keys(json).sort().join(',') === 'error,path,status,timestamp', `${result.record.id} anonymous shape 不一致`);
  invariant(json.status === 401 && json.error === 'Unauthorized' && json.path === route, `${result.record.id} anonymous error 不一致`);
  invariant(typeof json.timestamp === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(json.timestamp) && Number.isFinite(Date.parse(json.timestamp)), `${result.record.id} anonymous timestamp 不一致`);
}

function successBody(result, kind, teamId) {
  invariant(result.record.status === 200, `${result.record.id} status 不一致`);
  const json = result.json();
  if (kind === 'matches') {
    invariant(Object.keys(json).sort().join(',') === 'data,meta' && Array.isArray(json.data) && json.data.length === 0, `${result.record.id} empty list 不一致`);
    invariant(json.meta && Object.keys(json.meta).sort().join(',') === 'page,size,total,totalPages' && json.meta.total === 0 && json.meta.page === 0 && json.meta.size === 20 && json.meta.totalPages === 0, `${result.record.id} page meta 不一致`);
  } else if (kind === 'templates') {
    invariant(Object.keys(json).join(',') === 'data' && Array.isArray(json.data) && json.data.length === 0, `${result.record.id} empty templates 不一致`);
  } else {
    const counts = ['totalMatches', 'wins', 'draws', 'losses', 'totalGoalsFor', 'totalGoalsAgainst', 'goalDifference'];
    const arrays = ['recentForm', 'playerRankings', 'byKind'];
    invariant(Object.keys(json).join(',') === 'data' && json.data && String(json.data.teamId) === String(teamId), `${result.record.id} team stats 不一致`);
    invariant(Object.keys(json.data).sort().join(',') === [...counts, ...arrays, 'teamId'].sort().join(','), `${result.record.id} stats fields 不一致`);
    invariant(counts.every(key => json.data[key] === 0) && arrays.every(key => Array.isArray(json.data[key]) && json.data[key].length === 0), `${result.record.id} empty stats 不一致`);
  }
  return json;
}

function schemaRef(document, value) {
  if (!value.$ref) return value;
  invariant(/^#\/components\/(parameters|schemas)\/[^/]+$/.test(value.$ref), 'schema ref 対象外');
  const [, , kind, name] = value.$ref.split('/');
  const target = document.components?.[kind]?.[name]; invariant(target && !target.$ref, 'schema ref 解決失敗'); return target;
}

async function run(base) {
  const f = await fixture(); const tokens = {};
  for (const label of labels) tokens[label] = await login(base, label);
  const routes = [
    ['matches', (org, team) => `/api/v1/teams/${team}/matches`],
    ['stats', (org, team) => `/api/v1/organizations/${org}/teams/${team}/match-stats`],
    ['templates', (org, team) => `/api/v1/organizations/${org}/teams/${team}/entry-templates`],
  ];
  for (const [kind, route] of routes) {
    const previous = {};
    for (const form of ['numeric', 'slug']) {
      const routePath = route(form === 'numeric' ? f.org.id : f.org.slug, form === 'numeric' ? f.team.id : f.team.slug);
      for (const label of [...labels, 'anonymous']) {
        const result = await get(base, `${kind}-${form}-${label}`, routePath, tokens[label]);
        let json;
        if (label === 'anonymous') {
          anonymousBody(result, routePath);
        } else if (label === 'outsider') json = errorBody(result, 403, 'COMMON_002');
        else json = successBody(result, kind, f.team.id);
        if (json && form === 'numeric') previous[label] = json;
        if (json && form === 'slug') invariant(JSON.stringify(json) === JSON.stringify(previous[label]), `${result.record.id} numeric/slug body 不一致`);
        publish(result);
      }
    }
  }
  for (const form of ['numeric', 'slug']) for (const label of labels) {
    const org = form === 'numeric' ? f.jfa.id : f.jfa.slug;
    const team = form === 'numeric' ? f.team.id : f.team.slug;
    const result = await get(base, `jfa-${form}-${label}`, `/api/v1/organizations/${org}/teams/${team}/entry-templates`, tokens[label]);
    errorBody(result, 404, 'TOUR_026'); publish(result);
  }
  for (const [label, route, message] of [
    ['team-uuid', `/api/v1/teams/${f.uuidNegative}/matches`, `チームが見つかりません: ${f.uuidNegative}`],
    ['org-uuid', `/api/v1/organizations/${f.uuidNegative}/teams/${f.team.slug}/match-stats`, `組織が見つかりません: ${f.uuidNegative}`],
  ]) {
    const result = await get(base, label, route, tokens.member);
    // Converter の直接 RSE と、Spring 型変換包絡を処理する GHE の既存2経路。
    errorBody(result, 404, 'COMMON_005', [message, 'リソースが見つかりません']); publish(result);
  }
  const result = await get(base, 'openapi', '/v3/api-docs', null, 10 * 1024 * 1024, 60000);
  invariant(result.record.status === 200, 'OpenAPI status 不一致');
  const document = result.json(); invariant(typeof document.openapi === 'string' && document.paths, 'OpenAPI document 不一致');
  const targets = [
    ['/api/v1/teams/{teamId}/matches', ['teamId']],
    ['/api/v1/organizations/{orgId}/teams/{teamId}/match-stats', ['orgId', 'teamId']],
    ['/api/v1/organizations/{orgId}/teams/{teamId}/entry-templates', ['orgId', 'teamId']],
  ];
  receipt.schema = [];
  for (const [route, names] of targets) {
    const operation = document.paths[route]?.get; invariant(operation, 'OpenAPI GET 不足');
    const parameters = [...(document.paths[route].parameters || []), ...(operation.parameters || [])].map(p => schemaRef(document, p));
    for (const name of names) {
      const matches = parameters.filter(p => p.in === 'path' && p.name === name);
      invariant(matches.length === 1 && matches[0].required === true && schemaRef(document, matches[0].schema || {}).type === 'string', 'OpenAPI exact scope schema 不一致');
      receipt.schema.push({ route, method: 'get', name, in: 'path', required: true, type: 'string' });
    }
  }
  publish(result); invariant(receipt.requests.length === 33, '有限 request 件数不一致');
  receipt.state = 'PASS';
}

(async () => {
  try { const base = setup(); await run(base); }
  catch (error) {
    receipt.state = 'FAIL';
    // 外部例外に SQL/body/token が含まれてもログへ出さない。
    receipt.failure = { class: error?.constructor?.name || 'Error',
      code: error.failureCode || '外部例外（本文非保存）', request: receipt.requests.at(-1)?.id || null };
    process.exitCode = 1;
  } finally {
    if (output) fs.writeFileSync(path.join(output, 'receipt.json'), JSON.stringify(receipt, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    console.log(`CMP1921 HTTP contract: ${receipt.state} (${receipt.requests.filter(r => r.passed).length}/33 GET)`);
  }
})();
