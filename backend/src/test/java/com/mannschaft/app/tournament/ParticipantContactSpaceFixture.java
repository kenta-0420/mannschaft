package com.mannschaft.app.tournament;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * CMP-261118-0900 参加者向け連絡スペース一覧 — 受け入れテスト共通フィクスチャ（試練）。
 *
 * <p>設計書 {@code docs/features/F08.7.1_tournament_extensions/01_communication.md} §10.4 の前提
 * （大会 T・部門 D1/D2、チーム A/B/C、a1〜a4・b1・x・z、主催 O）を実 MySQL に別 TX で commit する。
 * 既存 {@link TournamentContactOracleFixture} は他 IT 3 本が共用するため改修せず、本クラスを新設した。
 * 呼び出し側 IT は {@code @Transactional(propagation = NOT_SUPPORTED)} とし、{@link #cleanup()} で所有行だけ消す
 * （投稿 API が作るスレッド・チャットメッセージも本クラスが消す）。</p>
 *
 * <h2>人物</h2>
 * <ul>
 *   <li>A: a1=ADMIN / a2=DEPUTY_ADMIN / a3=MEMBER / a4=SUPPORTER / aLeft=退会済み（left_at あり）</li>
 *   <li>B: b1=ADMIN。x=A・B 両方の一般会員。xa=A の代表かつ B の一般会員（AC-P19 後段）</li>
 *   <li>C（T 不参加）: c1。部外者 z</li>
 *   <li>主催組織 O: o1=ADMIN（チーム非所属）／ oMemberA=O の ADMIN かつ A の一般会員（AC-P33）</li>
 *   <li>別組織 F: fAdminA=F の ADMIN かつ A の一般会員（X9）。SYSTEM_ADMIN: sysAdmin（チーム非所属）</li>
 *   <li>アーカイブ済みチーム Arch の会員 archMember／ PROVISIONED チーム Prov の会員 provMember（AC-P11/P35）</li>
 * </ul>
 *
 * <h2>大会（主催はすべて O）</h2>
 * <ul>
 *   <li>T: D1（A ACTIVE）・D2（B ACTIVE）・D3（Arch・Prov ACTIVE）。T/D1/D2/D3 とも BULLETIN・CHAT 非公開。
 *       CHAT の ref_id は実チャットチャンネル（AC-P06 の送信用）</li>
 *   <li>tReg: A が REGISTERED（AC-P14 正常側）／ tWithdrawn: A が WITHDRAWN ／ tDisq: A が DISQUALIFIED</li>
 *   <li>tMix: dMixActive に A ACTIVE、dMixWithdrawn に A WITHDRAWN（AC-P15）</li>
 *   <li>tTwo: A が 2 部門とも ACTIVE（AC-P16・X2 の並び。スペースは並びと逆順に挿入する）</li>
 *   <li>tEmpty: A ACTIVE・スペース 0 件（AC-P18）／ tDeleted: 論理削除済み大会（AC-P12）</li>
 *   <li>tPub: 大会全体 BULLETIN のみ公開（AC-P21・P34）／ tPartDel: 大会全体 BULLETIN のみ論理削除（X10）</li>
 * </ul>
 */
public class ParticipantContactSpaceFixture {

    /** 確実に存在しない ID の基点（不在の対照群）。 */
    public static final long ABSENT_ID = Long.MAX_VALUE - 17;

    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;
    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final TeamRepository teams;
    private final ChatChannelRepository channels;
    private final String tag;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> orgIds = new ArrayList<>();
    private final List<Long> tournamentIds = new ArrayList<>();
    private final List<Long> divisionIds = new ArrayList<>();
    private final List<Long> channelIds = new ArrayList<>();
    private final Map<String, UUID> spaceIds = new HashMap<>();
    private final Map<String, Long> channelByScope = new HashMap<>();

    // --- スコープ ---
    public Long orgO;
    public Long orgF;
    public Long teamA;
    public Long teamB;
    public Long teamC;
    public Long teamArchived;
    public Long teamProvisioned;
    public String slugA;
    public String slugB;
    public String slugC;
    public String slugArchived;
    public String slugProvisioned;

    // --- 大会・部門 ---
    public Long t;
    public String tName;
    public Long d1;
    public String d1Name;
    public Long d2;
    public String d2Name;
    public Long d3;
    public Long tReg;
    public Long dReg;
    public Long tWithdrawn;
    public Long tDisq;
    public Long tMix;
    public Long dMixActive;
    public Long dMixWithdrawn;
    public Long tTwo;
    public Long dTwoFirst;
    public Long dTwoSecond;
    public Long tEmpty;
    public Long dEmpty;
    public Long tDeleted;
    public Long tPub;
    public Long dPub;
    public Long tPartDel;
    public Long dPartDel;

    // --- 人物 ---
    public Long a1;
    public Long a2;
    public Long a3;
    public Long a4;
    public Long aLeft;
    public Long b1;
    public Long x;
    public Long xa;
    public Long c1;
    public Long z;
    public Long o1;
    public Long oMemberA;
    public Long fAdminA;
    public Long sysAdmin;
    public Long archMember;
    public Long provMember;

    public ParticipantContactSpaceFixture(EntityManager em, JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                          UserRepository users, OrganizationRepository organizations,
                                          TeamRepository teams, ChatChannelRepository channels, String tag) {
        this.em = em;
        this.jdbc = jdbc;
        this.txManager = txManager;
        this.users = users;
        this.organizations = organizations;
        this.teams = teams;
        this.channels = channels;
        this.tag = tag;
    }

    /** 全データを別 TX で commit する。 */
    public void setUp() {
        tx(() -> {
            a1 = user("A代表");
            a2 = user("A副代表");
            a3 = user("A一般");
            a4 = user("Aサポーター");
            aLeft = user("A退会済み");
            b1 = user("B代表");
            x = user("AB一般");
            xa = user("A代表B一般");
            c1 = user("C一般");
            z = user("部外者");
            o1 = user("主催管理者");
            oMemberA = user("主催管理者兼A一般");
            fAdminA = user("別組織管理者兼A一般");
            sysAdmin = user("システム管理者");
            archMember = user("アーカイブ済みチーム会員");
            provMember = user("PROVISIONEDチーム会員");

            orgO = organization(tag + " 主催組織O");
            orgF = organization(tag + " 別組織F");
            slugA = slug();
            teamA = team(slugA, tag + " チームA");
            slugB = slug();
            teamB = team(slugB, tag + " チームB");
            slugC = slug();
            teamC = team(slugC, tag + " チームC");
            slugArchived = slug();
            teamArchived = team(slugArchived, tag + " アーカイブ済みチーム");
            slugProvisioned = slug();
            teamProvisioned = team(slugProvisioned, tag + " PROVISIONEDチーム");

            // A
            teamMember(a1, teamA, RoleKind.MEMBER, "ADMIN");
            teamMember(a2, teamA, RoleKind.MEMBER, "DEPUTY_ADMIN");
            teamMember(a3, teamA, RoleKind.MEMBER, null);
            teamMember(a4, teamA, RoleKind.SUPPORTER, null);
            teamMember(aLeft, teamA, RoleKind.MEMBER, null);
            teamMember(x, teamA, RoleKind.MEMBER, null);
            teamMember(xa, teamA, RoleKind.MEMBER, "ADMIN");
            teamMember(oMemberA, teamA, RoleKind.MEMBER, null);
            teamMember(fAdminA, teamA, RoleKind.MEMBER, null);
            // B
            teamMember(b1, teamB, RoleKind.MEMBER, "ADMIN");
            teamMember(x, teamB, RoleKind.MEMBER, null);
            teamMember(xa, teamB, RoleKind.MEMBER, null);
            // C・アーカイブ済み・PROVISIONED
            teamMember(c1, teamC, RoleKind.MEMBER, null);
            teamMember(archMember, teamArchived, RoleKind.MEMBER, "ADMIN");
            teamMember(provMember, teamProvisioned, RoleKind.MEMBER, "ADMIN");
            // 組織・SYSTEM_ADMIN
            MembershipTestHelper.insertMembership(em, o1, ScopeType.ORGANIZATION, orgO, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, o1, "ADMIN", null, orgO);
            MembershipTestHelper.insertMembership(em, oMemberA, ScopeType.ORGANIZATION, orgO, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, oMemberA, "ADMIN", null, orgO);
            MembershipTestHelper.insertMembership(em, fAdminA, ScopeType.ORGANIZATION, orgF, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, fAdminA, "ADMIN", null, orgF);
            MembershipTestHelper.insertUserRole(em, sysAdmin, "SYSTEM_ADMIN", null, null);

            String s = UUID.randomUUID().toString().substring(0, 8);

            // T（本命）
            tName = tag + " T " + s;
            t = tournament(orgO, tName);
            d1Name = tag + " D1 " + s;
            d1 = division(t, d1Name);
            d2Name = tag + " D2 " + s;
            d2 = division(t, d2Name);
            d3 = division(t, tag + " D3 " + s);
            participant(d1, teamA, "ACTIVE");
            participant(d2, teamB, "ACTIVE");
            participant(d3, teamArchived, "ACTIVE");
            participant(d3, teamProvisioned, "ACTIVE");
            spacesWithChannel(ContactSpaceScopeType.TOURNAMENT, t);
            spacesWithChannel(ContactSpaceScopeType.TOURNAMENT_DIVISION, d1);
            spacesWithChannel(ContactSpaceScopeType.TOURNAMENT_DIVISION, d2);
            spacesWithChannel(ContactSpaceScopeType.TOURNAMENT_DIVISION, d3);

            // tReg: A が REGISTERED
            tReg = tournament(orgO, tag + " TReg " + s);
            dReg = division(tReg, tag + " DReg " + s);
            participant(dReg, teamA, "REGISTERED");
            privateSpaces(ContactSpaceScopeType.TOURNAMENT, tReg);
            privateSpaces(ContactSpaceScopeType.TOURNAMENT_DIVISION, dReg);

            // tWithdrawn / tDisq: 参加外
            tWithdrawn = tournament(orgO, tag + " TWd " + s);
            Long dWd = division(tWithdrawn, tag + " DWd " + s);
            participant(dWd, teamA, "WITHDRAWN");
            privateSpaces(ContactSpaceScopeType.TOURNAMENT, tWithdrawn);
            privateSpaces(ContactSpaceScopeType.TOURNAMENT_DIVISION, dWd);
            tDisq = tournament(orgO, tag + " TDq " + s);
            Long dDq = division(tDisq, tag + " DDq " + s);
            participant(dDq, teamA, "DISQUALIFIED");
            privateSpaces(ContactSpaceScopeType.TOURNAMENT, tDisq);
            privateSpaces(ContactSpaceScopeType.TOURNAMENT_DIVISION, dDq);

            // tMix: 片方 ACTIVE・片方 WITHDRAWN
            tMix = tournament(orgO, tag + " TMix " + s);
            dMixActive = division(tMix, tag + " DMixA " + s);
            dMixWithdrawn = division(tMix, tag + " DMixW " + s);
            participant(dMixActive, teamA, "ACTIVE");
            participant(dMixWithdrawn, teamA, "WITHDRAWN");
            privateSpaces(ContactSpaceScopeType.TOURNAMENT, tMix);
            privateSpaces(ContactSpaceScopeType.TOURNAMENT_DIVISION, dMixActive);
            privateSpaces(ContactSpaceScopeType.TOURNAMENT_DIVISION, dMixWithdrawn);

            // tTwo: 2 部門とも ACTIVE。並び（X2）を挿入順に頼れないよう、後ろの部門・CHAT から逆順に挿入する。
            tTwo = tournament(orgO, tag + " TTwo " + s);
            dTwoFirst = division(tTwo, tag + " DTwo1 " + s);
            dTwoSecond = division(tTwo, tag + " DTwo2 " + s);
            participant(dTwoSecond, teamA, "ACTIVE");
            participant(dTwoFirst, teamA, "ACTIVE");
            contactSpace(ContactSpaceScopeType.TOURNAMENT_DIVISION, dTwoSecond, ContactSpaceKind.CHAT, false, false, 0L);
            contactSpace(ContactSpaceScopeType.TOURNAMENT_DIVISION, dTwoSecond, ContactSpaceKind.BULLETIN, false, false, 0L);
            contactSpace(ContactSpaceScopeType.TOURNAMENT_DIVISION, dTwoFirst, ContactSpaceKind.CHAT, false, false, 0L);
            contactSpace(ContactSpaceScopeType.TOURNAMENT_DIVISION, dTwoFirst, ContactSpaceKind.BULLETIN, false, false, 0L);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, tTwo, ContactSpaceKind.CHAT, false, false, 0L);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, tTwo, ContactSpaceKind.BULLETIN, false, false, 0L);

            // tEmpty: スペース 0 件
            tEmpty = tournament(orgO, tag + " TEmpty " + s);
            dEmpty = division(tEmpty, tag + " DEmpty " + s);
            participant(dEmpty, teamA, "ACTIVE");

            // tDeleted: 論理削除済み大会
            tDeleted = tournament(orgO, tag + " TDel " + s);
            Long dDel = division(tDeleted, tag + " DDel " + s);
            participant(dDel, teamA, "ACTIVE");
            privateSpaces(ContactSpaceScopeType.TOURNAMENT, tDeleted);
            privateSpaces(ContactSpaceScopeType.TOURNAMENT_DIVISION, dDel);
            em.createNativeQuery("UPDATE tournaments SET deleted_at = NOW() WHERE id = :id")
                    .setParameter("id", tDeleted).executeUpdate();

            // tPub: 大会全体 BULLETIN のみ公開
            tPub = tournament(orgO, tag + " TPub " + s);
            dPub = division(tPub, tag + " DPub " + s);
            participant(dPub, teamA, "ACTIVE");
            contactSpace(ContactSpaceScopeType.TOURNAMENT, tPub, ContactSpaceKind.BULLETIN, true, false, 0L);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, tPub, ContactSpaceKind.CHAT, false, false, 0L);
            privateSpaces(ContactSpaceScopeType.TOURNAMENT_DIVISION, dPub);

            // tPartDel: 大会全体 BULLETIN のみ論理削除
            tPartDel = tournament(orgO, tag + " TPartDel " + s);
            dPartDel = division(tPartDel, tag + " DPartDel " + s);
            participant(dPartDel, teamA, "ACTIVE");
            contactSpace(ContactSpaceScopeType.TOURNAMENT, tPartDel, ContactSpaceKind.BULLETIN, false, true, 0L);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, tPartDel, ContactSpaceKind.CHAT, false, false, 0L);
            privateSpaces(ContactSpaceScopeType.TOURNAMENT_DIVISION, dPartDel);

            // アーカイブ済み・PROVISIONED・退会済み
            em.createNativeQuery("UPDATE teams SET archived_at = NOW() WHERE id = :id")
                    .setParameter("id", teamArchived).executeUpdate();
            em.createNativeQuery("UPDATE teams SET lifecycle_status = 'PROVISIONED' WHERE id = :id")
                    .setParameter("id", teamProvisioned).executeUpdate();
            em.createNativeQuery("UPDATE memberships SET left_at = NOW() "
                            + "WHERE user_id = :uid AND scope_type = 'TEAM' AND scope_id = :tid")
                    .setParameter("uid", aLeft).setParameter("tid", teamA).executeUpdate();
            return null;
        });
    }

    /** 本フィクスチャが作った行（投稿 API が作った掲示板・チャットの子行を含む）だけを消す。 */
    public void cleanup() {
        String threads = "(SELECT q.id FROM (SELECT id FROM bulletin_threads WHERE " + scopeCondition() + ") q)";
        String replies = "(SELECT q.id FROM (SELECT id FROM bulletin_replies WHERE thread_id IN " + threads + ") q)";
        jdbc.update("DELETE FROM bulletin_reactions WHERE (target_type = 'THREAD' AND target_id IN " + threads
                + ") OR (target_type = 'REPLY' AND target_id IN " + replies + ")");
        jdbc.update("DELETE FROM bulletin_attachments WHERE (target_type = 'THREAD' AND target_id IN " + threads
                + ") OR (target_type = 'REPLY' AND target_id IN " + replies + ")");
        jdbc.update("DELETE FROM bulletin_read_status WHERE thread_id IN " + threads);
        jdbc.update("DELETE FROM bulletin_replies WHERE thread_id IN " + threads);
        jdbc.update("DELETE FROM bulletin_threads WHERE " + scopeCondition());
        if (!channelIds.isEmpty()) {
            String ch = in(channelIds);
            String msgs = "(SELECT q.id FROM (SELECT id FROM chat_messages WHERE channel_id IN " + ch + ") q)";
            jdbc.update("DELETE FROM chat_message_reactions WHERE message_id IN " + msgs);
            jdbc.update("DELETE FROM chat_message_bookmarks WHERE message_id IN " + msgs);
            jdbc.update("DELETE FROM chat_message_attachments WHERE message_id IN " + msgs);
            jdbc.update("DELETE FROM chat_messages WHERE channel_id IN " + ch);
            jdbc.update("DELETE FROM chat_channel_members WHERE channel_id IN " + ch);
            jdbc.update("DELETE FROM chat_channels WHERE id IN " + ch);
        }
        for (Long id : tournamentIds) {
            jdbc.update("DELETE FROM tournament_contact_space WHERE scope_type = 'TOURNAMENT' AND scope_id = ?", id);
        }
        for (Long id : divisionIds) {
            jdbc.update("DELETE FROM tournament_contact_space WHERE scope_type = 'TOURNAMENT_DIVISION' AND scope_id = ?", id);
            jdbc.update("DELETE FROM tournament_participants WHERE division_id = ?", id);
            jdbc.update("DELETE FROM tournament_divisions WHERE id = ?", id);
        }
        for (Long id : tournamentIds) {
            jdbc.update("DELETE FROM tournaments WHERE id = ?", id);
        }
        for (Long id : userIds) {
            jdbc.update("DELETE FROM audit_logs WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
            jdbc.update("DELETE FROM memberships WHERE user_id = ?", id);
        }
        for (Long id : teamIds) {
            jdbc.update("DELETE FROM teams WHERE id = ?", id);
        }
        for (Long id : orgIds) {
            jdbc.update("DELETE FROM organizations WHERE id = ?", id);
        }
        for (Long id : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    /** 連絡スペース ID。 */
    public UUID spaceId(ContactSpaceScopeType scopeType, Long scopeId, ContactSpaceKind kind) {
        UUID id = spaceIds.get(key(scopeType, scopeId, kind));
        if (id == null) {
            throw new IllegalStateException("fixture にスペースが無い: " + key(scopeType, scopeId, kind));
        }
        return id;
    }

    /** CHAT スペースの ref_id（実チャットチャンネル ID）。T・D1〜D3 のみ。 */
    public Long channelId(ContactSpaceScopeType scopeType, Long scopeId) {
        Long id = channelByScope.get(scopeType.name() + ":" + scopeId);
        if (id == null) {
            throw new IllegalStateException("fixture にチャンネルが無い: " + scopeType + ":" + scopeId);
        }
        return id;
    }

    public <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    // ------------------------------------------------------------------------

    private static String key(ContactSpaceScopeType scopeType, Long scopeId, ContactSpaceKind kind) {
        return scopeType.name() + ":" + scopeId + ":" + kind.name();
    }

    private static String in(List<Long> ids) {
        return ids.isEmpty() ? "(NULL)" : ids.stream().map(String::valueOf).collect(Collectors.joining(",", "(", ")"));
    }

    private String scopeCondition() {
        return "((scope_type = 'TOURNAMENT' AND scope_id IN " + in(tournamentIds)
                + ") OR (scope_type = 'TOURNAMENT_DIVISION' AND scope_id IN " + in(divisionIds) + "))";
    }

    private static String slug() {
        return "cmp0900-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Long user(String label) {
        Long id = users.save(UserEntity.builder().email("cmp0900-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName(label).displayName(label).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        userIds.add(id);
        return id;
    }

    private Long organization(String name) {
        Long id = organizations.save(OrganizationEntity.builder()
                .slug(slug()).name(name)
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build()).getId();
        orgIds.add(id);
        return id;
    }

    private Long team(String slug, String name) {
        Long id = teams.save(TeamEntity.builder().slug(slug)
                .name(name).visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build()).getId();
        teamIds.add(id);
        return id;
    }

    /** チーム所属（memberships）と、必要なら権限ロール（user_roles の ADMIN/DEPUTY_ADMIN）。 */
    private void teamMember(Long userId, Long teamId, RoleKind kind, String adminRoleOrNull) {
        MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, kind);
        if (adminRoleOrNull != null) {
            MembershipTestHelper.insertUserRole(em, userId, adminRoleOrNull, teamId, null);
        }
    }

    /** 大会を seed する（列は {@link TournamentContactOracleFixture} と同一）。 */
    private Long tournament(Long orgId, String name) {
        em.createNativeQuery(
                        "INSERT INTO tournaments (organization_id, name, format, sport, "
                                + "win_points, draw_points, loss_points, has_draw, has_sets, "
                                + "has_extra_time, has_penalties, score_unit_label, league_round_type, "
                                + "knockout_legs, visibility, status, version, created_by, created_at, updated_at) "
                                + "VALUES (:orgId, :name, 'LEAGUE', 'SOCCER', "
                                + "3, 1, 0, 1, 0, "
                                + "0, 0, '点', 'SINGLE', "
                                + "1, 'PUBLIC', 'OPEN', 0, :createdBy, NOW(), NOW())")
                .setParameter("orgId", orgId)
                .setParameter("name", name)
                .setParameter("createdBy", o1)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM tournaments WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
        tournamentIds.add(id);
        return id;
    }

    private Long division(Long tid, String name) {
        em.createNativeQuery(
                        "INSERT INTO tournament_divisions (tournament_id, name, level, promotion_slots, "
                                + "relegation_slots, playoff_promotion_slots, sort_order, created_at, updated_at) "
                                + "VALUES (:tid, :name, 1, 0, 0, 0, 0, NOW(), NOW())")
                .setParameter("tid", tid)
                .setParameter("name", name)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM tournament_divisions WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
        divisionIds.add(id);
        return id;
    }

    private void participant(Long divId, Long teamId, String status) {
        em.createNativeQuery(
                        "INSERT INTO tournament_participants (division_id, team_id, status, joined_at) "
                                + "VALUES (:divId, :teamId, :status, NOW())")
                .setParameter("divId", divId)
                .setParameter("teamId", teamId)
                .setParameter("status", status)
                .executeUpdate();
    }

    /** BULLETIN・CHAT とも非公開。CHAT は実チャットチャンネルを払い出して ref_id に入れる。 */
    private void spacesWithChannel(ContactSpaceScopeType scopeType, Long scopeId) {
        ChannelType channelType = scopeType == ContactSpaceScopeType.TOURNAMENT
                ? ChannelType.TOURNAMENT_CHAT : ChannelType.TOURNAMENT_DIVISION_CHAT;
        Long channelId = channels.save(ChatChannelEntity.builder()
                .channelType(channelType)
                .name(tag + " 連絡 " + scopeType + " " + scopeId)
                .isPrivate(false)
                .sourceType(scopeType.name())
                .sourceId(scopeId)
                .createdBy(o1)
                .build()).getId();
        channelIds.add(channelId);
        channelByScope.put(scopeType.name() + ":" + scopeId, channelId);
        contactSpace(scopeType, scopeId, ContactSpaceKind.BULLETIN, false, false, 0L);
        contactSpace(scopeType, scopeId, ContactSpaceKind.CHAT, false, false, channelId);
    }

    private void privateSpaces(ContactSpaceScopeType scopeType, Long scopeId) {
        contactSpace(scopeType, scopeId, ContactSpaceKind.BULLETIN, false, false, 0L);
        contactSpace(scopeType, scopeId, ContactSpaceKind.CHAT, false, false, 0L);
    }

    private void contactSpace(ContactSpaceScopeType scopeType, Long scopeId, ContactSpaceKind kind,
                              boolean isPublic, boolean deleted, Long refId) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO tournament_contact_space (id, scope_type, scope_id, space_kind, ref_id, is_public, "
                                + "created_at, updated_at, deleted_at) "
                                + "VALUES (UNHEX(REPLACE(:id, '-', '')), :st, :sid, :kind, :ref, :pub, NOW(), NOW(), "
                                + (deleted ? "NOW()" : "NULL") + ")")
                .setParameter("id", id.toString())
                .setParameter("st", scopeType.name())
                .setParameter("sid", scopeId)
                .setParameter("kind", kind.name())
                .setParameter("ref", refId)
                .setParameter("pub", isPublic)
                .executeUpdate();
        spaceIds.put(key(scopeType, scopeId, kind), id);
    }
}
