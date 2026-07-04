/**
 * F08.7 Wave C: 可視性6×ロール6マトリクス E2E 用 seed スクリプト
 *
 * 前提: seed-e2e-data.js が先に実行済みであること。
 * 主催ORG: org_id=1 (日本サッカー協会（テスト）)
 *
 * 投入内容:
 * - ユーザー4種（org1-supporter / team-participant / outsider / scorekeeper-user）
 * - org1配下に visibility 6大会 × division × participant × matchday × match
 * - スコアキーパー指名: 1大会で scorekeeper-user を指名
 * - INSERT IGNORE でべき等化（重複実行可能）
 *
 * ロール割当:
 *   ① ORG ADMIN     : e2e-admin@test.mannschaft.local (既存 org1 role_id=2)
 *   ② ORG MEMBER    : e2e-user@test.mannschaft.local (既存 org1 memberships)
 *   ③ ORG SUPPORTER : f087-supporter@test.mannschaft.local (新規 org1 role_id=5)
 *   ④ 未認証(GUEST) : Bearer token なし（seed不要）
 *   ⑤ 参加チームMEMBER : f087-participant@test.mannschaft.local (participant team MEMBER)
 *   ⑥ 非所属の他者  : f087-outsider@test.mannschaft.local (ロールなし)
 */

const mysql = require("mysql2/promise");
const bcrypt = require("bcryptjs");
const crypto = require("crypto");

const F18_ENCRYPTION_KEY_BASE64 = "pE8ozQmki8gdQ1nCFC86zcK1SSNJuVRLd7uhdpO4ihc=";
const F18_KEY_BYTES = Buffer.from(F18_ENCRYPTION_KEY_BASE64, "base64");

