/**
 * F08.10 多競技ライブ記録 実機 E2E 用 seed スクリプト（ターン制・団体戦）
 *
 * 前提: seed-e2e-data.js → seed-f0810-multisport-e2e.js が実行済みであること。
 *
 * 目的:
 *   ターン制（将棋/囲碁）および団体戦の実機 E2E が共通で使える名前空間を提供する。
 *   既存 seed（f0810-basketball-team / f0810-volleyball-team）と衝突しないよう
 *   固有 slug（接頭辞 f0810-turn-*）で組織内に追加チームを用意する。
 *
 * 命名空間（他 E2E 隊と衝突しない固有 slug / email）:
 *   組織:   f0810-multisport-club（既存 seed と共用・追加作成しない）
 *   チーム: f0810-shogi-team（将棋・個人戦＋団体戦で共用）
 *           f0810-go-team（囲碁・個人戦）
 *   会員:   f0810-turn-recorder@test.mannschaft.local（ADMIN・記録者）
 *           f0810-shogi-player1..2@test.mannschaft.local（対局者・将棋）
 *           f0810-go-player1..2@test.mannschaft.local（対局者・囲碁）
 *           e2e-admin（既存 SYSTEM_ADMIN）も両チーム ADMIN を付与
 *
 * 投入内容:
 *   - 既存組織（f0810-multisport-club）にチーム 2（将棋/囲碁）を追加
 *   - 記録者 + 対局者4人 を両チームに配置
 *   - e2e-admin にも両チーム ADMIN を付与
 *
 * 実行:
 *   node backend/scripts/seed-f0810-turn-team-e2e.js
 */

const mysql = require("mysql2/promise");
const bcrypt = require("bcryptjs");
const crypto = require("crypto");

