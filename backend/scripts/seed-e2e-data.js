const mysql = require('mysql2/promise');
const bcrypt = require('bcryptjs');
const crypto = require('crypto');

/** チーム/組織名から URL スラッグを生成する（BE SlugGenerator と同ロジック）。
 * 日本語名など ASCII 英数字が 3 文字未満の場合は MD5 ハッシュのプレフィックスを使い
 * 一意性を担保する（seed の重複実行でも同じ名前から同じスラッグを生成）。 */
function generateSlug(name) {
  const base = name.toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .substring(0, 30);
  if (base.length >= 3) return base;
  // 非 ASCII 主体の名前: MD5 ハッシュで一意な 8 文字を生成（決定論的）
  const hash = crypto.createHash('md5').update(name).digest('hex').substring(0, 8);
  return 's-' + hash;
}

// ============================================================
// F18 ポイントカードウォレット用 AES-256-GCM 暗号化ヘルパー
// ------------------------------------------------------------
// バックエンド `EncryptionService`（AES/GCM/NoPadding、IV 12bytes、
// Auth tag 128bit）と完全に互換。
// 鍵は application-local.yml の `mannschaft.encryption.key`
// （Base64 エンコードされた 32 バイト鍵）と同じ値を使用する。
// ============================================================
const F18_ENCRYPTION_KEY_BASE64 = 'pE8ozQmki8gdQ1nCFC86zcK1SSNJuVRLd7uhdpO4ihc=';
const F18_KEY_BYTES = Buffer.from(F18_ENCRYPTION_KEY_BASE64, 'base64');
if (F18_KEY_BYTES.length !== 32) {
  throw new Error('F18 encryption key must decode to 32 bytes');
}

/**
 * 平文文字列を AES-256-GCM で暗号化し、Base64 文字列として返す。
 * フォーマット: Base64(IV[12] || ciphertext || authTag[16])
 * バックエンド `EncryptionService#encrypt` と同一の出力形式。
 */
function encryptForTest(plain) {
  if (plain === null || plain === undefined) return null;
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', F18_KEY_BYTES, iv);
  const enc = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  return Buffer.concat([iv, enc, tag]).toString('base64');
}