function encryptForTest(plain) {
  if (plain === null || plain === undefined) return null;
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", F18_KEY_BYTES, iv);
  const enc = Buffer.concat([cipher.update(plain, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return Buffer.concat([iv, enc, tag]).toString("base64");
}

(async () => {
  const conn = await mysql.createConnection({
    host: "127.0.0.1", port: 3306,
    user: "mannschaft", password: "mannschaft", database: "mannschaft",
    charset: "utf8mb4", // 二重エンコード再発防止のため接続文字コードを明示
  });

  const now = new Date().toISOString().slice(0, 19).replace("T", " ");
  const SYS = 1;
  const ORG1_ID = 1; // 日本サッカー協会（テスト）

  const hashStrength8 = (plain) => bcrypt.hashSync(plain, 8).replace("$2b$", "$2a$");
  const passwd = hashStrength8("TestPass2026!");

  // ============================================================
  // 1. 新規ユーザー4種
  // ============================================================
  const newUsers = [
    { email: "f087-supporter@test.mannschaft.local",       last: "F087", first: "サポーター",         display: "F087サポーター" },
    { email: "f087-participant@test.mannschaft.local",     last: "F087", first: "参加者",             display: "F087参加チームMember" },
    { email: "f087-outsider@test.mannschaft.local",        last: "F087", first: "部外者",             display: "F087非所属ユーザー" },
    { email: "f087-scorekeeper@test.mannschaft.local",     last: "F087", first: "スコアキーパー",     display: "F087スコアキーパー" },
    { email: "f087-team-admin@test.mannschaft.local",      last: "F087", first: "チームADMIN",        display: "F087参加チームADMIN" },
  ];

  const userIdMap = {};
  for (const u of newUsers) {
    await conn.execute(
      `INSERT IGNORE INTO users
        (email, password_hash, last_name, first_name, display_name,
         is_searchable, encryption_key_version, locale, timezone,
         status, reporting_restricted, created_at, updated_at)
       VALUES (?,?,?,?,?,1,1,'ja','Asia/Tokyo','ACTIVE',0,?,?)`,
      [u.email, passwd, encryptForTest(u.last), encryptForTest(u.first), u.display, now, now]
    );
    const [[r]] = await conn.execute("SELECT id FROM users WHERE email = ?", [u.email]);
    userIdMap[u.email] = Number(r.id);
  }
  console.log("F087 users:", JSON.stringify(userIdMap));

  // ============================================================
  // 2. 参加チームを作成（f087-participant用）
  // ============================================================
  const participantTeamName = "F087参加チーム（テスト）";
  const participantTeamSlug = "f087-participant-team";
  await conn.execute(
    `INSERT IGNORE INTO teams
      (slug, name, template, prefecture, city, visibility, supporter_enabled, version, created_at, updated_at)
     VALUES (?,?,?,?,?,'PUBLIC',1,1,?,?)`,
    [participantTeamSlug, participantTeamName, "SPORTS", "東京都", "新宿区", now, now]
  );
  const [[teamRow]] = await conn.execute("SELECT id FROM teams WHERE name = ?", [participantTeamName]);
  const PARTICIPANT_TEAM_ID = Number(teamRow.id);
  console.log("F087 participant team id:", PARTICIPANT_TEAM_ID);

  // 対戦相手チーム（参加するだけで PARTICIPANTS_ONLY アクセス権の対象外）
  const opponentTeamName = "F087対戦相手チーム（テスト）";
  const opponentTeamSlug = "f087-opponent-team";
  await conn.execute(
    `INSERT IGNORE INTO teams
      (slug, name, template, prefecture, city, visibility, supporter_enabled, version, created_at, updated_at)
     VALUES (?,?,?,?,?,'PUBLIC',1,1,?,?)`,
    [opponentTeamSlug, opponentTeamName, "SPORTS", "東京都", "渋谷区", now, now]
  );
  const [[oppTeamRow]] = await conn.execute("SELECT id FROM teams WHERE name = ?", [opponentTeamName]);
  const OPPONENT_TEAM_ID = Number(oppTeamRow.id);
  console.log("F087 opponent team id:", OPPONENT_TEAM_ID);

  // ============================================================
  // 3. ロール割当
  // ============================================================
  async function assignRole(userId, roleId, teamId, orgId) {
    await conn.execute(
      `INSERT IGNORE INTO user_roles
        (user_id, role_id, team_id, organization_id, granted_by, created_at, updated_at)
       VALUES (?,?,?,?,?,?,?)`,
      [userId, roleId, teamId || null, orgId || null, SYS, now, now]
    );
    const isScopedMembershipRole = roleId >= 2 && roleId <= 5 && (teamId || orgId);
    if (isScopedMembershipRole) {
      const scopeType = teamId ? "TEAM" : "ORGANIZATION";
      const scopeId = teamId || orgId;
      const roleKind = roleId === 5 ? "SUPPORTER" : "MEMBER";
      const [rows] = await conn.execute(
        "SELECT id FROM memberships WHERE user_id=? AND scope_type=? AND scope_id=? AND left_at IS NULL LIMIT 1",
        [userId, scopeType, scopeId]
      );
      if (rows.length === 0) {
        await conn.execute(
          `INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, invited_by, created_at, updated_at)
           VALUES (?,?,?,?,?,?,?,?)`,
          [userId, scopeType, scopeId, roleKind, now, SYS, now, now]
        );
      }
    }
  }

  const supporterId   = userIdMap["f087-supporter@test.mannschaft.local"];
  const participantId = userIdMap["f087-participant@test.mannschaft.local"];
  const scorekeeperId = userIdMap["f087-scorekeeper@test.mannschaft.local"];
  const teamAdminId   = userIdMap["f087-team-admin@test.mannschaft.local"];

  // ③ ORG SUPPORTER: org1 role_id=5
  await assignRole(supporterId, 5, null, ORG1_ID);
  // ⑤ 参加チームMEMBER: PARTICIPANT_TEAM MEMBER (role_id=4)
  await assignRole(participantId, 4, PARTICIPANT_TEAM_ID, null);
  // ⑦ 参加チームADMIN: PARTICIPANT_TEAM ADMIN (role_id=2)
  await assignRole(teamAdminId, 2, PARTICIPANT_TEAM_ID, null);
  // scorekeeper-user: org1への明示的ロールなし（後でスコアキーパー指名のみ）
  // outsider: ロールなし（何も付与しない）
  console.log("Roles assigned");

  // ============================================================
  // 4. visibility 6大会を作成
  // ============================================================
  const visibilities = [
    "PUBLIC",
    "SUPPORTERS_AND_ABOVE",
    "MEMBERS_AND_ABOVE",
    "ADMINS_AND_ABOVE",
    "SCOPE_AFFILIATED",
    "PARTICIPANTS_ONLY",
  ];

  // e2e-admin のuserIdを取得
  const [[adminRow]] = await conn.execute("SELECT id FROM users WHERE email='e2e-admin@test.mannschaft.local'");
  const ADMIN_USER_ID = Number(adminRow.id);

  const tournamentIdMap = {};
  const divisionIdMap = {};
  const matchdayIdMap = {};
  const matchIdMap = {};
  const participant1IdMap = {}; // 参加チーム（PARTICIPANT_TEAM_ID）
  const participant2IdMap = {}; // 対戦相手チーム（OPPONENT_TEAM_ID）

  for (const vis of visibilities) {
    const tName = `F087-E2E-${vis}大会（テスト）`;

    // 既存大会の確認（べき等化: nameで検索してupsert）
    let [[existRow]] = await conn.execute(
      "SELECT id FROM tournaments WHERE name=? AND organization_id=?",
      [tName, ORG1_ID]
    );

    if (!existRow) {
      await conn.execute(
        `INSERT INTO tournaments
          (organization_id, name, format, season, start_date, end_date,
           win_points, draw_points, loss_points, has_draw, has_sets, has_extra_time, has_penalties,
           score_unit_label, league_round_type, knockout_legs, visibility, status,
           version, created_by, created_at, updated_at)
         VALUES (?,?,'LEAGUE','2026','2026-06-01','2026-08-31',3,1,0,1,0,0,0,'点','SINGLE',1,?,'IN_PROGRESS',0,?,?,?)`,
        [ORG1_ID, tName, vis, ADMIN_USER_ID, now, now]
      );
      [[existRow]] = await conn.execute(
        "SELECT id FROM tournaments WHERE name=? AND organization_id=?",
        [tName, ORG1_ID]
      );
    } else {
      // 既存の visibility を更新
      await conn.execute(
        "UPDATE tournaments SET visibility=?, status='IN_PROGRESS', updated_at=? WHERE id=?",
        [vis, now, existRow.id]
      );
    }

    const tId = Number(existRow.id);
    tournamentIdMap[vis] = tId;

    // Division
    let [[divRow]] = await conn.execute(
      "SELECT id FROM tournament_divisions WHERE tournament_id=? AND name='Aリーグ' LIMIT 1",
      [tId]
    );
    if (!divRow) {
      await conn.execute(
        `INSERT INTO tournament_divisions
          (tournament_id, name, level, promotion_slots, relegation_slots,
           playoff_promotion_slots, sort_order, created_at, updated_at)
         VALUES (?,?,1,0,0,0,0,?,?)`,
        [tId, "Aリーグ", now, now]
      );
      [[divRow]] = await conn.execute(
        "SELECT id FROM tournament_divisions WHERE tournament_id=? AND name='Aリーグ' LIMIT 1",
        [tId]
      );
    }
    const divId = Number(divRow.id);
    divisionIdMap[vis] = divId;

    // Participants (PARTICIPANT_TEAM_ID と OPPONENT_TEAM_ID)
    let [[p1Row]] = await conn.execute(
      "SELECT id FROM tournament_participants WHERE division_id=? AND team_id=?",
      [divId, PARTICIPANT_TEAM_ID]
    );
    if (!p1Row) {
      await conn.execute(
        "INSERT INTO tournament_participants (division_id, team_id, seed, display_name, status, joined_at) VALUES (?,?,1,'F087参加チーム','ACTIVE',?)",
        [divId, PARTICIPANT_TEAM_ID, now]
      );
      [[p1Row]] = await conn.execute(
        "SELECT id FROM tournament_participants WHERE division_id=? AND team_id=?",
        [divId, PARTICIPANT_TEAM_ID]
      );
    }
    participant1IdMap[vis] = Number(p1Row.id);

    let [[p2Row]] = await conn.execute(
      "SELECT id FROM tournament_participants WHERE division_id=? AND team_id=?",
      [divId, OPPONENT_TEAM_ID]
    );
    if (!p2Row) {
      await conn.execute(
        "INSERT INTO tournament_participants (division_id, team_id, seed, display_name, status, joined_at) VALUES (?,?,2,'F087対戦相手チーム','ACTIVE',?)",
        [divId, OPPONENT_TEAM_ID, now]
      );
      [[p2Row]] = await conn.execute(
        "SELECT id FROM tournament_participants WHERE division_id=? AND team_id=?",
        [divId, OPPONENT_TEAM_ID]
      );
    }
    participant2IdMap[vis] = Number(p2Row.id);

    // Matchday
    let [[mdRow]] = await conn.execute(
      "SELECT id FROM tournament_matchdays WHERE division_id=? AND matchday_number=1 LIMIT 1",
      [divId]
    );
    if (!mdRow) {
      await conn.execute(
        "INSERT INTO tournament_matchdays (division_id, name, matchday_number, scheduled_date, status, created_at, updated_at) VALUES (?,?,1,'2026-06-15','SCHEDULED',?,?)",
        [divId, "第1節", now, now]
      );
      [[mdRow]] = await conn.execute(
        "SELECT id FROM tournament_matchdays WHERE division_id=? AND matchday_number=1 LIMIT 1",
        [divId]
      );
    }
    const mdId = Number(mdRow.id);
    matchdayIdMap[vis] = mdId;

    // Match (1試合)
    let [[matchRow]] = await conn.execute(
      "SELECT id FROM tournament_matches WHERE matchday_id=? AND home_participant_id=? LIMIT 1",
      [mdId, participant1IdMap[vis]]
    );
    if (!matchRow) {
      await conn.execute(
        `INSERT INTO tournament_matches
          (matchday_id, home_participant_id, away_participant_id,
           match_number, scheduled_datetime, result, leg, version, status, created_at, updated_at)
         VALUES (?,?,?,1,'2026-06-15 13:00:00','PENDING',1,0,'SCHEDULED',?,?)`,
        [mdId, participant1IdMap[vis], participant2IdMap[vis], now, now]
      );
      [[matchRow]] = await conn.execute(
        "SELECT id FROM tournament_matches WHERE matchday_id=? AND home_participant_id=? LIMIT 1",
        [mdId, participant1IdMap[vis]]
      );
    }
    matchIdMap[vis] = Number(matchRow.id);

    console.log(`Tournament ${vis}: id=${tId}, div=${divId}, part1=${participant1IdMap[vis]}, part2=${participant2IdMap[vis]}, md=${mdId}, match=${matchIdMap[vis]}`);
  }

  // ============================================================
  // 5. スコアキーパー指名（PUBLIC大会のみ）
  // ============================================================
  const publicTId = tournamentIdMap["PUBLIC"];
  // 既存の指名を確認してなければ INSERT
  const [skRows] = await conn.execute(
    "SELECT id FROM tournament_scorekeepers WHERE tournament_id=? AND user_id=?",
    [publicTId, scorekeeperId]
  );
  if (skRows.length === 0) {
    const skId = Buffer.from(crypto.randomUUID().replace(/-/g, ""), "hex");
    await conn.execute(
      "INSERT INTO tournament_scorekeepers (id, tournament_id, user_id, created_by, created_at) VALUES (?,?,?,?,?)",
      [skId, publicTId, scorekeeperId, ADMIN_USER_ID, now]
    );
    console.log(`Scorekeeper assigned: userId=${scorekeeperId} → tournament PUBLIC (id=${publicTId})`);
  } else {
    console.log(`Scorekeeper already assigned: userId=${scorekeeperId} → tournament PUBLIC (id=${publicTId})`);
  }

  // ============================================================
  // 6. サマリー
  // ============================================================
  console.log("\n========================================");
  console.log("  F08.7 Wave C E2E SEED - COMPLETE");
  console.log("========================================");
  console.log("ユーザー:");
  console.log(`  ① ORG ADMIN     : e2e-admin@test.mannschaft.local (org1 role_id=2)`);
  console.log(`  ② ORG MEMBER    : e2e-user@test.mannschaft.local (org1 memberships)`);
  console.log(`  ③ ORG SUPPORTER : f087-supporter@test.mannschaft.local id=${supporterId} (org1 role_id=5)`);
  console.log(`  ④ 未認証(GUEST) : Bearer token なし`);
  console.log(`  ⑤ 参加チームMember: f087-participant@test.mannschaft.local id=${participantId} (team ${PARTICIPANT_TEAM_ID} role_id=4)`);
  console.log(`  ⑥ 非所属の他者  : f087-outsider@test.mannschaft.local id=${userIdMap["f087-outsider@test.mannschaft.local"]} (ロールなし)`);
  console.log(`  scorekeeper     : f087-scorekeeper@test.mannschaft.local id=${scorekeeperId}`);
  console.log(`  ⑦ 参加チームADMIN: f087-team-admin@test.mannschaft.local id=${teamAdminId} (team ${PARTICIPANT_TEAM_ID} role_id=2)`);
  console.log("\n大会:");
  for (const vis of visibilities) {
    console.log(`  ${vis}: tournamentId=${tournamentIdMap[vis]}, divId=${divisionIdMap[vis]}, matchId=${matchIdMap[vis]}`);
  }
  console.log(`\nParticipant team: id=${PARTICIPANT_TEAM_ID} (${participantTeamName})`);
  console.log(`Opponent   team:  id=${OPPONENT_TEAM_ID} (${opponentTeamName})`);
  console.log(`Scorekeeper指名: userId=${scorekeeperId} → PUBLIC tournament (id=${publicTId})`);
  console.log("========================================");

  // JSONサマリーをファイルに書き出す（E2Eスクリプトから参照用）
  const summary = {
    users: {
      admin: { email: "e2e-admin@test.mannschaft.local", password: "TestPass2026!" },
      member: { email: "e2e-user@test.mannschaft.local", password: "TestPass2026!" },
      supporter: { email: "f087-supporter@test.mannschaft.local", password: "TestPass2026!" },
      participant: { email: "f087-participant@test.mannschaft.local", password: "TestPass2026!" },
      outsider: { email: "f087-outsider@test.mannschaft.local", password: "TestPass2026!" },
      scorekeeper: { email: "f087-scorekeeper@test.mannschaft.local", password: "TestPass2026!" },
      teamAdmin: { email: "f087-team-admin@test.mannschaft.local", password: "TestPass2026!" },
    },
    orgId: ORG1_ID,
    participantTeamId: PARTICIPANT_TEAM_ID,
    opponentTeamId: OPPONENT_TEAM_ID,
    tournaments: {},
  };
  for (const vis of visibilities) {
    summary.tournaments[vis] = {
      tournamentId: tournamentIdMap[vis],
      divisionId: divisionIdMap[vis],
      matchId: matchIdMap[vis],
      matchdayId: matchdayIdMap[vis],
      participant1Id: participant1IdMap[vis],
      participant2Id: participant2IdMap[vis],
    };
  }
  const fs = require("fs");
  const summaryPath = __dirname + "/f087-e2e-seed-summary.json";
  fs.writeFileSync(summaryPath, JSON.stringify(summary, null, 2));
  console.log(`Summary written to: ${summaryPath}`);

  await conn.end();
})().catch(e => {
  console.error("FATAL:", e.message, e.sql || "");
  process.exit(1);
});
