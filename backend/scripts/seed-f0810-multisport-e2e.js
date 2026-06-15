/**
 * F08.10 多競技ライブ記録 実機 E2E 用 seed スクリプト
 *
 * 前提: seed-e2e-data.js が先に実行済みであること（e2e-admin / e2e-user が存在すること）。
 *
 * 目的:
 *   後続の連続時間制（バスケ）・セット制（バレー）・ターン制（将棋/囲碁）・団体戦・観戦の
 *   実機 E2E が共通で使える「多競技専用の名前空間」を提供する。
 *   既存 F08.7 / F08.10-入口① seed（FC東京U-18 = org9/team1）と衝突しないよう、
 *   専用の組織・チーム・会員アカウントを固有命名（接頭辞 f0810-*）で用意する。
 *
 * 命名空間（他 E2E 隊と衝突しない固有 slug / email）:
 *   組織:   org slug = f0810-multisport-club（「F08.10多競技クラブ（テスト）」）
 *   チーム: team slug = f0810-basketball-team（バスケ）/ f0810-volleyball-team（バレー）
 *   会員:   f0810-recorder@test.mannschaft.local（両チームの ADMIN・記録者）
 *           f0810-player1..4@test.mannschaft.local（選手・選手グリッドの母集団）
 *           e2e-admin（既存 SYSTEM_ADMIN）も両チーム ADMIN を付与（既存 storageState 流用のため）
 *
 * 投入内容:
 *   - 組織 1（COMMUNITY）
 *   - チーム 2（バスケ / バレー。チームに sport 列は無く、sport は matches 側で決まる）
 *   - team_org_memberships でチーム→組織を ACTIVE リンク
 *   - 記録者 + 選手4人 を両チームに配置（user_roles ＋ memberships の二系統同期）
 *   - e2e-admin にも両チーム ADMIN を付与
 *
 * 注意:
 *   - 試合（matches）・イベント・セットは spec が API 経由で作成/削除する（DB に作り置きしない）。
 *     spec の冪等性（毎回作成→記録→完了→削除）を保つため、seed は「器（組織/チーム/会員/認可）」のみ用意する。
 *   - assignRole は seed-e2e-data.js と同一ロジック（user_roles ＋ memberships 同期）。
 *
 * 実行:
 *   node backend/scripts/seed-f0810-multisport-e2e.js
 */

const mysql = require("mysql2/promise");
const bcrypt = require("bcryptjs");
const crypto = require("crypto");

const F18_ENCRYPTION_KEY_BASE64 = "pE8ozQmki8gdQ1nCFC86zcK1SSNJuVRLd7uhdpO4ihc=";
const F18_KEY_BYTES = Buffer.from(F18_ENCRYPTION_KEY_BASE64, "base64");
if (F18_KEY_BYTES.length !== 32) {
  throw new Error("encryption key must decode to 32 bytes");
}