(async () => {
  const conn = await mysql.createConnection({
    host: '127.0.0.1', port: 3306,
    user: 'mannschaft', password: 'mannschaft', database: 'mannschaft',
    charset: 'utf8mb4', // 二重エンコード再発防止のため接続文字コードを明示
  });

  const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
  const SYS = 1;

  // ============================================================
  // 0. E2E テスト用ユーザー（E2E_USER / E2E_ADMIN）を作成
  //    INSERT IGNORE でべき等化 → INSERT 後に SELECT で実際の ID を取得
  // ============================================================
  const hashStrength8 = (plain) => bcrypt.hashSync(plain, 8).replace('$2b$', '$2a$');

  const e2eUserHash = hashStrength8('TestPass2026!');
  const e2eAdminHash = hashStrength8('TestPass2026!');

  await conn.execute(
    `INSERT IGNORE INTO users
      (email, password_hash, last_name, first_name, display_name,
       is_searchable, encryption_key_version, locale, timezone,
       status, reporting_restricted, created_at, updated_at)
     VALUES (?,?,?,?,?,1,1,?,?,?,0,?,?)`,
    ['e2e-user@test.mannschaft.local', e2eUserHash,
     encryptForTest('E2Eユーザー'), encryptForTest('一般'), 'E2E一般ユーザー',
     'ja', 'Asia/Tokyo', 'ACTIVE', now, now]
  );
  const [[e2eUserRow]] = await conn.execute(
    'SELECT id FROM users WHERE email = ?',
    ['e2e-user@test.mannschaft.local']
  );
  const E2E_USER = Number(e2eUserRow.id);

  await conn.execute(
    `INSERT IGNORE INTO users
      (email, password_hash, last_name, first_name, display_name,
       is_searchable, encryption_key_version, locale, timezone,
       status, reporting_restricted, created_at, updated_at)
     VALUES (?,?,?,?,?,1,1,?,?,?,0,?,?)`,
    ['e2e-admin@test.mannschaft.local', e2eAdminHash,
     encryptForTest('E2E管理者'), encryptForTest('管理'), 'E2E管理者',
     'ja', 'Asia/Tokyo', 'ACTIVE', now, now]
  );
  const [[e2eAdminRow]] = await conn.execute(
    'SELECT id FROM users WHERE email = ?',
    ['e2e-admin@test.mannschaft.local']
  );
  const E2E_ADMIN = Number(e2eAdminRow.id);

  // F03.4 予約E2E: SUPPORTER ロールのテストアカウント。
  // 「SUPPORTER でも予約できるか」を実機検証するために fc-u-18 の SUPPORTER として用意する。
  const e2eSupporterHash = hashStrength8('TestPass2026!');
  await conn.execute(
    `INSERT IGNORE INTO users
      (email, password_hash, last_name, first_name, display_name,
       is_searchable, encryption_key_version, locale, timezone,
       status, reporting_restricted, created_at, updated_at)
     VALUES (?,?,?,?,?,1,1,?,?,?,0,?,?)`,
    ['e2e-supporter@test.mannschaft.local', e2eSupporterHash,
     encryptForTest('E2Eサポーター'), encryptForTest('応援'), 'E2Eサポーター 応援',
     'ja', 'Asia/Tokyo', 'ACTIVE', now, now]
  );
  const [[e2eSupporterRow]] = await conn.execute(
    'SELECT id FROM users WHERE email = ?',
    ['e2e-supporter@test.mannschaft.local']
  );
  const E2E_SUPPORTER = Number(e2eSupporterRow.id);

  // 認可根治戦役 Wave4: e2e-real-smoke の認可スモーク用「非メンバー」テストアカウント。
  // どのチーム/組織にも user_roles/memberships を一切割り当てない（assignRole を意図的に呼ばない）。
  // smoke-check.sh はこのユーザーでログインし、保護EPを叩いて 403/404 になることを確認する
  // （200 が返ったら認可漏れとして smoke を fail させる）。
  const e2eOutsiderHash = hashStrength8('TestPass2026!');
  await conn.execute(
    `INSERT IGNORE INTO users
      (email, password_hash, last_name, first_name, display_name,
       is_searchable, encryption_key_version, locale, timezone,
       status, reporting_restricted, created_at, updated_at)
     VALUES (?,?,?,?,?,1,1,?,?,?,0,?,?)`,
    ['e2e-outsider@test.mannschaft.local', e2eOutsiderHash,
     encryptForTest('E2E部外者'), encryptForTest('非会員'), 'E2E部外者（非会員）',
     'ja', 'Asia/Tokyo', 'ACTIVE', now, now]
  );
  const [[e2eOutsiderRow]] = await conn.execute(
    'SELECT id FROM users WHERE email = ?',
    ['e2e-outsider@test.mannschaft.local']
  );
  const E2E_OUTSIDER = Number(e2eOutsiderRow.id);

  console.log(`E2E users: E2E_USER id=${E2E_USER}, E2E_ADMIN id=${E2E_ADMIN}, E2E_SUPPORTER id=${E2E_SUPPORTER}, E2E_OUTSIDER id=${E2E_OUTSIDER}（どのteam/orgにも非所属）`);

  // ============================================================
  // 1. ダミーユーザー 20人
  // ============================================================
  const hash = bcrypt.hashSync('TestPass2026!', 12).replace('$2b$', '$2a$');
  const userNames = [
    ['田中','太郎','田中太郎'],['佐藤','花子','佐藤花子'],['鈴木','一郎','鈴木一郎'],
    ['高橋','美咲','高橋美咲'],['渡辺','健太','渡辺健太'],['伊藤','直人','伊藤直人'],
    ['山本','さくら','山本さくら'],['中村','翔','中村翔'],['小林','真央','小林真央'],
    ['加藤','大輔','加藤大輔'],['吉田','愛','吉田愛'],['山田','蓮','山田蓮'],
    ['松本','陽菜','松本陽菜'],['井上','悠斗','井上悠斗'],['木村','凛','木村凛'],
    ['林','結衣','林結衣'],['清水','蒼','清水蒼'],['斎藤','朝陽','斎藤朝陽'],
    ['前田','心春','前田心春'],['藤田','颯太','藤田颯太']
  ];

  const userIds = [];
  for (let i = 0; i < userNames.length; i++) {
    const [last, first, display] = userNames[i];
    const email = `e2e-dummy-${i + 1}@test.mannschaft.local`;
    await conn.execute(
      `INSERT IGNORE INTO users
        (email, password_hash, last_name, first_name, display_name,
         is_searchable, encryption_key_version, locale, timezone,
         status, reporting_restricted, created_at, updated_at)
       VALUES (?,?,?,?,?,1,1,?,?,?,0,?,?)`,
      [email, hash, encryptForTest(last), encryptForTest(first), display, 'ja', 'Asia/Tokyo', 'ACTIVE', now, now]
    );
    const [[r]] = await conn.execute('SELECT id FROM users WHERE email = ?', [email]);
    userIds.push(Number(r.id));
  }
  console.log(`Users created/found: ${userIds.length} (id ${userIds[0]}-${userIds[userIds.length - 1]})`);

  // ============================================================
  // 2. 組織（JFA階層構造）
  // ============================================================
  const orgs = {};

  async function createOrg(name, orgType, parentId, pref, city) {
    const slug = generateSlug(name);
    await conn.execute(
      `INSERT IGNORE INTO organizations
        (slug, name, org_type, parent_organization_id, prefecture, city,
         visibility, hierarchy_visibility, supporter_enabled, version, created_at, updated_at)
       VALUES (?,?,?,?,?,?,?,?,?,1,?,?)`,
      [slug, name, orgType, parentId, pref, city, 'PUBLIC', 'FULL', 1, now, now]
    );
    const [[r]] = await conn.execute('SELECT id FROM organizations WHERE name = ?', [name]);
    return Number(r.id);
  }

  // 最上位: JFA
  orgs.jfa = await createOrg('日本サッカー協会（テスト）', 'NPO', null, '東京都', '文京区');
  // 地域FA（JFA傘下）
  orgs.kanto = await createOrg('関東サッカー協会（テスト）', 'NPO', orgs.jfa, '東京都', '千代田区');
  orgs.kansai = await createOrg('関西サッカー協会（テスト）', 'NPO', orgs.jfa, '大阪府', '大阪市');
  // 都道府県FA
  orgs.tokyo = await createOrg('東京都サッカー協会（テスト）', 'NPO', orgs.kanto, '東京都', '文京区');
  orgs.kanagawa = await createOrg('神奈川県サッカー協会（テスト）', 'NPO', orgs.kanto, '神奈川県', '横浜市');
  orgs.osaka = await createOrg('大阪府サッカー協会（テスト）', 'NPO', orgs.kansai, '大阪府', '大阪市');
  // 独立組織
  orgs.futsal = await createOrg('NPO フットサル連盟（テスト）', 'NPO', null, '東京都', '渋谷区');
  orgs.sports = await createOrg('地域スポーツクラブ協会（テスト）', 'COMMUNITY', null, '神奈川県', '川崎市');
  // クラブ組織
  orgs.fcTokyo = await createOrg('FC東京ユースアカデミー（テスト）', 'COMMUNITY', orgs.tokyo, '東京都', '調布市');
  orgs.yokohamaFC = await createOrg('横浜FCジュニア（テスト）', 'COMMUNITY', orgs.kanagawa, '神奈川県', '横浜市');

  console.log(`Organizations created/found: ${Object.keys(orgs).length}`, JSON.stringify(orgs));

  // ============================================================
  // 3. チーム
  // ============================================================
  const teams = {};

  async function createTeam(name, template, pref, city) {
    const slug = generateSlug(name);
    await conn.execute(
      `INSERT IGNORE INTO teams
        (slug, name, template, prefecture, city, visibility, supporter_enabled, version, created_at, updated_at)
       VALUES (?,?,?,?,?,?,?,1,?,?)`,
      [slug, name, template, pref, city, 'PUBLIC', 1, now, now]
    );
    const [[r]] = await conn.execute('SELECT id FROM teams WHERE name = ?', [name]);
    return Number(r.id);
  }

  async function linkTeamOrg(teamId, orgId) {
    await conn.execute(
      `INSERT IGNORE INTO team_org_memberships
        (team_id, organization_id, status, invited_at, created_at)
       VALUES (?,?,?,?,?)`,
      [teamId, orgId, 'ACTIVE', now, now]
    );
  }

  // 組織所属チーム
  teams.fcTokyoU18 = await createTeam('FC東京U-18（テスト）', 'SPORTS', '東京都', '調布市');
  teams.fcTokyoU15 = await createTeam('FC東京U-15（テスト）', 'SPORTS', '東京都', '調布市');
  await linkTeamOrg(teams.fcTokyoU18, orgs.fcTokyo);
  await linkTeamOrg(teams.fcTokyoU15, orgs.fcTokyo);

  teams.yokohamaJr = await createTeam('横浜FCジュニアA（テスト）', 'SPORTS', '神奈川県', '横浜市');
  await linkTeamOrg(teams.yokohamaJr, orgs.yokohamaFC);

  teams.tokyoSelect = await createTeam('東京都選抜U-16（テスト）', 'SPORTS', '東京都', '文京区');
  await linkTeamOrg(teams.tokyoSelect, orgs.tokyo);

  teams.osakaSelect = await createTeam('大阪府トレセンU-14（テスト）', 'SPORTS', '大阪府', '大阪市');
  await linkTeamOrg(teams.osakaSelect, orgs.osaka);

  teams.futsalA = await createTeam('フットサルクラブA（テスト）', 'SPORTS', '東京都', '渋谷区');
  await linkTeamOrg(teams.futsalA, orgs.futsal);

  teams.sportsClubA = await createTeam('川崎スポーツクラブ（テスト）', 'SPORTS', '神奈川県', '川崎市');
  await linkTeamOrg(teams.sportsClubA, orgs.sports);

  teams.grassroots = await createTeam('草サッカー倶楽部（テスト）', 'SPORTS', '埼玉県', 'さいたま市');
  await linkTeamOrg(teams.grassroots, orgs.sports);

  // 独立チーム
  teams.indieFC = await createTeam('インディーFC（テスト）', 'SPORTS', '千葉県', '船橋市');
  teams.sundayFC = await createTeam('日曜キッカーズ（テスト）', 'SPORTS', '東京都', '世田谷区');

  console.log(`Teams created/found: ${Object.keys(teams).length}`, JSON.stringify(teams));

  // ============================================================
  // 4. ロール配置
  // ============================================================
  async function assignRole(userId, roleId, teamId, orgId) {
    await conn.execute(
      `INSERT IGNORE INTO user_roles
        (user_id, role_id, team_id, organization_id, granted_by, created_at, updated_at)
       VALUES (?,?,?,?,?,?,?)`,
      [userId, roleId, teamId || null, orgId || null, SYS, now, now]
    );

    // F00.5 Phase 3: memberships 基盤への同期。
    // isMember() は user_roles ではなく memberships.left_at IS NULL を参照するため、
    // ロール付与と同時に対応するアクティブ memberships 行を投入しないと、
    // 掲示板など memberships 認可ガード配下のドメインが全員 403 になる（このシードが欠落していた根本原因）。
    //   - スコープ（team_id / organization_id）が無い SYSTEM_ADMIN(role_id=1) はプラットフォーム全体ロールのため対象外
    //   - GUEST(role_id=6) はメンバーシップを伴わないため対象外
    //   - role_kind は SUPPORTER(role_id=5) のみ SUPPORTER、その他（ADMIN/DEPUTY_ADMIN/MEMBER）は MEMBER
    const isScopedMembershipRole = roleId >= 2 && roleId <= 5 && (teamId || orgId);
    if (isScopedMembershipRole) {
      const scopeType = teamId ? 'TEAM' : 'ORGANIZATION';
      const scopeId = teamId || orgId;
      const roleKind = roleId === 5 ? 'SUPPORTER' : 'MEMBER';
      // 冪等性: 同一 (user, scope) のアクティブ行が無ければ作成する。
      // memberships には UNIQUE 制約が無いため、存在チェックで重複投入を防ぐ。
      const [rows] = await conn.execute(
        `SELECT id FROM memberships
          WHERE user_id = ? AND scope_type = ? AND scope_id = ? AND left_at IS NULL
          LIMIT 1`,
        [userId, scopeType, scopeId]
      );
      if (rows.length === 0) {
        await conn.execute(
          `INSERT INTO memberships
            (user_id, scope_type, scope_id, role_kind, joined_at, invited_by, created_at, updated_at)
           VALUES (?,?,?,?,?,?,?,?)`,
          [userId, scopeType, scopeId, roleKind, now, SYS, now, now]
        );
      }
    }
  }

  // E2E admin: SYSTEM_ADMIN（プラットフォーム全体） + JFA ADMIN + FC東京U-18 ADMIN
  // role_id=1 は SYSTEM_ADMIN（V2.014__seed_roles.sql で投入済み）
  await assignRole(E2E_ADMIN, 1, null, null);
  await assignRole(E2E_ADMIN, 2, null, orgs.jfa);
  await assignRole(E2E_ADMIN, 2, teams.fcTokyoU18, null);

  // E2E user: FC東京U-18 MEMBER
  await assignRole(E2E_USER, 4, teams.fcTokyoU18, null);

  // E2E supporter: FC東京U-18 SUPPORTER（role_id=5。assignRole が memberships role_kind=SUPPORTER も同期投入）
  await assignRole(E2E_SUPPORTER, 5, teams.fcTokyoU18, null);

  // FC東京U-18: 監督 + 選手4人
  await assignRole(userIds[0], 2, teams.fcTokyoU18, null);
  for (let i = 1; i <= 4; i++) await assignRole(userIds[i], 4, teams.fcTokyoU18, null);

  // FC東京U-15: 監督 + 選手3人
  await assignRole(userIds[5], 2, teams.fcTokyoU15, null);
  for (let i = 6; i <= 8; i++) await assignRole(userIds[i], 4, teams.fcTokyoU15, null);

  // 横浜FCジュニアA: 監督 + 選手2人
  await assignRole(userIds[9], 2, teams.yokohamaJr, null);
  await assignRole(userIds[10], 4, teams.yokohamaJr, null);
  await assignRole(userIds[11], 4, teams.yokohamaJr, null);

  // 東京都選抜: 監督 + 選手1人
  await assignRole(userIds[12], 2, teams.tokyoSelect, null);
  await assignRole(userIds[13], 4, teams.tokyoSelect, null);

  // 大阪トレセン
  await assignRole(userIds[14], 2, teams.osakaSelect, null);
  await assignRole(userIds[15], 4, teams.osakaSelect, null);

  // フットサルクラブA
  await assignRole(userIds[16], 2, teams.futsalA, null);
  await assignRole(userIds[17], 4, teams.futsalA, null);

  // インディーFC（独立）
  await assignRole(userIds[18], 2, teams.indieFC, null);
  await assignRole(userIds[19], 4, teams.indieFC, null);

  // 組織ロール: FA理事
  await assignRole(userIds[0], 2, null, orgs.tokyo);
  await assignRole(userIds[9], 2, null, orgs.kanagawa);
  await assignRole(userIds[14], 2, null, orgs.osaka);
  await assignRole(userIds[5], 4, null, orgs.fcTokyo);

  console.log('Roles assigned');

  // ============================================================
  // 5. スケジュール
  // ============================================================
  async function createSchedule(teamId, orgId, title, eventType, startAt, endAt, location, createdBy) {
    await conn.execute(
      `INSERT IGNORE INTO schedules
        (team_id, organization_id, title, event_type, start_at, end_at, location,
         all_day, visibility, min_view_role, min_response_role, status, attendance_required,
         attendance_status, comment_option, is_exception, created_by, created_at, updated_at)
       VALUES (?,?,?,?,?,?,?,0,?,?,?,?,?,?,?,0,?,?,?)`,
      [teamId, orgId, title, eventType, startAt, endAt, location,
       'MEMBERS_ONLY', 'MEMBER_PLUS', 'MEMBER_PLUS', 'SCHEDULED', 1, 'READY', 'OPTIONAL', createdBy, now, now]
    );
  }

  // FC東京U-18
  await createSchedule(teams.fcTokyoU18, null, '練習（フィジカル）', 'PRACTICE', '2026-04-05 09:00:00', '2026-04-05 12:00:00', '味の素スタジアム西練習場', userIds[0]);
  await createSchedule(teams.fcTokyoU18, null, 'プリンスリーグ関東 第3節 vs 横浜FCユース', 'MATCH', '2026-04-06 14:00:00', '2026-04-06 16:00:00', '西が丘サッカー場', userIds[0]);
  await createSchedule(teams.fcTokyoU18, null, '練習（紅白戦）', 'PRACTICE', '2026-04-08 15:00:00', '2026-04-08 18:00:00', '味の素スタジアム西練習場', userIds[0]);
  await createSchedule(teams.fcTokyoU18, null, 'プリンスリーグ関東 第4節 vs 浦和ユース', 'MATCH', '2026-04-12 13:00:00', '2026-04-12 15:00:00', '浦和駒場スタジアム', userIds[0]);
  await createSchedule(teams.fcTokyoU18, null, 'ミーティング（戦術確認）', 'MEETING', '2026-04-04 18:00:00', '2026-04-04 19:30:00', 'クラブハウス会議室', userIds[0]);

  // 横浜FCジュニアA
  await createSchedule(teams.yokohamaJr, null, '練習（ボール回し）', 'PRACTICE', '2026-04-05 14:00:00', '2026-04-05 16:00:00', 'ニッパツ三ツ沢球技場サブグラウンド', userIds[9]);
  await createSchedule(teams.yokohamaJr, null, '神奈川県リーグ 第2節', 'MATCH', '2026-04-07 10:00:00', '2026-04-07 12:00:00', '保土ケ谷公園サッカー場', userIds[9]);

  // FC東京U-15
  await createSchedule(teams.fcTokyoU15, null, '関東ユースリーグ 第5節', 'MATCH', '2026-04-06 10:00:00', '2026-04-06 12:00:00', '西が丘サッカー場', userIds[5]);

  // 東京FA主催
  await createSchedule(null, orgs.tokyo, '審判講習会（4級）', 'EVENT', '2026-04-10 09:00:00', '2026-04-10 17:00:00', '東京体育館', userIds[12]);
  await createSchedule(null, orgs.tokyo, '指導者研修会 C級コーチ', 'EVENT', '2026-04-15 10:00:00', '2026-04-15 16:00:00', '国立スポーツ科学センター', userIds[12]);

  // 独立チーム
  await createSchedule(teams.indieFC, null, '練習試合 vs 草サッカー倶楽部', 'MATCH', '2026-04-06 10:00:00', '2026-04-06 12:00:00', '船橋運動公園', userIds[18]);
  await createSchedule(teams.grassroots, null, '週末練習', 'PRACTICE', '2026-04-05 08:00:00', '2026-04-05 10:00:00', '大宮公園サッカー場', userIds[19]);

  console.log('Schedules created: 12');

  // ============================================================
  // 6. チャットチャンネル + メッセージ
  // ============================================================
  async function createChannel(type, teamId, orgId, name, createdBy) {
    await conn.execute(
      `INSERT IGNORE INTO chat_channels
        (channel_type, team_id, organization_id, name, is_private, is_archived, version, created_by, created_at, updated_at)
       VALUES (?,?,?,?,0,0,1,?,?,?)`,
      [type, teamId, orgId, name, createdBy, now, now]
    );
    const [[r]] = await conn.execute(
      'SELECT id FROM chat_channels WHERE channel_type=? AND name=? AND COALESCE(team_id,0)=? AND COALESCE(organization_id,0)=?',
      [type, name, teamId || 0, orgId || 0]
    );
    return Number(r.id);
  }

  // channel_type は ChannelType Java enum に合わせて TEAM_PUBLIC / ORG_PUBLIC を使う
  // （旧値 'TEAM' / 'ORGANIZATION' は enum に存在せず 500 エラーを引き起こす）
  const ch1 = await createChannel('TEAM_PUBLIC', teams.fcTokyoU18, null, '全体連絡', userIds[0]);
  const ch2 = await createChannel('TEAM_PUBLIC', teams.fcTokyoU18, null, '試合速報', userIds[0]);
  const ch3 = await createChannel('TEAM_PUBLIC', teams.fcTokyoU18, null, 'コーチ専用', userIds[0]);
  const ch4 = await createChannel('TEAM_PUBLIC', teams.yokohamaJr, null, '全体連絡', userIds[9]);
  const ch5 = await createChannel('ORG_PUBLIC', null, orgs.tokyo, '理事会連絡', E2E_ADMIN);
  const ch6 = await createChannel('ORG_PUBLIC', null, orgs.tokyo, '大会運営', userIds[12]);
  const ch7 = await createChannel('TEAM_PUBLIC', teams.fcTokyoU15, null, '全体連絡', userIds[5]);
  const ch8 = await createChannel('TEAM_PUBLIC', teams.indieFC, null, '雑談', userIds[18]);

  // E2E_ADMIN を FC東京U-18 チャンネル (ch1) の ADMIN メンバーとして追加
  // BA-005/BA-006 (業務アラート E2E テスト) が PATCH inquiry API を叩くために必要
  await conn.execute(
    `INSERT IGNORE INTO chat_channel_members
      (channel_id, user_id, role, unread_count, is_muted, is_pinned, joined_at, created_at, updated_at)
     VALUES (?, ?, 'ADMIN', 0, 0, 0, ?, ?, ?)`,
    [ch1, E2E_ADMIN, now, now, now]
  );
  console.log(`E2E_ADMIN (id=${E2E_ADMIN}) を ch1 (id=${ch1}) の ADMIN メンバーとして追加`);

  // チャットメッセージ
  const [chatMsgTable] = await conn.execute("SHOW TABLES LIKE 'chat_messages'");
  if (chatMsgTable.length > 0) {
    const [chatCols] = await conn.execute('SHOW COLUMNS FROM chat_messages');
    const colNames = chatCols.map(c => c.Field);
    console.log('chat_messages columns:', colNames.join(', '));

    if (colNames.includes('channel_id') && colNames.includes('sender_id') && colNames.includes('content')) {
      const insertMsg = `INSERT IGNORE INTO chat_messages
        (channel_id, sender_id, content, message_type, created_at, updated_at)
       VALUES (?,?,?,?,?,?)`;
      await conn.execute(insertMsg, [ch1, userIds[0], '明日の練習は9時集合です。グラウンドシューズを忘れずに！', 'TEXT', '2026-04-04 20:00:00', '2026-04-04 20:00:00']);
      await conn.execute(insertMsg, [ch1, userIds[1], '了解です！', 'TEXT', '2026-04-04 20:05:00', '2026-04-04 20:05:00']);
      await conn.execute(insertMsg, [ch1, userIds[2], '承知しました', 'TEXT', '2026-04-04 20:10:00', '2026-04-04 20:10:00']);
      await conn.execute(insertMsg, [ch1, E2E_USER, '分かりました！', 'TEXT', '2026-04-04 20:15:00', '2026-04-04 20:15:00']);
      await conn.execute(insertMsg, [ch2, userIds[0], '第3節 vs 横浜FCユース、キックオフ14:00。会場: 西が丘サッカー場', 'TEXT', '2026-04-05 12:00:00', '2026-04-05 12:00:00']);
      console.log('Chat messages created: 5');
    }
  }

  console.log(`Chat channels created/found: 8`);

  // ============================================================
  // 7. 通知
  // ============================================================
  async function notify(userId, type, priority, title, body, srcType, srcId, scopeType, scopeId, url) {
    await conn.execute(
      `INSERT INTO notifications
        (user_id, notification_type, priority, title, body,
         source_type, source_id, scope_type, scope_id, action_url, is_read, created_at)
       VALUES (?,?,?,?,?,?,?,?,?,?,0,?)`,
      [userId, type, priority, title, body, srcType, srcId, scopeType, scopeId, url, now]
    );
  }

  // 通知はべき等化が難しいため、既存通知がなければ一括 INSERT
  const [[notifCount]] = await conn.execute(
    'SELECT COUNT(*) AS cnt FROM notifications WHERE user_id IN (?,?)',
    [E2E_USER, E2E_ADMIN]
  );
  if (Number(notifCount.cnt) === 0) {
    await notify(E2E_USER, 'SCHEDULE_CREATED', 'NORMAL', '新しい予定が追加されました', 'プリンスリーグ関東 第3節が追加されました', 'SCHEDULE', 1, 'TEAM', teams.fcTokyoU18, `/teams/${teams.fcTokyoU18}/schedules`);
    await notify(E2E_USER, 'CHAT_MESSAGE', 'NORMAL', '田中太郎さんがメッセージを送信', '明日の練習は9時集合です', 'CHAT_CHANNEL', ch1, 'TEAM', teams.fcTokyoU18, `/chat/${ch1}`);
    await notify(E2E_USER, 'SCHEDULE_REMINDER', 'HIGH', '明日の試合のリマインダー', 'プリンスリーグ関東 第3節 vs 横浜FCユース（14:00〜）', 'SCHEDULE', 1, 'TEAM', teams.fcTokyoU18, `/teams/${teams.fcTokyoU18}/schedules`);
    await notify(E2E_USER, 'TEAM_ANNOUNCEMENT', 'NORMAL', 'チームからのお知らせ', '4月の練習スケジュールが更新されました', 'TEAM', teams.fcTokyoU18, 'TEAM', teams.fcTokyoU18, `/teams/${teams.fcTokyoU18}`);
    await notify(E2E_ADMIN, 'MEMBER_JOINED', 'NORMAL', '新メンバーが参加しました', '佐藤花子さんがFC東京U-18に参加しました', 'TEAM', teams.fcTokyoU18, 'TEAM', teams.fcTokyoU18, `/teams/${teams.fcTokyoU18}/members`);
    await notify(E2E_ADMIN, 'SYSTEM_ANNOUNCEMENT', 'HIGH', 'システムメンテナンスのお知らせ', '4/10 AM2:00〜4:00 にメンテナンスを実施します', 'SYSTEM', null, 'SYSTEM', null, '/announcements');
    await notify(E2E_ADMIN, 'SCHEDULE_CREATED', 'NORMAL', '審判講習会が追加されました', '4/10 東京体育館にて4級審判講習会を実施', 'SCHEDULE', 1, 'ORGANIZATION', orgs.tokyo, `/organizations/${orgs.tokyo}/schedules`);
    console.log('Notifications created: 7');
  } else {
    console.log(`Notifications skipped: already ${notifCount.cnt} rows for E2E users`);
  }

  // ============================================================
  // 8. 活動記録テンプレート + 活動記録（SNS シェアテスト用）
  // ============================================================

  // 8-a. FC東京U-18 用の活動記録テンプレートを作成（INSERT IGNORE）
  await conn.execute(
    `INSERT IGNORE INTO activity_templates
      (scope_type, scope_id, name, description, icon, color,
       is_participant_required, default_visibility, sort_order, created_by, created_at, updated_at)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`,
    ['TEAM', teams.fcTokyoU18, '試合結果', 'チームの試合結果を記録するテンプレート',
     '⚽', '#1565C0', true, 'PUBLIC', 1, userIds[0], now, now]
  );
  const [[tmplRow]] = await conn.execute(
    'SELECT id FROM activity_templates WHERE scope_type=? AND scope_id=? AND name=?',
    ['TEAM', teams.fcTokyoU18, '試合結果']
  );
  const activityTemplateId = Number(tmplRow.id);
  console.log(`Activity template created/found: id=${activityTemplateId}`);

  // 8-b. 活動記録本体（SNS シェアテスト用）
  //      visibility='PUBLIC'、location あり
  await conn.execute(
    `INSERT IGNORE INTO activity_results
      (scope_type, scope_id, template_id, title, activity_date,
       activity_time_start, activity_time_end,
       description, field_values, visibility, location,
       created_by, created_at, updated_at)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
    [
      'TEAM', teams.fcTokyoU18, activityTemplateId,
      '春季合宿2026',
      '2026-03-25',
      '09:00:00', '17:00:00',
      '菅平高原での春季合宿。フィジカルトレーニングとチームビルディングを実施。',
      JSON.stringify({ score: '3-1', opponent: 'テスト対戦チーム', result: 'WIN' }),
      'PUBLIC',
      '長野県・菅平高原',
      userIds[0],
      now, now
    ]
  );
  const [[arRow]] = await conn.execute(
    'SELECT id FROM activity_results WHERE scope_type=? AND scope_id=? AND title=?',
    ['TEAM', teams.fcTokyoU18, '春季合宿2026']
  );
  const activityResultId = Number(arRow.id);
  console.log(`Activity result created/found: id=${activityResultId}`);

  // ============================================================
  // F18. ポイントカードウォレット — E2E_USER 用シード
  //   - point_card_user_settings: 規約同意済み行を 1 件
  //   - user_point_cards: 5 枚（東急 / 楽天 / クリーニング屋 / ドラッグストア / コンビニ）
  //   - point_card_groups: 2 グループ（コンビニ / ドラッグストア）
  //   - point_card_group_items: 各グループに 1〜2 枚を紐付け
  //
  //   VARBINARY 暗号化カラムは encryptForTest()（AES-256-GCM）で投入する。
  //   E2E_USER の既存 F18 データを DELETE してから INSERT することで冪等化する。
  // ============================================================
  console.log('\n--- Seeding F18 point card wallet ---');

  // 既存データを掃除（外部キー順: items → groups, cards, settings）
  await conn.execute(
    `DELETE FROM point_card_group_items
       WHERE group_id IN (SELECT id FROM point_card_groups WHERE user_id = ?)`,
    [E2E_USER]
  );
  await conn.execute('DELETE FROM point_card_groups WHERE user_id = ?', [E2E_USER]);
  await conn.execute('DELETE FROM user_point_cards WHERE user_id = ?', [E2E_USER]);
  await conn.execute('DELETE FROM point_card_user_settings WHERE user_id = ?', [E2E_USER]);

  // 規約同意済み設定
  await conn.execute(
    `INSERT INTO point_card_user_settings
       (user_id, is_enabled, terms_accepted_at, terms_version, require_biometric_on_show,
        created_at, updated_at)
     VALUES (?, 1, ?, ?, 0, ?, ?)`,
    [E2E_USER, now, 'v1.0.0', now, now]
  );

  // カード 5 枚定義
  const f18Cards = [
    { name: '東急ポイント',       barcode: '1234567890123', last4: '0123', format: 'CODE128', favorite: true,  order: 0 },
    { name: '楽天ポイントカード', barcode: '9876543210987', last4: '0987', format: 'CODE128', favorite: true,  order: 1 },
    { name: 'クリーニング屋',     barcode: '5555000011112222', last4: '2222', format: 'CODE128', favorite: false, order: 2 },
    { name: 'マツモトキヨシ',     barcode: '7777888899990000', last4: '0000', format: 'CODE128', favorite: false, order: 3 },
    { name: 'セブンイレブン',     barcode: '1111222233334444', last4: '4444', format: 'CODE128', favorite: false, order: 4 },
  ];

  const f18CardIds = [];
  for (const c of f18Cards) {
    const id = crypto.randomUUID();
    await conn.execute(
      `INSERT INTO user_point_cards
         (id, user_id, provider_id,
          display_name, nickname, barcode_value, barcode_format, last4, memo,
          is_favorite, display_order, balance, stamp_count, last_used_at,
          created_at, updated_at)
       VALUES (?, ?, NULL,
               ?, NULL, ?, ?, ?, NULL,
               ?, ?, NULL, NULL, NULL,
               ?, ?)`,
      [
        id, E2E_USER,
        encryptForTest(c.name),
        encryptForTest(c.barcode),
        c.format,
        c.last4,
        c.favorite ? 1 : 0,
        c.order,
        now, now,
      ]
    );
    f18CardIds.push({ id, name: c.name });
  }
  console.log(`F18 cards inserted: ${f18CardIds.length}`);

  // グループ 2 個
  const conveniGroupId = crypto.randomUUID();
  const drugGroupId = crypto.randomUUID();

  await conn.execute(
    `INSERT INTO point_card_groups
       (id, user_id, name, emoji, display_order, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
    [conveniGroupId, E2E_USER, 'コンビニ', '🏪', 0, now, now]
  );
  await conn.execute(
    `INSERT INTO point_card_groups
       (id, user_id, name, emoji, display_order, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
    [drugGroupId, E2E_USER, 'ドラッグストア', '💊', 1, now, now]
  );

  // グループに紐付け
  //   コンビニ: セブンイレブン
  //   ドラッグストア: マツモトキヨシ
  const conveniCard = f18CardIds.find((c) => c.name === 'セブンイレブン');
  const drugCard = f18CardIds.find((c) => c.name === 'マツモトキヨシ');
  await conn.execute(
    `INSERT INTO point_card_group_items
       (id, group_id, card_id, display_order, created_at)
     VALUES (?, ?, ?, ?, ?)`,
    [crypto.randomUUID(), conveniGroupId, conveniCard.id, 0, now]
  );
  await conn.execute(
    `INSERT INTO point_card_group_items
       (id, group_id, card_id, display_order, created_at)
     VALUES (?, ?, ?, ?, ?)`,
    [crypto.randomUUID(), drugGroupId, drugCard.id, 0, now]
  );
  console.log(`F18 groups inserted: 2 (コンビニ, ドラッグストア)`);

  // ============================================================
  // F02.9. お気に入りウィジェット — E2E_USER 用シード
  //   - user_favorites: TEAM 2件 + ORGANIZATION 1件 = 3件
  //   - 主キー id は BINARY(16)（UuidV7Entity）。INSERT 時に
  //     MySQL の UUID_TO_BIN(UUID()) で発番する。
  //   - 冪等性: 既存 E2E_USER の user_favorites 行を DELETE してから INSERT。
  //   - entity_id は VARCHAR(36) なので BIGINT を十進数文字列化して投入する
  //     （FavoriteResponse#entityId のフロント表現と一致）。
  // ============================================================
  console.log('\n--- Seeding F02.9 user_favorites ---');

  await conn.execute('DELETE FROM user_favorites WHERE user_id = ?', [E2E_USER]);

  // TEAM: FC東京U-18, FC東京U-15
  const favorites = [
    { entityType: 'TEAM',         entityId: String(teams.fcTokyoU18),   displayOrder: 0 },
    { entityType: 'TEAM',         entityId: String(teams.fcTokyoU15),   displayOrder: 1 },
    { entityType: 'ORGANIZATION', entityId: String(orgs.jfa),           displayOrder: 2 },
  ];

  for (const fav of favorites) {
    await conn.execute(
      `INSERT INTO user_favorites
         (id, user_id, entity_type, entity_id, display_order, created_at)
       VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, ?, ?)`,
      [E2E_USER, fav.entityType, fav.entityId, fav.displayOrder, now]
    );
  }
  console.log(`F02.9 favorites inserted: ${favorites.length} (TEAM x2, ORGANIZATION x1)`);

  // ============================================================
  // F17 村コミュニティ — Phase 10 実機E2E用 seed
  // ------------------------------------------------------------
  //   - villages 2件 (OFFICIAL x1 / COMMUNITY x1) を name で UPSERT する
  //   - E2E_USER を COMMUNITY 村に VILLAGER として参加させる
  //   - 冪等性: E2E_USER の village_memberships を当該村について DELETE → INSERT
  //   - villages の id は BINARY(16) なので UUID_TO_BIN(UUID()) で発番
  // ============================================================
  console.log('\n--- Seeding F17 villages ---');

  const villageSeeds = [
    {
      slug: 'e2e-test-official-village',
      name: 'E2Eテスト公式村',
      description: 'E2E実機テスト用の公式村。VLG-001〜012 で参照される。',
      type: 'OFFICIAL',
      joinPolicy: 'FREE',
      visibility: 'PUBLIC',
      category: 'sports',
    },
    {
      slug: 'e2e-test-community-village',
      name: 'E2Eテストコミュニティ村',
      description: 'E2E実機テスト用のコミュニティ村。E2E_USER が VILLAGER として参加する。',
      type: 'COMMUNITY',
      joinPolicy: 'FREE',
      visibility: 'PUBLIC',
      category: 'hobby',
    },
  ];

  /** village を name で UPSERT し、BIN_TO_UUID(id) を返す */
  async function upsertVillage(v) {
    // 既存村があるか確認（name は UNIQUE）
    const [rows] = await conn.execute(
      'SELECT BIN_TO_UUID(id) AS id_text FROM villages WHERE name = ?',
      [v.name]
    );
    if (rows.length > 0) {
      // 既存村: メタを最新化（slug/desc/type/...）
      await conn.execute(
        `UPDATE villages
           SET slug = ?, description = ?, type = ?, join_policy = ?,
               visibility = ?, category = ?, deleted_at = NULL, archived_at = NULL,
               updated_at = ?
         WHERE name = ?`,
        [v.slug, v.description, v.type, v.joinPolicy, v.visibility, v.category, now, v.name]
      );
      return rows[0].id_text;
    }
    // 新規 INSERT
    await conn.execute(
      `INSERT INTO villages
         (id, slug, name, description, type, join_policy, visibility, category,
          member_count_cache, created_by_user_id, created_at, updated_at, version)
       VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 0)`,
      [v.slug, v.name, v.description, v.type, v.joinPolicy, v.visibility, v.category,
       E2E_ADMIN, now, now]
    );
    const [[newRow]] = await conn.execute(
      'SELECT BIN_TO_UUID(id) AS id_text FROM villages WHERE name = ?',
      [v.name]
    );
    return newRow.id_text;
  }

  const villageIds = {};
  for (const v of villageSeeds) {
    const idText = await upsertVillage(v);
    villageIds[v.slug] = idText;
  }

  // E2E_USER を COMMUNITY 村に VILLAGER として参加させる
  // 冪等性: E2E_USER × その村の既存メンバーシップを全削除
  const communityIdText = villageIds['e2e-test-community-village'];
  await conn.execute(
    `DELETE FROM village_memberships
       WHERE village_id = UUID_TO_BIN(?)
         AND subject_type = 'USER'
         AND subject_id = ?`,
    [communityIdText, E2E_USER]
  );
  await conn.execute(
    `INSERT INTO village_memberships
       (id, village_id, subject_type, subject_id, role, joined_at,
        created_at, updated_at, version)
     VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), 'USER', ?, 'VILLAGER', ?, ?, ?, 0)`,
    [communityIdText, E2E_USER, now, now, now]
  );

  // member_count_cache を再計算
  for (const slug of Object.keys(villageIds)) {
    const idText = villageIds[slug];
    await conn.execute(
      `UPDATE villages
          SET member_count_cache = (
            SELECT COUNT(*) FROM village_memberships
             WHERE village_id = UUID_TO_BIN(?)
               AND left_at IS NULL
               AND banned_at IS NULL
          )
        WHERE id = UUID_TO_BIN(?)`,
      [idText, idText]
    );
  }

  console.log(`F17 villages inserted: 2 (OFFICIAL=${villageIds['e2e-test-official-village']}, COMMUNITY=${villageIds['e2e-test-community-village']})`);
  console.log(`F17 memberships: E2E_USER joined COMMUNITY village as VILLAGER`);

  // ============================================================
  // F09.15 区分所有者承継支援 — Phase 11 実機E2E用 seed
  // ------------------------------------------------------------
  //   - apartment 組織 1件（COMMUNITY 型・name で UPSERT）を作成
  //   - E2E_USER に当該組織の ADMIN ロール（role_id=2）を付与
  //     ※ 全 succession 系一覧 API は accessControlService.isAdminOrAbove で
  //       ADMIN 必須のため、E2E_USER 自身が ADMIN を持たないと 403 になる
  //   - dwelling_units（住戸）1件 / resident_registry（居住者）1件 を投入
  //     resident_registry の暗号化カラム（last_name 等）は encryptForTest() で投入
  //   - succession_covenants / succession_pre_registrations /
  //     unseal_requests / delinquency_escalations / legal_filings 各 1件
  //     冪等性: 当該 apartment 組織内の各テーブルを毎回 DELETE → INSERT
  // ============================================================
  console.log('\n--- Seeding F09.15 succession (apartment) ---');

  // apartment 組織を UPSERT
  const apartmentOrgId = await createOrg(
    'E2Eテストマンション管理組合', 'COMMUNITY', null, '東京都', '世田谷区'
  );
  console.log(`F09.15 apartment org: id=${apartmentOrgId}`);

  // E2E_USER に apartment 組織の ADMIN ロール（role_id=2）を付与
  await assignRole(E2E_USER, 2, null, apartmentOrgId);
  // E2E_ADMIN にも付与（管理者操作用）
  await assignRole(E2E_ADMIN, 2, null, apartmentOrgId);
  console.log(`F09.15 roles: E2E_USER / E2E_ADMIN granted ADMIN of apartment org`);

  // 既存 succession データを冪等削除（apartment 組織配下のみ）
  await conn.execute(
    'DELETE FROM legal_filings WHERE organization_id = ?', [apartmentOrgId]
  );
  await conn.execute(
    'DELETE FROM delinquency_escalations WHERE organization_id = ?', [apartmentOrgId]
  );
  await conn.execute(
    'DELETE FROM unseal_requests WHERE organization_id = ?', [apartmentOrgId]
  );
  await conn.execute(
    'DELETE FROM succession_covenants WHERE organization_id = ?', [apartmentOrgId]
  );
  await conn.execute(
    'DELETE FROM succession_pre_registrations WHERE organization_id = ?', [apartmentOrgId]
  );
  await conn.execute(
    'DELETE FROM resident_registry WHERE dwelling_unit_id IN (SELECT id FROM dwelling_units WHERE organization_id = ?)',
    [apartmentOrgId]
  );
  await conn.execute(
    'DELETE FROM dwelling_units WHERE organization_id = ?', [apartmentOrgId]
  );

  // dwelling_units 1件
  await conn.execute(
    `INSERT INTO dwelling_units
      (scope_type, organization_id, unit_number, floor, area_sqm, layout,
       unit_type, resident_count, created_at, updated_at)
     VALUES (?,?,?,?,?,?,?,?,?,?)`,
    ['ORGANIZATION', apartmentOrgId, '101', 1, 65.50, '3LDK', 'STANDARD', 1, now, now]
  );
  const [[dwellingRow]] = await conn.execute(
    'SELECT id FROM dwelling_units WHERE organization_id = ? AND unit_number = ?',
    [apartmentOrgId, '101']
  );
  const dwellingUnitId = Number(dwellingRow.id);

  // resident_registry 1件（暗号化カラムは encryptForTest() で投入）
  const residentLastName = encryptForTest('承継太郎');
  const residentFirstName = encryptForTest('被験者');
  await conn.execute(
    `INSERT INTO resident_registry
      (dwelling_unit_id, user_id, resident_type, last_name, first_name,
       encryption_key_version, move_in_date, is_primary, is_verified,
       death_status, occupancy_status,
       created_at, updated_at)
     VALUES (?,?,?,?,?,1,?,1,1,?,?,?,?)`,
    [dwellingUnitId, E2E_USER, 'OWNER',
     residentLastName, residentFirstName,
     '2020-04-01', 'ALIVE', 'RESIDING', now, now]
  );
  const [[residentRow]] = await conn.execute(
    'SELECT id FROM resident_registry WHERE dwelling_unit_id = ? AND user_id = ?',
    [dwellingUnitId, E2E_USER]
  );
  const residentRegistryId = Number(residentRow.id);

  // succession_covenants 1件
  await conn.execute(
    `INSERT INTO succession_covenants
      (id, organization_id, dwelling_unit_id, resident_registry_id, signer_user_id,
       covenant_type, covenant_version, pdf_s3_key, pdf_sha256,
       internal_signature_token, signed_at, created_at, updated_at)
     VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [apartmentOrgId, dwellingUnitId, residentRegistryId, E2E_USER,
     'SUCCESSION_PRE_REGISTRATION', '1.0',
     `organizations/${apartmentOrgId}/succession/covenants/e2e-seed.pdf`,
     '0'.repeat(64),
     'e2e-seed-token-' + Date.now(),
     now, now, now]
  );

  // succession_pre_registrations 1件（封緘済）
  await conn.execute(
    `INSERT INTO succession_pre_registrations
      (id, organization_id, dwelling_unit_id, resident_registry_id, owner_user_id,
       seal_status, emergency_contacts, inheritance_candidates, will_memo,
       last_updated_by_owner_at, created_at, updated_at)
     VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [apartmentOrgId, dwellingUnitId, residentRegistryId, E2E_USER,
     'SEALED',
     '[{"name":"承継花子","phone":"090-0000-0000","relationship":"配偶者"}]',
     '[{"name":"承継一郎","relationship":"長男"}]',
     '自筆証書遺言は公証役場に保管。',
     now, now, now]
  );
  const [[preRegRow]] = await conn.execute(
    'SELECT BIN_TO_UUID(id) AS id_text FROM succession_pre_registrations WHERE organization_id = ? AND resident_registry_id = ?',
    [apartmentOrgId, residentRegistryId]
  );
  const preRegistrationIdText = preRegRow.id_text;

  // unseal_requests 1件（PENDING）
  await conn.execute(
    `INSERT INTO unseal_requests
      (id, organization_id, dwelling_unit_id, resident_registry_id, pre_registration_id,
       requested_by, request_reason, created_at, updated_at)
     VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, UUID_TO_BIN(?), ?, ?, ?, ?)`,
    [apartmentOrgId, dwellingUnitId, residentRegistryId, preRegistrationIdText,
     E2E_ADMIN, 'E2E テスト用ダミー申請（死亡確認のため開封要請）', now, now]
  );

  // delinquency_escalations 1件
  await conn.execute(
    `INSERT INTO delinquency_escalations
      (id, organization_id, dwelling_unit_id, resident_registry_id,
       current_stage, delinquency_started_at, created_at, updated_at)
     VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, ?, ?, ?, ?)`,
    [apartmentOrgId, dwellingUnitId, residentRegistryId,
     'STAGE_1_REMINDER', '2026-04-01', now, now]
  );

  // legal_filings 1件
  await conn.execute(
    `INSERT INTO legal_filings
      (id, organization_id, dwelling_unit_id, resident_registry_id,
       filing_type, note, created_at, updated_at)
     VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, ?, ?, ?, ?)`,
    [apartmentOrgId, dwellingUnitId, residentRegistryId,
     'ABSENTEE_PROPERTY_MANAGER', 'E2E テスト用ダミー申立て（不在者財産管理人）', now, now]
  );

  console.log(`F09.15 apartment seed: org=${apartmentOrgId}, dwelling=${dwellingUnitId}, resident=${residentRegistryId}`);
  console.log(`F09.15 succession rows: covenant x1, pre_registration x1, unseal_request x1, delinquency_escalation x1, legal_filing x1`);

  // ============================================================
  // サマリー
  // ============================================================
  console.log('\n========================================');
  console.log('  E2E SEED DATA - COMPLETE');
  console.log('========================================');
  console.log(`E2E_USER id:    ${E2E_USER}  (e2e-user@test.mannschaft.local)`);
  console.log(`E2E_ADMIN id:   ${E2E_ADMIN}  (e2e-admin@test.mannschaft.local)`);
  console.log(`E2E_OUTSIDER id: ${E2E_OUTSIDER}  (e2e-outsider@test.mannschaft.local / 非メンバー・認可スモーク専用)`);
  console.log(`Users:         20 dummy (id ${userIds[0]}-${userIds[19]})`);
  console.log(`Organizations: 10`);
  console.log(`  JFA (top) -> 関東FA -> 東京FA, 神奈川FA`);
  console.log(`  JFA (top) -> 関西FA -> 大阪FA`);
  console.log(`  東京FA -> FC東京ユースアカデミー`);
  console.log(`  神奈川FA -> 横浜FCジュニア`);
  console.log(`  独立: NPOフットサル連盟, 地域スポーツクラブ協会`);
  console.log(`Teams:         10`);
  console.log(`  FC東京U-18 (id=${teams.fcTokyoU18}), U-15 -> FC東京ユースアカデミー`);
  console.log(`  横浜FCジュニアA -> 横浜FCジュニア`);
  console.log(`  東京都選抜U-16 -> 東京FA`);
  console.log(`  大阪府トレセンU-14 -> 大阪FA`);
  console.log(`  フットサルクラブA -> NPOフットサル連盟`);
  console.log(`  川崎スポーツクラブ, 草サッカー倶楽部 -> 地域スポーツクラブ協会`);
  console.log(`  独立: インディーFC, 日曜キッカーズ`);
  console.log(`Schedules:     12`);
  console.log(`Chat channels: 8`);
  console.log(`Notifications: 7`);
  console.log(`Activity template: id=${activityTemplateId} (試合結果)`);
  console.log(`Activity result:   id=${activityResultId} (春季合宿2026, PUBLIC)`);
  console.log(`F18 wallet:        E2E_USER に カード 5 / グループ 2 を投入`);
  console.log(`F02.9 favorites:   E2E_USER に TEAM x2 + ORGANIZATION x1 = 3 件を投入`);
  console.log(`F17 villages:      OFFICIAL x1 + COMMUNITY x1 を投入。E2E_USER は COMMUNITY 村に参加`);
  console.log(`F09.15 succession: apartment org + dwelling + resident + 5 succession records を投入`);
  console.log('========================================');

  await conn.end();
})().catch(e => { console.error('FATAL:', e.message, e.sql || ''); process.exit(1); });
