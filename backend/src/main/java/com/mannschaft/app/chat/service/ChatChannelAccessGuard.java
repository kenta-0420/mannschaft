package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.event.service.EventScopeAccessGuard;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.service.TournamentContactAccessService;
import com.mannschaft.app.village.service.PostingIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * チャットドメインの認可判定を一元化するガード。
 *
 * <p>チャンネル・メンバー・メッセージ・添付の各操作について「誰がこの操作をしてよいか」の判定を
 * 本クラスに集約する。判定はすべて<b>対象エンティティを取得したうえで、そのエンティティ由来のスコープ</b>
 * （{@code chat_channels.team_id} / {@code organization_id} / {@code chat_channel_members} の行）に基づく。
 * リクエストが申告したスコープ識別子は判定材料に用いない。</p>
 *
 * <p>本クラスはチャットのリポジトリと、各ドメインの正準の認可判定（{@link AccessControlService}・
 * {@link TournamentContactAccessService}・{@link EventScopeAccessGuard}・{@link PostingIdentityService}）
 * のみに依存し、チャットの業務サービスには依存しない。循環依存を作らずに全経路が同一の判定を通ることを
 * 保証するための構成である。</p>
 *
 * <h2>トランザクションの外で呼ぶ（CMP-261105-1507）</h2>
 * <p>本クラスは {@code @Transactional} を持たない。チャンネル ID・メッセージ ID を指定する EP の認可は
 * {@link ChatChannelFacade} / {@link ChatMessageFacade}（いずれも tx なし）が本クラスで済ませてから
 * tx 本体（{@link ChatChannelService} / {@link ChatMemberService} / {@link ChatMessageService}）を呼ぶ。
 * 他ドメイン（membership / role / tournament / event / village）の読み取りをチャットの書き込み
 * トランザクションに巻き込まないためである（CLAUDE.md DB 設計の原則 #5・番人 D-3T）。</p>
 *
 * <p>エンティティを受け取る判定はパッケージ内からだけ呼ぶ（Service API にエンティティを公開しない。
 * 番人 {@code ServiceApiEntityBoundaryArchTest}）。パッケージ外（Controller）からは ID を受け取る
 * {@link #requireChannelPostAccess(Long, Long)} を使う。</p>
 *
 * <h2>存在秘匿（docs/security/01_authorization_baseline.md §3.3.2）</h2>
 * <p>チャンネル ID・メッセージ ID を指定する EP で、<b>そのチャンネルの存在を知り得ない利用者</b>への拒否は、
 * 不在 ID への応答（{@link ChatErrorCode#CHANNEL_NOT_FOUND} / {@link ChatErrorCode#MESSAGE_NOT_FOUND}）と
 * status・{@code error.code}・{@code error.message} まで完全に一致させる。存在を知り得る利用者
 * （{@link #isAwareOfChannel}）への拒否だけを 403 とする。判定は<b>拒否経路でのみ</b>追加クエリを撃ち、
 * 許可経路のクエリ回数は変えない（{@link com.mannschaft.app.common.ScopeConcealingAccessGate} と同じ流儀）。</p>
 */
@Service
@RequiredArgsConstructor
public class ChatChannelAccessGuard {

    private final ChatChannelRepository channelRepository;
    private final ChatChannelMemberRepository memberRepository;
    private final AccessControlService accessControlService;
    /** F08.7.1 連絡機能: 大会/ディビジョンチャットの閲覧・投稿認可を委譲する（クロスドメイン・原則1）。 */
    private final TournamentContactAccessService tournamentContactAccessService;
    /** 裏目付A: {@code EVENT_CHAT} の閲覧・投稿認可を event ドメインのアクセスモデルへ委譲する。 */
    private final EventScopeAccessGuard eventScopeAccessGuard;
    /** 裏目付A: {@code VILLAGE_LOBBY} の閲覧・投稿認可を village ドメインの正準判定へ委譲する。 */
    private final PostingIdentityService postingIdentityService;

    // ═════════════════════════════════════════════════════════════════════
    // パッケージ外（Controller）向け: ID を受け取る判定
    // ═════════════════════════════════════════════════════════════════════

    /**
     * チャンネルへの投稿権限を、本文投稿と同一の判定で保証する（添付のアップロード URL 発行など）。
     *
     * @param channelId 対象チャンネル ID
     * @param userId    呼出ユーザー ID
     * @throws BusinessException チャンネルが不在、または存在を知り得ない利用者なら {@link ChatErrorCode#CHANNEL_NOT_FOUND}、
     *                           閲覧はできるが投稿できない利用者なら 403
     */
    public void requireChannelPostAccess(Long channelId, Long userId) {
        ChatChannelEntity channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHANNEL_NOT_FOUND));
        checkChannelPostAccess(channel, userId, ChatErrorCode.CHANNEL_NOT_FOUND);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 閲覧・投稿（種別ごとの正準判定へ委譲）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 取得済みチャンネルに対する閲覧認可（存在秘匿つき）。
     *
     * <p>種別ごとの意味論（{@link ChannelType#isMembershipGated()} を単一の出所とする）:</p>
     * <ul>
     *   <li><b>{@code TOURNAMENT_CHAT} / {@code TOURNAMENT_DIVISION_CHAT}</b> —
     *       {@link TournamentContactAccessService#checkView} に委譲（F08.7.1 §4.1）。</li>
     *   <li><b>メンバーシップ管理種別</b> — {@code chat_channel_members} にメンバー行が存在することを要求する
     *       （{@code TEAM_PUBLIC} も対象。「公開」は自分で参加できる、の意で、未参加のまま本文を読めることは意味しない）。</li>
     *   <li><b>{@code VILLAGE_LOBBY} / {@code EVENT_CHAT}</b> — village / event ドメイン側のアクセスモデルで認可する。</li>
     * </ul>
     *
     * <p><b>正準の根拠</b>: {@code ChatMessageVisibilityResolver} の危険注記が
     * 「本 Resolver の canView をチャット本文可視の単独ゲートに使用してはならない。本文読取・WS 購読の認可は
     * {@code ChatChannelMemberRepository} 直参照を正とする」と明記しており、本実装はこれに従う。</p>
     *
     * @param channel      対象チャンネル（生存確認済み）
     * @param userId       閲覧ユーザー ID
     * @param notFoundCode 当該 EP の不在コード（チャンネル ID 指定なら CHANNEL_NOT_FOUND、
     *                     メッセージ ID 指定なら MESSAGE_NOT_FOUND）。存在を知り得ない利用者への拒否はこれと同一にする
     */
    void checkChannelViewAccess(ChatChannelEntity channel, Long userId, ErrorCode notFoundCode) {
        ContactSpaceScopeType scope = tournamentScopeOf(channel);
        if (scope != null) {
            if (!canViewTournamentChannel(scope, channel, userId)) {
                throw new BusinessException(notFoundCode);
            }
            return;
        }
        requireViewableByChannelType(channel, userId, notFoundCode);
    }

    /**
     * メッセージ ID 指定系の閲覧認可。拒否はすべて不在メッセージ（{@link ChatErrorCode#MESSAGE_NOT_FOUND}）
     * と同一応答にする（子リソースの不在コード。親チャンネルのコードを返すと実在が漏れる）。
     */
    void checkMessageViewAccess(ChatMessageEntity message, Long userId) {
        checkChannelViewAccess(channelOfMessage(message), userId, ChatErrorCode.MESSAGE_NOT_FOUND);
    }

    /**
     * 投稿・モデレーション認可（存在秘匿つき）。閲覧もできない利用者への拒否は {@code notFoundCode} と同一にし、
     * 閲覧はできるが投稿できない利用者（大会チャットの一般参加者等）への拒否だけを 403 にする。
     */
    void checkChannelPostAccess(ChatChannelEntity channel, Long userId, ErrorCode notFoundCode) {
        ContactSpaceScopeType scope = tournamentScopeOf(channel);
        if (scope != null) {
            try {
                tournamentContactAccessService.checkPost(scope, channel.getSourceId(), userId);
            } catch (BusinessException e) {
                if (!canViewTournamentChannel(scope, channel, userId)) {
                    throw new BusinessException(notFoundCode);
                }
                throw e;
            }
            return;
        }
        requireViewableByChannelType(channel, userId, notFoundCode);
    }

    /**
     * メッセージが属するチャンネルを取得する。チャンネルが不在（論理削除済み）なら、メッセージも不在として扱う。
     */
    ChatChannelEntity channelOfMessage(ChatMessageEntity message) {
        return channelRepository.findById(message.getChannelId())
                .orElseThrow(() -> new BusinessException(ChatErrorCode.MESSAGE_NOT_FOUND));
    }

    /**
     * 大会以外の種別の閲覧・投稿認可。非該当はすべて存在を知り得ない者として {@code notFoundCode} にする
     * （メンバーシップ管理種別だけは、存在を知り得る利用者に 403 を返す）。未知種別は fail-closed。
     */
    private void requireViewableByChannelType(ChatChannelEntity channel, Long userId, ErrorCode notFoundCode) {
        ChannelType type = channel.getChannelType();
        if (type.isMembershipGated()) {
            requireMembershipRowOrConceal(channel, userId, notFoundCode);
            return;
        }
        boolean allowed = switch (type) {
            case VILLAGE_LOBBY -> channel.getVillageId() != null
                    && userId != null
                    && postingIdentityService.isUserVillageMember(channel.getVillageId(), userId);
            case EVENT_CHAT -> eventScopeAccessGuard.isEventScopeMember(userId, channel.getSourceId());
            // TOURNAMENT_* は前段で委譲済みのため未到達。未知種別は fail-closed で拒否する。
            default -> false;
        };
        if (!allowed) {
            // 村ロビー・イベントチャットの非該当者はチャンネルの存在を知り得ない。不在と同一応答にする。
            throw new BusinessException(notFoundCode);
        }
    }

    /**
     * 大会連絡チャットを閲覧できるか。大会ドメインの正準判定（{@link TournamentContactAccessService#checkView}）
     * の拒否（スペース不在・閲覧権限なし）を偽として返す。それ以外の例外はそのまま伝播させる。
     */
    private boolean canViewTournamentChannel(ContactSpaceScopeType scope, ChatChannelEntity channel, Long userId) {
        try {
            tournamentContactAccessService.checkView(scope, channel.getSourceId(), ContactSpaceKind.CHAT, userId);
            return true;
        } catch (BusinessException e) {
            if (e.getErrorCode() == TournamentErrorCode.CONTACT_SPACE_VIEW_FORBIDDEN
                    || e.getErrorCode() == TournamentErrorCode.CONTACT_SPACE_NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    /** チャンネルが大会連絡チャットなら対応する連絡スペーススコープを返す。そうでなければ null。 */
    private static ContactSpaceScopeType tournamentScopeOf(ChatChannelEntity channel) {
        return switch (channel.getChannelType()) {
            case TOURNAMENT_CHAT -> ContactSpaceScopeType.TOURNAMENT;
            case TOURNAMENT_DIVISION_CHAT -> ContactSpaceScopeType.TOURNAMENT_DIVISION;
            default -> null;
        };
    }

    // ═════════════════════════════════════════════════════════════════════
    // メンバーシップ・存在秘匿
    // ═════════════════════════════════════════════════════════════════════

    /**
     * メンバーシップ管理種別（{@link ChannelType#isMembershipGated()}）のチャンネルについて、
     * 呼出ユーザーが当該チャンネルの現役メンバーであることを保証する。
     *
     * <p>村ロビー・イベント・大会チャットは {@code chat_channel_members} 行を持たない横断スペースであり、
     * それぞれのドメイン側の正準判定で認可される。本メソッドはその区別を
     * {@link ChannelType#isMembershipGated()} に委ねる。</p>
     *
     * @throws BusinessException 存在を知り得ない利用者なら {@link ChatErrorCode#CHANNEL_NOT_FOUND}（不在と同一）、
     *                           知り得るが未参加なら {@link ChatErrorCode#CHANNEL_ACCESS_DENIED}
     */
    void requireChannelMembership(ChatChannelEntity channel, Long userId) {
        if (!channel.getChannelType().isMembershipGated()) {
            return;
        }
        requireMembershipRowOrConceal(channel, userId, ChatErrorCode.CHANNEL_NOT_FOUND);
    }

    /**
     * 種別を問わず、呼出ユーザーが当該チャンネルの現役メンバー（{@code chat_channel_members} 行あり）で
     * あることを保証する。拒否は存在秘匿つきで作り分ける。
     *
     * @param channel      対象チャンネル（生存確認済み）
     * @param userId       呼出ユーザー ID
     * @param notFoundCode 当該 EP の不在コード
     * @throws BusinessException 存在を知り得ない利用者なら {@code notFoundCode}、
     *                           知り得るが未参加なら {@link ChatErrorCode#CHANNEL_ACCESS_DENIED}
     */
    void requireMembershipRowOrConceal(ChatChannelEntity channel, Long userId, ErrorCode notFoundCode) {
        if (userId != null && memberRepository.existsByChannelIdAndUserId(channel.getId(), userId)) {
            return;
        }
        throw concealOrForbid(channel, userId, notFoundCode, ChatErrorCode.CHANNEL_ACCESS_DENIED);
    }

    /**
     * 拒否が確定した後に、越境（存在を知り得ない）か権限不足（存在を知り得る）かを作り分けた例外を返す。
     *
     * <p>拒否経路でのみ呼ぶこと（{@link #isAwareOfChannel} は追加クエリを撃つ）。</p>
     *
     * @param notFoundCode  不在時に投げるコードそのもの（専用コードを新設しない）
     * @param forbiddenCode 存在を知り得る利用者に返す 403 コード
     * @return 投げるべき例外
     */
    BusinessException concealOrForbid(ChatChannelEntity channel, Long userId,
                                      ErrorCode notFoundCode, ErrorCode forbiddenCode) {
        if (isAwareOfChannel(channel, userId)) {
            return new BusinessException(forbiddenCode);
        }
        return new BusinessException(notFoundCode);
    }

    /**
     * 呼出ユーザーがチャンネルの<b>存在を知り得る</b>かを判定する（F04.2 §5「チャンネルメンバーシップの可視性」）。
     *
     * <ul>
     *   <li>当該チャンネルのメンバー（{@code chat_channel_members} 行あり）</li>
     *   <li>SYSTEM_ADMIN（全チャンネルのモデレーション権限を持つ）</li>
     *   <li>スコープチャンネルのスコープ（チーム/組織）の ADMIN / DEPUTY_ADMIN（スコープ内の全チャンネルを管理する）</li>
     *   <li>公開スコープチャンネル（{@code TEAM_PUBLIC} / {@code ORG_PUBLIC} かつ非 private）のスコープ所属者
     *       （参加を促す UI のため非メンバーにも存在が見える）</li>
     * </ul>
     * <p>上記以外（他人の DM・グループ DM、非公開チャンネルの非メンバー、他スコープの利用者、
     * 村ロビー・イベント・大会チャットの非該当者）は存在を知り得ない。</p>
     */
    boolean isAwareOfChannel(ChatChannelEntity channel, Long userId) {
        if (userId == null) {
            return false;
        }
        if (memberRepository.existsByChannelIdAndUserId(channel.getId(), userId)) {
            return true;
        }
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        if (!channel.getChannelType().isScopeChannel()) {
            return false;
        }
        if (isScopeAdmin(channel, userId)) {
            return true;
        }
        boolean publicChannel = channel.getChannelType().isSelfJoinableScopeChannel()
                && !Boolean.TRUE.equals(channel.getIsPrivate());
        return publicChannel && isScopeMember(channel, userId);
    }

    /** チャンネルのスコープ（チーム優先・無ければ組織）の所属者かを判定する。 */
    private boolean isScopeMember(ChatChannelEntity channel, Long userId) {
        if (channel.getTeamId() != null) {
            return accessControlService.isMember(userId, channel.getTeamId(), "TEAM");
        }
        if (channel.getOrganizationId() != null) {
            return accessControlService.isMember(userId, channel.getOrganizationId(), "ORGANIZATION");
        }
        return false;
    }

    /**
     * 自己参加（{@code POST /channels/{id}/join}）の可否を、存在秘匿つきで保証する。
     *
     * <p>メンバーシップでアクセスを判定する種別のうち、自己参加を許すのは公開スコープチャンネル
     * （{@code TEAM_PUBLIC} / {@code ORG_PUBLIC} かつ非 private）だけであり、さらにそのスコープ（チーム/組織）の
     * 所属者であることを要求する。村ロビー・イベント・大会チャットは {@code chat_channel_members} で判定しない
     * 種別のため従来どおり素通しする。</p>
     *
     * <p>ここでの所属判定はロックを取らない事前判定である。離脱処理との競合を直列化する行ロックつきの
     * 再確認は tx 本体（{@link ChatMemberService#joinChannel}）が行う。</p>
     *
     * @throws BusinessException 存在を知り得ない利用者なら {@link ChatErrorCode#CHANNEL_NOT_FOUND}（不在と同一）、
     *                           知り得るが招待制なら {@link ChatErrorCode#CHANNEL_ACCESS_DENIED}、
     *                           知り得るがスコープ非所属（SYSTEM_ADMIN 等）なら {@link CommonErrorCode#COMMON_002}
     */
    void requireSelfJoinable(ChatChannelEntity channel, Long userId) {
        ChannelType channelType = channel.getChannelType();
        if (!channelType.isMembershipGated()) {
            return;
        }
        // DM / GROUP_DM / 非公開チャンネルは招待制。自己参加は認めない。
        if (!channelType.isSelfJoinableScopeChannel() || Boolean.TRUE.equals(channel.getIsPrivate())) {
            throw concealOrForbid(channel, userId, ChatErrorCode.CHANNEL_NOT_FOUND, ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }
        // 公開種別なのにスコープが無いチャンネルは所属を検証できない。安全側に倒して拒否する。
        if (channel.getTeamId() == null && channel.getOrganizationId() == null) {
            throw concealOrForbid(channel, userId, ChatErrorCode.CHANNEL_NOT_FOUND, ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }
        // 公開チャンネルは「スコープ内に対する公開」。当該チーム/組織の所属者であることを要求する。
        if (userId == null || !isScopeMember(channel, userId)) {
            throw concealOrForbid(channel, userId, ChatErrorCode.CHANNEL_NOT_FOUND, CommonErrorCode.COMMON_002);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 管理操作
    // ═════════════════════════════════════════════════════════════════════

    /**
     * チャンネル内の管理操作（メンバー追加・除外・ロール変更・アイコン変更）について、
     * 呼出ユーザーが当該チャンネルの OWNER または ADMIN であることを保証する。
     *
     * @param deniedCode 存在を知り得る利用者への拒否コード（経路ごとの API 契約に合わせる）
     * @throws BusinessException 存在を知り得ない利用者なら {@link ChatErrorCode#CHANNEL_NOT_FOUND}（不在と同一）、
     *                           知り得るが権限が不足する場合は {@code deniedCode}
     */
    void requireChannelManagerRole(ChatChannelEntity channel, Long operatorUserId, ChatErrorCode deniedCode) {
        ChatChannelMemberEntity operator = memberRepository
                .findByChannelIdAndUserId(channel.getId(), operatorUserId)
                .orElseThrow(() -> concealOrForbid(channel, operatorUserId,
                        ChatErrorCode.CHANNEL_NOT_FOUND, deniedCode));
        ChannelMemberRole role = operator.getRole();
        if (role != ChannelMemberRole.OWNER && role != ChannelMemberRole.ADMIN) {
            throw new BusinessException(deniedCode);
        }
    }

    /**
     * チャンネル管理操作（更新・削除・アーカイブ・グループ変換）の認可を保証する。
     * チーム/組織チャンネルは当該スコープの ADMIN 以上、DM / GROUP_DM はチャンネル OWNER のみを許可する。
     */
    void requireChannelAdminAccess(ChatChannelEntity channel, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (channel.getTeamId() != null || channel.getOrganizationId() != null) {
            if (isScopeAdmin(channel, userId)) {
                return;
            }
            // 越境は不在と同一（CHANNEL_NOT_FOUND）、存在を知り得る利用者の権限不足は従来どおり COMMON_002。
            throw concealOrForbid(channel, userId, ChatErrorCode.CHANNEL_NOT_FOUND, CommonErrorCode.COMMON_002);
        }
        ChatChannelMemberEntity member = memberRepository.findByChannelIdAndUserId(channel.getId(), userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHANNEL_NOT_FOUND));
        if (member.getRole() != ChannelMemberRole.OWNER) {
            throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }
    }

    /**
     * F10.7: 問い合わせチャンネル設定の per-scope 認可を保証する。
     *
     * <p>コントローラーの {@code @PreAuthorize("hasRole('ADMIN')")} は per-scope 判定にならないため、
     * 当該チャンネルが属するチームの ADMIN / DEPUTY_ADMIN、または SYSTEM_ADMIN を要求する。
     * 存在を知り得ない利用者（他チーム・他人の DM 等）への拒否は不在と同一応答にする。</p>
     *
     * @throws BusinessException 存在を知り得ない利用者なら {@link ChatErrorCode#CHANNEL_NOT_FOUND}、
     *                           知り得るが権限が不足する場合は {@link CommonErrorCode#COMMON_002}
     */
    void requireInquiryChannelAdmin(ChatChannelEntity channel, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (channel.getTeamId() != null
                && accessControlService.isAdminOrAbove(userId, channel.getTeamId(), "TEAM")) {
            return;
        }
        throw concealOrForbid(channel, userId, ChatErrorCode.CHANNEL_NOT_FOUND, CommonErrorCode.COMMON_002);
    }

    /** チャンネルのスコープ（チーム優先・無ければ組織）の ADMIN / DEPUTY_ADMIN かを判定する。 */
    boolean isScopeAdmin(ChatChannelEntity channel, Long userId) {
        if (channel.getTeamId() != null) {
            return accessControlService.isAdminOrAbove(userId, channel.getTeamId(), "TEAM");
        }
        if (channel.getOrganizationId() != null) {
            return accessControlService.isAdminOrAbove(userId, channel.getOrganizationId(), "ORGANIZATION");
        }
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════
    // メッセージの編集・削除
    // ═════════════════════════════════════════════════════════════════════

    /**
     * メッセージ削除の認可（F04.2 §5「メッセージ削除権限」・§2 対象ロール）を保証する。
     *
     * <p>許可主体: 投稿者本人 / SYSTEM_ADMIN / チャンネルの OWNER・ADMIN / チャンネルのスコープ（チーム・組織）の ADMIN。</p>
     *
     * <p><b>DEPUTY_ADMIN は含めない</b>: F04.2 §2 は {@code MANAGE_CHAT} 権限を持つ DEPUTY_ADMIN にも削除を許すが、
     * その権限は権限カタログに未登録である（登録は別戦役 CMP-261107-2134）。未登録の権限名で判定すると誰にも付与できず
     * 番人 {@code PermissionNameCatalogGuardTest} も拒むため、登録されるまで DEPUTY_ADMIN による他人の投稿の削除は許さない。
     * CMP-261107-2134 で {@code hasAdminOrPermissionInScope(..., "MANAGE_CHAT")} の判定へ広げる。</p>
     *
     * @return 投稿者本人以外による削除（モデレーション削除）なら true
     * @throws BusinessException 存在を知り得ない利用者なら {@link ChatErrorCode#MESSAGE_NOT_FOUND}（不在と同一）、
     *                           知り得るが権限が無い場合は {@link ChatErrorCode#MESSAGE_DELETE_DENIED}
     */
    boolean requireMessageDeletable(ChatChannelEntity channel, ChatMessageEntity message, Long userId) {
        if (userId != null && userId.equals(message.getSenderId())) {
            return false;
        }
        if (userId != null && canModerateMessages(channel, userId)) {
            return true;
        }
        throw concealOrForbid(channel, userId, ChatErrorCode.MESSAGE_NOT_FOUND, ChatErrorCode.MESSAGE_DELETE_DENIED);
    }

    /**
     * 呼出ユーザーが当該チャンネルのメッセージをモデレーション（他人のメッセージの削除）できるかを判定する。
     * 判定材料はチャンネル実体のスコープとメンバー行のみで、リクエストの申告値は使わない。
     */
    boolean canModerateMessages(ChatChannelEntity channel, Long userId) {
        if (userId == null) {
            return false;
        }
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        var member = memberRepository.findByChannelIdAndUserId(channel.getId(), userId);
        if (member.isPresent()) {
            ChannelMemberRole role = member.get().getRole();
            if (role == ChannelMemberRole.OWNER || role == ChannelMemberRole.ADMIN) {
                return true;
            }
        }
        // スコープ ADMIN のみ（DEPUTY_ADMIN は MANAGE_CHAT 登録後の CMP-261107-2134 で広げる）。
        if (channel.getTeamId() != null) {
            return accessControlService.isAdmin(userId, channel.getTeamId(), "TEAM");
        }
        if (channel.getOrganizationId() != null) {
            return accessControlService.isAdmin(userId, channel.getOrganizationId(), "ORGANIZATION");
        }
        return false;
    }

    /**
     * メッセージの編集について、呼出ユーザーが<b>当該メッセージの送信者本人</b>であることを保証する。
     *
     * <p>判定は引数で渡された channelId 等ではなく、取得済みメッセージエンティティの
     * {@code senderId} に基づく。</p>
     *
     * @throws BusinessException 送信者本人でない場合。チャンネルの存在を知り得ない利用者なら
     *                           {@link ChatErrorCode#MESSAGE_NOT_FOUND}（不在と同一）、知り得るなら
     *                           {@link ChatErrorCode#MESSAGE_EDIT_DENIED}
     */
    void requireMessageOwner(ChatChannelEntity channel, ChatMessageEntity message, Long userId) {
        if (userId == null || !userId.equals(message.getSenderId())) {
            throw concealOrForbid(channel, userId, ChatErrorCode.MESSAGE_NOT_FOUND, ChatErrorCode.MESSAGE_EDIT_DENIED);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 作成（スコープ）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * チャンネル作成時に、作成者が<b>申告されたスコープ（チーム / 組織）の所属者</b>であることを保証する。
     *
     * <p>チーム/組織チャンネルはそのスコープの内部資産であり、スコープ外の利用者が作成できる余地を残さない。
     * 非公開チャンネル（{@code *_PRIVATE} または {@code isPrivate=true}）はスコープの ADMIN 以上に限定する。
     * DM / GROUP_DM はスコープを持たないため本判定の対象外であり、別途 {@link ChatDmReceiveAccessGuard} で
     * 相手側の受信可否を保証する。</p>
     *
     * @throws BusinessException スコープ非所属・権限不足の場合
     */
    public void requireChannelCreationScope(ChannelType channelType, Long teamId, Long organizationId,
                                            boolean isPrivate, Long createdBy) {
        if (!channelType.isScopeChannel()) {
            return;
        }
        boolean adminRequired = isPrivate || !channelType.isSelfJoinableScopeChannel();
        if (teamId != null) {
            if (adminRequired) {
                accessControlService.checkAdminOrAbove(createdBy, teamId, "TEAM");
            } else {
                accessControlService.checkMembership(createdBy, teamId, "TEAM");
            }
            return;
        }
        if (organizationId != null) {
            if (adminRequired) {
                accessControlService.checkAdminOrAbove(createdBy, organizationId, "ORGANIZATION");
            } else {
                accessControlService.checkMembership(createdBy, organizationId, "ORGANIZATION");
            }
            return;
        }
        // スコープ種別なのにスコープ識別子が無い場合は所属を検証できない。fail-closed で拒否する。
        throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
    }
}
