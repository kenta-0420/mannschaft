const mysql = require('mysql2/promise');
const bcrypt = require('bcryptjs');

(async () => {
  const conn = await mysql.createConnection({
    host: '127.0.0.1', port: 3306,
    user: 'mannschaft', password: 'mannschaft', database: 'mannschaft'
  });

  const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
  const SYS = 1;
  const E2E_USER = 5;
  const E2E_ADMIN = 6;

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
      'INSERT INTO users (email, password_hash, last_name, first_name, display_name, is_searchable, encryption_key_version, locale, timezone, status, reporting_restricted, created_at, updated_at) VALUES (?,?,?,?,?,1,1,?,?,?,0,?,?)',
      [email, hash, last, first, display, 'ja', 'Asia/Tokyo', 'ACTIVE', now, now]
    );
    const [[r]] = await conn.execute('SELECT LAST_INSERT_ID() as id');
    userIds.push(Number(r.id));
  }
  console.log(`Users created: ${userIds.length} (id ${userIds[0]}-${userIds[userIds.length - 1]})`);

  // ============================================================
  // 2. 組織（JFA階層構造）
  // ============================================================
  const orgs = {};

  async function createOrg(name, orgType, parentId, pref, city) {
    await conn.execute(
      'INSERT INTO organizations (name, org_type, parent_organization_id, prefecture, city, visibility, hierarchy_visibility, supporter_enabled, version, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,1,?,?)',
      [name, orgType, parentId, pref, city, 'PUBLIC', 'FULL', 1, now, now]
    );
    const [[r]] = await conn.execute('SELECT LAST_INSERT_ID() as id');
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

  console.log(`Organizations created: ${Object.keys(orgs).length}`, JSON.stringify(orgs));

  // ============================================================
  // 3. チーム
  // ============================================================
  const teams = {};

  async function createTeam(name, template, pref, city) {
    await conn.execute(
      'INSERT INTO teams (name, template, prefecture, city, visibility, supporter_enabled, version, created_at, updated_at) VALUES (?,?,?,?,?,?,1,?,?)',
      [name, template, pref, city, 'PUBLIC', 1, now, now]
    );
    const [[r]] = await conn.execute('SELECT LAST_INSERT_ID() as id');
    return Number(r.id);
  }

  async function linkTeamOrg(teamId, orgId) {
    await conn.execute(
      'INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) VALUES (?,?,?,?,?)',
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

  console.log(`Teams created: ${Object.keys(teams).length}`, JSON.stringify(teams));

  // ============================================================
  // 4. ロール配置
  // ============================================================
  async function assignRole(userId, roleId, teamId, orgId) {
    await conn.execute(
      'INSERT INTO user_roles (user_id, role_id, team_id, organization_id, granted_by, created_at, updated_at) VALUES (?,?,?,?,?,?,?)',
      [userId, roleId, teamId || null, orgId || null, SYS, now, now]
    );
  }

  // E2E admin: JFA ADMIN + FC東京U-18 ADMIN
  await assignRole(E2E_ADMIN, 2, null, orgs.jfa);
  await assignRole(E2E_ADMIN, 2, teams.fcTokyoU18, null);

  // E2E user: FC東京U-18 MEMBER
  await assignRole(E2E_USER, 4, teams.fcTokyoU18, null);

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
      `INSERT INTO schedules (team_id, organization_id, title, event_type, start_at, end_at, location,
        all_day, visibility, min_view_role, min_response_role, status, attendance_required,
        attendance_status, comment_option, is_exception, created_by, created_at, updated_at)
       VALUES (?,?,?,?,?,?,?,0,?,?,?,?,?,?,?,0,?,?,?)`,
      [teamId, orgId, title, eventType, startAt, endAt, location,
       'PUBLIC', 'MEMBER', 'MEMBER', 'CONFIRMED', 1, 'READY', 'ALLOWED', createdBy, now, now]
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
      'INSERT INTO chat_channels (channel_type, team_id, organization_id, name, is_private, is_archived, version, created_by, created_at, updated_at) VALUES (?,?,?,?,0,0,1,?,?,?)',
      [type, teamId, orgId, name, createdBy, now, now]
    );
    const [[r]] = await conn.execute('SELECT LAST_INSERT_ID() as id');
    return Number(r.id);
  }

  const ch1 = await createChannel('TEAM', teams.fcTokyoU18, null, '全体連絡', userIds[0]);
  const ch2 = await createChannel('TEAM', teams.fcTokyoU18, null, '試合速報', userIds[0]);
  const ch3 = await createChannel('TEAM', teams.fcTokyoU18, null, 'コーチ専用', userIds[0]);
  const ch4 = await createChannel('TEAM', teams.yokohamaJr, null, '全体連絡', userIds[9]);
  const ch5 = await createChannel('ORGANIZATION', null, orgs.tokyo, '理事会連絡', E2E_ADMIN);
  const ch6 = await createChannel('ORGANIZATION', null, orgs.tokyo, '大会運営', userIds[12]);
  const ch7 = await createChannel('TEAM', teams.fcTokyoU15, null, '全体連絡', userIds[5]);
  const ch8 = await createChannel('TEAM', teams.indieFC, null, '雑談', userIds[18]);

  // チャットメッセージ
  const [chatMsgTable] = await conn.execute("SHOW TABLES LIKE 'chat_messages'");
  if (chatMsgTable.length > 0) {
    const [chatCols] = await conn.execute('SHOW COLUMNS FROM chat_messages');
    const colNames = chatCols.map(c => c.Field);
    console.log('chat_messages columns:', colNames.join(', '));

    if (colNames.includes('channel_id') && colNames.includes('sender_id') && colNames.includes('content')) {
      const insertMsg = 'INSERT INTO chat_messages (channel_id, sender_id, content, message_type, created_at, updated_at) VALUES (?,?,?,?,?,?)';
      await conn.execute(insertMsg, [ch1, userIds[0], '明日の練習は9時集合です。グラウンドシューズを忘れずに！', 'TEXT', '2026-04-04 20:00:00', '2026-04-04 20:00:00']);
      await conn.execute(insertMsg, [ch1, userIds[1], '了解です！', 'TEXT', '2026-04-04 20:05:00', '2026-04-04 20:05:00']);
      await conn.execute(insertMsg, [ch1, userIds[2], '承知しました', 'TEXT', '2026-04-04 20:10:00', '2026-04-04 20:10:00']);
      await conn.execute(insertMsg, [ch1, E2E_USER, '分かりました！', 'TEXT', '2026-04-04 20:15:00', '2026-04-04 20:15:00']);
      await conn.execute(insertMsg, [ch2, userIds[0], '第3節 vs 横浜FCユース、キックオフ14:00。会場: 西が丘サッカー場', 'TEXT', '2026-04-05 12:00:00', '2026-04-05 12:00:00']);
      console.log('Chat messages created: 5');
    }
  }

  console.log(`Chat channels created: 8`);

  // ============================================================
  // 7. 通知
  // ============================================================
  async function notify(userId, type, priority, title, body, srcType, srcId, scopeType, scopeId, url) {
    await conn.execute(
      'INSERT INTO notifications (user_id, notification_type, priority, title, body, source_type, source_id, scope_type, scope_id, action_url, is_read, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,0,?)',
      [userId, type, priority, title, body, srcType, srcId, scopeType, scopeId, url, now]
    );
  }

  await notify(E2E_USER, 'SCHEDULE_CREATED', 'NORMAL', '新しい予定が追加されました', 'プリンスリーグ関東 第3節が追加されました', 'SCHEDULE', 1, 'TEAM', teams.fcTokyoU18, `/teams/${teams.fcTokyoU18}/schedules`);
  await notify(E2E_USER, 'CHAT_MESSAGE', 'NORMAL', '田中太郎さんがメッセージを送信', '明日の練習は9時集合です', 'CHAT_CHANNEL', ch1, 'TEAM', teams.fcTokyoU18, `/chat/${ch1}`);
  await notify(E2E_USER, 'SCHEDULE_REMINDER', 'HIGH', '明日の試合のリマインダー', 'プリンスリーグ関東 第3節 vs 横浜FCユース（14:00〜）', 'SCHEDULE', 1, 'TEAM', teams.fcTokyoU18, `/teams/${teams.fcTokyoU18}/schedules`);
  await notify(E2E_USER, 'TEAM_ANNOUNCEMENT', 'NORMAL', 'チームからのお知らせ', '4月の練習スケジュールが更新されました', 'TEAM', teams.fcTokyoU18, 'TEAM', teams.fcTokyoU18, `/teams/${teams.fcTokyoU18}`);

  await notify(E2E_ADMIN, 'MEMBER_JOINED', 'NORMAL', '新メンバーが参加しました', '佐藤花子さんがFC東京U-18に参加しました', 'TEAM', teams.fcTokyoU18, 'TEAM', teams.fcTokyoU18, `/teams/${teams.fcTokyoU18}/members`);
  await notify(E2E_ADMIN, 'SYSTEM_ANNOUNCEMENT', 'HIGH', 'システムメンテナンスのお知らせ', '4/10 AM2:00〜4:00 にメンテナンスを実施します', 'SYSTEM', null, 'SYSTEM', null, '/announcements');
  await notify(E2E_ADMIN, 'SCHEDULE_CREATED', 'NORMAL', '審判講習会が追加されました', '4/10 東京体育館にて4級審判講習会を実施', 'SCHEDULE', 1, 'ORGANIZATION', orgs.tokyo, `/organizations/${orgs.tokyo}/schedules`);

  console.log('Notifications created: 7');

  // ============================================================
  // サマリー
  // ============================================================
  console.log('\n========================================');
  console.log('  E2E SEED DATA - COMPLETE');
  console.log('========================================');
  console.log(`Users:         20 dummy (id ${userIds[0]}-${userIds[19]})`);
  console.log(`Organizations: 10`);
  console.log(`  JFA (top) -> 関東FA -> 東京FA, 神奈川FA`);
  console.log(`  JFA (top) -> 関西FA -> 大阪FA`);
  console.log(`  東京FA -> FC東京ユースアカデミー`);
  console.log(`  神奈川FA -> 横浜FCジュニア`);
  console.log(`  独立: NPOフットサル連盟, 地域スポーツクラブ協会`);
  console.log(`Teams:         10`);
  console.log(`  FC東京U-18, U-15 -> FC東京ユースアカデミー`);
  console.log(`  横浜FCジュニアA -> 横浜FCジュニア`);
  console.log(`  東京都選抜U-16 -> 東京FA`);
  console.log(`  大阪府トレセンU-14 -> 大阪FA`);
  console.log(`  フットサルクラブA -> NPOフットサル連盟`);
  console.log(`  川崎スポーツクラブ, 草サッカー倶楽部 -> 地域スポーツクラブ協会`);
  console.log(`  独立: インディーFC, 日曜キッカーズ`);
  console.log(`Schedules:     12`);
  console.log(`Chat channels: 8`);
  console.log(`Notifications: 7`);
  console.log('========================================');

  await conn.end();
})().catch(e => { console.error('FATAL:', e.message, e.sql || ''); process.exit(1); });
