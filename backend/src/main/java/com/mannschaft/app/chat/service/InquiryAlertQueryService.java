package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F10.1.1 / P3b: チャットドメインの管理者レンズ「未読問い合わせ」集約 Query Service（read-only）。
 *
 * <p>チーム/組織パネル管理者レンズ ⑤（{@code ADMIN_TEAM_ALERT} / {@code ADMIN_ORG_ALERT}）の
 * 「未読問い合わせ件数」を 1 スコープ分だけ集計する。問い合わせチャンネル（{@code is_inquiry_channel=TRUE}）を
 * スコープで引き、閲覧者の未読合計を返す（設計書 02 §3）。</p>
 *
 * <p>全クエリの WHERE にスコープ列（{@code team_id} / {@code organization_id}）を含めるため、
 * テナント越境（IDOR）は構造的に発生しない。スコープに問い合わせチャンネルが存在しなければ 0 を返す
 * （0 件であり、症状を隠すための握りつぶしではない）。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.2 ⑤ / §2.3 ⑤ / §3</p>
 */
@Service
@RequiredArgsConstructor
public class InquiryAlertQueryService {

    private final ChatChannelRepository chatChannelRepository;
    private final ChatChannelMemberRepository chatChannelMemberRepository;

    /**
     * 指定チームの問い合わせチャンネルにおける、閲覧者の未読問い合わせ件数合計を返す。
     *
     * @param userId 閲覧者ユーザー ID（未読は閲覧者ごと）
     * @param teamId チーム ID（WHERE 必須・IDOR 防止）
     * @return 未読問い合わせ件数（問い合わせチャンネル無しなら 0）
     */
    @Transactional(readOnly = true)
    public long unreadInquiriesForTeam(Long userId, Long teamId) {
        ChatChannelEntity channel = chatChannelRepository
                .findByTeamIdAndIsInquiryChannelTrue(teamId)
                .orElse(null);
        if (channel == null) {
            return 0L;
        }
        return chatChannelMemberRepository
                .sumUnreadCountByUserIdAndChannelIds(userId, List.of(channel.getId()));
    }

    /**
     * 指定組織の問い合わせチャンネルにおける、閲覧者の未読問い合わせ件数合計を返す。
     *
     * @param userId 閲覧者ユーザー ID
     * @param orgId  組織 ID（WHERE 必須・IDOR 防止）
     * @return 未読問い合わせ件数（問い合わせチャンネル無しなら 0）
     */
    @Transactional(readOnly = true)
    public long unreadInquiriesForOrg(Long userId, Long orgId) {
        List<Long> channelIds = chatChannelRepository
                .findByOrganizationIdAndIsInquiryChannelTrue(orgId)
                .stream()
                .map(ChatChannelEntity::getId)
                .toList();
        if (channelIds.isEmpty()) {
            return 0L;
        }
        return chatChannelMemberRepository.sumUnreadCountByUserIdAndChannelIds(userId, channelIds);
    }
}
