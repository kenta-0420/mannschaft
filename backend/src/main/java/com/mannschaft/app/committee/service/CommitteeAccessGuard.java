package com.mannschaft.app.committee.service;

import com.mannschaft.app.committee.entity.CommitteeInvitationEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.error.CommitteeErrorCode;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 委員会ドメインの認可判定を一元化するガード。
 *
 * <p>委員会の認可は<b>委員会メンバーシップと委員会内ロール</b>（{@code committee_members} の現役行）
 * に基づく。組織のロールとは独立した軸であり、組織 ADMIN であることが委員会内の権限を意味しない
 * （組織 ADMIN が加わる判定は委員会サービス側で明示的に併記する）。</p>
 *
 * <p>判定はすべて<b>対象エンティティを取得したうえで、そのエンティティが属する委員会</b>で行う。
 * パス変数の委員会 ID は照合の対象であって判定の根拠にはしない。これにより、別委員会に属する
 * 招集状・伝達ログの識別子を差し込む経路を構造的に塞ぐ。</p>
 *
 * <p>本クラスはリポジトリのみに依存し、委員会の業務サービスには依存しない。循環依存を作らずに
 * 全経路が同一の判定を通ることを保証するための構成である。</p>
 */
@Service
@RequiredArgsConstructor
public class CommitteeAccessGuard {

    private final CommitteeMemberRepository committeeMemberRepository;

    /**
     * 委員会の現役メンバーであることを保証し、そのメンバー行を返す。
     *
     * <p>メンバー一覧の閲覧・伝達履歴の閲覧・自発的離脱など「委員会の内部情報に触れる操作」の
     * 最低要件として用いる。</p>
     *
     * @param committeeId 対象委員会 ID
     * @param userId      操作者ユーザー ID
     * @return 操作者の現役メンバー行
     * @throws BusinessException 現役メンバーでない場合（{@code COMMON_002} / 403）
     */
    public CommitteeMemberEntity requireCommitteeMember(Long committeeId, Long userId) {
        return committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(committeeId, userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_002));
    }

    /**
     * 委員会内で指定ロールのいずれかを保持していることを保証し、そのメンバー行を返す。
     *
     * @param committeeId  対象委員会 ID
     * @param userId       操作者ユーザー ID
     * @param allowedRoles 許可するロール（1 つ以上）
     * @return 操作者の現役メンバー行
     * @throws BusinessException 現役メンバーでない、またはロールが不足する場合（{@code COMMON_002} / 403）
     */
    public CommitteeMemberEntity requireCommitteeRole(Long committeeId, Long userId, CommitteeRole... allowedRoles) {
        CommitteeMemberEntity member = requireCommitteeMember(committeeId, userId);
        Set<CommitteeRole> allowed = EnumSet.noneOf(CommitteeRole.class);
        for (CommitteeRole role : allowedRoles) {
            allowed.add(role);
        }
        if (!allowed.contains(member.getRole())) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return member;
    }

    /**
     * 委員会内で指定ロールのいずれかを保持しているかを真偽で返す（組織ロールとの OR 判定が要る経路向け）。
     *
     * @param committeeId  対象委員会 ID
     * @param userId       判定対象ユーザー ID
     * @param allowedRoles 対象ロール
     * @return いずれかのロールを持つ現役メンバーなら {@code true}
     */
    public boolean hasCommitteeRole(Long committeeId, Long userId, CommitteeRole... allowedRoles) {
        return committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(committeeId, userId)
                .map(member -> {
                    for (CommitteeRole role : allowedRoles) {
                        if (member.getRole() == role) {
                            return true;
                        }
                    }
                    return false;
                })
                .orElse(false);
    }

    /**
     * 招集状の宛先本人であることを保証する。
     *
     * <p>受諾・辞退はいずれも<b>その招集状の被招集者本人</b>のみが行える。冪等応答を返す分岐より
     * 前に本判定を通すことで、宛先でない利用者が招集状の状態を観測する経路を作らない。</p>
     *
     * @param invitation  対象招集状
     * @param userId      操作者ユーザー ID
     * @throws BusinessException 被招集者本人でない場合（{@code COMMON_002} / 403）
     */
    public void requireInvitee(CommitteeInvitationEntity invitation, Long userId) {
        if (invitation == null || !Objects.equals(invitation.getInviteeUserId(), userId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * 招集状の取り下げ権限（招集者本人 または 当該招集状が属する委員会の CHAIR）を保証する。
     *
     * <p>判定に用いる委員会 ID は<b>招集状エンティティ由来</b>である。</p>
     *
     * @param invitation 対象招集状
     * @param userId     操作者ユーザー ID
     * @throws BusinessException いずれも満たさない場合（{@code COMMON_002} / 403）
     */
    public void requireInvitationCanceller(CommitteeInvitationEntity invitation, Long userId) {
        if (invitation == null) {
            throw new BusinessException(CommitteeErrorCode.INVITATION_NOT_FOUND);
        }
        if (Objects.equals(invitation.getInvitedBy(), userId)) {
            return;
        }
        requireCommitteeRole(invitation.getCommitteeId(), userId, CommitteeRole.CHAIR);
    }

    /**
     * 子リソースが<b>パスで指定された委員会に属している</b>ことを保証する。
     *
     * <p>伝達ログのように、実体が自身の所属委員会を持つリソースについて、パス変数の委員会 ID と
     * 実体の委員会 ID が食い違う要求を遮断する。</p>
     *
     * @param pathCommitteeId   パスで指定された委員会 ID
     * @param entityCommitteeId 実体が保持する委員会 ID
     * @throws BusinessException 一致しない場合（{@code NOT_FOUND} / 404）
     */
    public void requireSameCommittee(Long pathCommitteeId, Long entityCommitteeId) {
        if (!Objects.equals(pathCommitteeId, entityCommitteeId)) {
            throw new BusinessException(CommitteeErrorCode.NOT_FOUND);
        }
    }
}