const F18_ENCRYPTION_KEY_BASE64 = "pE8ozQmki8gdQ1nCFC86zcK1SSNJuVRLd7uhdpO4ihc=";
const F18_KEY_BYTES = Buffer.from(F18_ENCRYPTION_KEY_BASE64, "base64");
if (F18_KEY_BYTES.length !== 32) {
  throw new Error("encryption key must decode to 32 bytes");
}

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
  const hashStrength8 = (plain) => bcrypt.hashSync(plain, 8).replace("$2b$", "$2a$");
  const passwd = hashStrength8("TestPass2026!");

  // ------------------------------------------------------------
  // 既存 e2e-admin の ID を取得
  // ------------------------------------------------------------
  const [[adminRow]] = await conn.execute(
    "SELECT id FROM users WHERE email = ?",
    ["e2e-admin@test.mannschaft.local"]
  );
  if (!adminRow) {
    throw new Error(
      "e2e-admin が見つかりません。先に backend/scripts/seed-e2e-data.js を実行してください。"
    );
  }
  const E2E_ADMIN = Number(adminRow.id);

  // ------------------------------------------------------------
  // 既存組織（f0810-multisport-club）を取得（追加作成しない）
  // ------------------------------------------------------------
  const ORG_SLUG = "f0810-multisport-club";
  const [[orgRow]] = await conn.execute(
    "SELECT id FROM organizations WHERE slug = ?", [ORG_SLUG]
  );
  if (!orgRow) {
    throw new Error(
      "f0810-multisport-club が見つかりません。先に seed-f0810-multisport-e2e.js を実行してください。"
    );
  }
  const ORG_ID = Number(orgRow.id);
  console.log(`Organization: id=${ORG_ID}, slug=${ORG_SLUG} (既存を再利用)`);

  // ------------------------------------------------------------
  // ユーザー作成ヘルパー
  // ------------------------------------------------------------
  async function upsertUser(email, last, first, display) {
    await conn.execute(
      `INSERT IGNORE INTO users
        (email, password_hash, last_name, first_name, display_name,
         is_searchable, encryption_key_version, locale, timezone,
         status, reporting_restricted, created_at, updated_at)
       VALUES (?,?,?,?,?,1,1,?,?,?,0,?,?)`,
      [email, passwd, encryptForTest(last), encryptForTest(first), display,
       "ja", "Asia/Tokyo", "ACTIVE", now, now]
    );
    const [[r]] = await conn.execute("SELECT id FROM users WHERE email = ?", [email]);
    return Number(r.id);
  }

  const turnRecorderId = await upsertUser(
    "f0810-turn-recorder@test.mannschaft.local", "F0810", "ターン記録者", "F08.10ターン記録者"
  );
  const shogiPlayerIds = [];
  const shogiPlayerNames = [
    ["F0810", "将棋選手一", "F08.10将棋選手1"],
    ["F0810", "将棋選手二", "F08.10将棋選手2"],
  ];
  for (let i = 0; i < shogiPlayerNames.length; i++) {
    const [last, first, display] = shogiPlayerNames[i];
    const id = await upsertUser(
      `f0810-shogi-player${i + 1}@test.mannschaft.local`, last, first, display
    );
    shogiPlayerIds.push(id);
  }
  const goPlayerIds = [];
  const goPlayerNames = [
    ["F0810", "囲碁選手一", "F08.10囲碁選手1"],
    ["F0810", "囲碁選手二", "F08.10囲碁選手2"],
  ];
  for (let i = 0; i < goPlayerNames.length; i++) {
    const [last, first, display] = goPlayerNames[i];
    const id = await upsertUser(
      `f0810-go-player${i + 1}@test.mannschaft.local`, last, first, display
    );
    goPlayerIds.push(id);
  }
  console.log(`Users: turn-recorder=${turnRecorderId}, shogiPlayers=[${shogiPlayerIds.join(",")}], goPlayers=[${goPlayerIds.join(",")}]`);

  // ------------------------------------------------------------
  // チーム作成ヘルパー
  // ------------------------------------------------------------
  async function upsertTeam(slug, name) {
    await conn.execute(
      `INSERT IGNORE INTO teams
        (slug, name, template, prefecture, city, visibility, supporter_enabled, version, created_at, updated_at)
       VALUES (?,?,?,?,?,?,?,1,?,?)`,
      [slug, name, "SPORTS", "東京都", "渋谷区", "PUBLIC", 1, now, now]
    );
    const [[r]] = await conn.execute("SELECT id FROM teams WHERE slug = ?", [slug]);
    return Number(r.id);
  }

  const shogiTeamId = await upsertTeam("f0810-shogi-team", "F08.10将棋部（テスト）");
  const goTeamId = await upsertTeam("f0810-go-team", "F08.10囲碁部（テスト）");

  async function linkTeamOrg(teamId, orgId) {
    await conn.execute(
      `INSERT IGNORE INTO team_org_memberships
        (team_id, organization_id, status, invited_at, created_at)
       VALUES (?,?,?,?,?)`,
      [teamId, orgId, "ACTIVE", now, now]
    );
  }
  await linkTeamOrg(shogiTeamId, ORG_ID);
  await linkTeamOrg(goTeamId, ORG_ID);
  console.log(`Teams: shogi=${shogiTeamId}, go=${goTeamId} (linked to org ${ORG_ID})`);

  // ------------------------------------------------------------
  // ロール配置（user_roles ＋ memberships 二系統同期）
  //   role_id: 2=ADMIN, 4=MEMBER
  // ------------------------------------------------------------
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

  // ターン記録者: 両チームの ADMIN
  await assignRole(turnRecorderId, 2, shogiTeamId, null);
  await assignRole(turnRecorderId, 2, goTeamId, null);
  await assignRole(turnRecorderId, 2, null, ORG_ID);

  // e2e-admin: 両チームの ADMIN
  await assignRole(E2E_ADMIN, 2, shogiTeamId, null);
  await assignRole(E2E_ADMIN, 2, goTeamId, null);

  // 将棋選手2人: 将棋チームの MEMBER
  for (const pid of shogiPlayerIds) {
    await assignRole(pid, 4, shogiTeamId, null);
  }
  // 囲碁選手2人: 囲碁チームの MEMBER
  for (const pid of goPlayerIds) {
    await assignRole(pid, 4, goTeamId, null);
  }
  console.log("Roles assigned (turn-recorder/e2e-admin = ADMIN, players = MEMBER)");

  // ------------------------------------------------------------
  // サマリー
  // ------------------------------------------------------------
  console.log("\n========================================");
  console.log("  F08.10 TURN+TEAM E2E SEED - COMPLETE");
  console.log("========================================");
  console.log(`ORG_ID:        ${ORG_ID}   (slug=${ORG_SLUG})`);
  console.log(`SHOGI_TEAM_ID: ${shogiTeamId} (slug=f0810-shogi-team)`);
  console.log(`GO_TEAM_ID:    ${goTeamId} (slug=f0810-go-team)`);
  console.log(`TURN_RECORDER: ${turnRecorderId} (f0810-turn-recorder@test.mannschaft.local)`);
  console.log(`SHOGI_PLAYERS: [${shogiPlayerIds.join(", ")}]`);
  console.log(`GO_PLAYERS:    [${goPlayerIds.join(", ")}]`);
  console.log(`E2E_ADMIN:     ${E2E_ADMIN} (両チーム ADMIN 追加付与済み)`);
  console.log("========================================");

  await conn.end();
})().catch((e) => {
  console.error("FATAL:", e.message, e.sql || "");
  process.exit(1);
});