/** 平文を AES-256-GCM で暗号化して Base64 を返す（BE EncryptionService 互換）。 */
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
  });

  const now = new Date().toISOString().slice(0, 19).replace("T", " ");
  const SYS = 1;
  const hashStrength8 = (plain) => bcrypt.hashSync(plain, 8).replace("$2b$", "$2a$");
  const passwd = hashStrength8("TestPass2026!");

  // ------------------------------------------------------------
  // 既存 e2e-admin の ID を取得（前提 seed 実行済みの確認も兼ねる）
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
  // ユーザー作成ヘルパー（INSERT IGNORE → SELECT で実 ID 取得）
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

  const recorderId = await upsertUser(
    "f0810-recorder@test.mannschaft.local", "F0810", "記録者", "F08.10記録者"
  );
  const playerIds = [];
  const playerNames = [
    ["F0810", "選手一郎", "F08.10選手1"],
    ["F0810", "選手二郎", "F08.10選手2"],
    ["F0810", "選手三郎", "F08.10選手3"],
    ["F0810", "選手四郎", "F08.10選手4"],
  ];
  for (let i = 0; i < playerNames.length; i++) {
    const [last, first, display] = playerNames[i];
    const id = await upsertUser(
      `f0810-player${i + 1}@test.mannschaft.local`, last, first, display
    );
    playerIds.push(id);
  }
  console.log(`Users: recorder=${recorderId}, players=[${playerIds.join(",")}]`);

  // ------------------------------------------------------------
  // 組織（COMMUNITY・固有 slug）
  // ------------------------------------------------------------
  const ORG_SLUG = "f0810-multisport-club";
  const ORG_NAME = "F08.10多競技クラブ（テスト）";
  await conn.execute(
    `INSERT IGNORE INTO organizations
      (slug, name, org_type, parent_organization_id, prefecture, city,
       visibility, hierarchy_visibility, supporter_enabled, version, created_at, updated_at)
     VALUES (?,?,?,?,?,?,?,?,?,1,?,?)`,
    [ORG_SLUG, ORG_NAME, "COMMUNITY", null, "東京都", "渋谷区",
     "PUBLIC", "FULL", 1, now, now]
  );
  const [[orgRow]] = await conn.execute(
    "SELECT id FROM organizations WHERE slug = ?", [ORG_SLUG]
  );
  const ORG_ID = Number(orgRow.id);
  console.log(`Organization: id=${ORG_ID}, slug=${ORG_SLUG}`);

  // ------------------------------------------------------------
  // チーム 2（バスケ / バレー・固有 slug）
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

  const basketTeamId = await upsertTeam("f0810-basketball-team", "F08.10バスケ部（テスト）");
  const volleyTeamId = await upsertTeam("f0810-volleyball-team", "F08.10バレー部（テスト）");
  // 採点競技（SCORED）専用チーム（フィギュアスケート・体操の E2E 用）
  const scoredTeamId = await upsertTeam("f0810-scored-team", "F08.10採点競技部（テスト）");

  async function linkTeamOrg(teamId, orgId) {
    await conn.execute(
      `INSERT IGNORE INTO team_org_memberships
        (team_id, organization_id, status, invited_at, created_at)
       VALUES (?,?,?,?,?)`,
      [teamId, orgId, "ACTIVE", now, now]
    );
  }
  await linkTeamOrg(basketTeamId, ORG_ID);
  await linkTeamOrg(volleyTeamId, ORG_ID);
  await linkTeamOrg(scoredTeamId, ORG_ID);
  console.log(`Teams: basketball=${basketTeamId}, volleyball=${volleyTeamId}, scored=${scoredTeamId} (linked to org ${ORG_ID})`);

  // ------------------------------------------------------------
  // ロール配置（user_roles ＋ memberships 二系統同期・seed-e2e-data.js と同一）
  //   role_id: 2=ADMIN, 4=MEMBER
  // ------------------------------------------------------------
  async function assignRole(userId, roleId, teamId, orgId) {
    await conn.execute(
      `INSERT IGNORE INTO user_roles
        (user_id, role_id, team_id, organization_id, granted_by, created_at, updated_at)
       VALUES (?,?,?,?,?,?,?)`,
      [userId, roleId, teamId || null, orgId || null, SYS, now, now]
    );
    // isMember() は memberships.left_at IS NULL を参照するため二系統同期が必須。
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

  // 記録者: 全チームの ADMIN（共同記録＝主体チーム ADMIN/DEPUTY のみ記録可）
  await assignRole(recorderId, 2, basketTeamId, null);
  await assignRole(recorderId, 2, volleyTeamId, null);
  await assignRole(recorderId, 2, scoredTeamId, null);
  // 組織 ADMIN も付与（観戦/閲覧の組織テナント文脈用）
  await assignRole(recorderId, 2, null, ORG_ID);

  // e2e-admin（既存 SYSTEM_ADMIN / 既存 storageState 流用）にも全チーム ADMIN を付与
  await assignRole(E2E_ADMIN, 2, basketTeamId, null);
  await assignRole(E2E_ADMIN, 2, volleyTeamId, null);
  await assignRole(E2E_ADMIN, 2, scoredTeamId, null);
  await assignRole(E2E_ADMIN, 2, null, ORG_ID);

  // 選手4人を全チームの MEMBER（選手グリッドの母集団）
  for (const pid of playerIds) {
    await assignRole(pid, 4, basketTeamId, null);
    await assignRole(pid, 4, volleyTeamId, null);
    await assignRole(pid, 4, scoredTeamId, null);
  }
  console.log("Roles assigned (recorder/e2e-admin = ADMIN, players = MEMBER on all teams including scored)");

  // ------------------------------------------------------------
  // サマリー（spec の固定値）
  // ------------------------------------------------------------
  console.log("\n========================================");
  console.log("  F08.10 MULTISPORT E2E SEED - COMPLETE");
  console.log("========================================");
  console.log(`ORG_ID:            ${ORG_ID}   (slug=${ORG_SLUG})`);
  console.log(`BASKETBALL_TEAM_ID:${basketTeamId} (slug=f0810-basketball-team)`);
  console.log(`VOLLEYBALL_TEAM_ID:${volleyTeamId} (slug=f0810-volleyball-team)`);
  console.log(`SCORED_TEAM_ID:    ${scoredTeamId} (slug=f0810-scored-team)`);
  console.log(`RECORDER:          ${recorderId} (f0810-recorder@test.mannschaft.local)`);
  console.log(`PLAYERS:           [${playerIds.join(", ")}]`);
  console.log(`E2E_ADMIN:         ${E2E_ADMIN} (全チーム ADMIN 追加付与済み)`);
  console.log("========================================");

  await conn.end();
})().catch((e) => {
  console.error("FATAL:", e.message, e.sql || "");
  process.exit(1);
});
