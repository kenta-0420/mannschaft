package com.mannschaft.app.publicview.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 投稿時に著者の本名スナップショットを取得するサービス。
 * チーム/組織が {@link NameDisclosureMode#REAL_NAME} モードの場合のみ投稿者の本名を返す。
 *
 * <p>このスナップショットは {@code blog_posts.author_real_name_snapshot} カラムに保存され、
 * 将来チームの disclosure 設定が変更されても、投稿当時の公開設定が維持される
 * （§4.7 非対称切替ルール対応）。</p>
 *
 * <p><strong>クロスドメイン注意（CLAUDE.md 原則5）</strong>: publicview ドメインが
 * team / organization / auth(user) ドメインの Repository を直接参照している。
 * 将来のイベント駆動化候補: PostCreatedEvent を受け取り snapshot を非同期で更新する方式。</p>
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §4.7 / §5</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PostAuthorSnapshotService {

    // TODO: publicview ドメインが team/org/auth ドメインの Repository を直接参照。将来はイベント駆動化を検討。
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;

    /**
     * チーム投稿作成時の本名スナップショットを取得する。
     *
     * <p>チームの {@code supporter_name_disclosure} が {@link NameDisclosureMode#REAL_NAME} の場合のみ
     * 投稿者のフルネーム（{@code users.last_name + users.first_name}）を返す。
     * {@link NameDisclosureMode#DISPLAY_NAME} の場合は {@code null} を返す（スナップショット不要）。</p>
     *
     * <p>チームまたはユーザーが存在しない場合は安全のため {@code null} を返す。</p>
     *
     * @param teamId       投稿先チーム ID
     * @param authorUserId 投稿者ユーザー ID
     * @return REAL_NAME モード時の本名スナップショット文字列、または {@code null}
     */
    @Nullable
    public String resolveForTeamPost(Long teamId, Long authorUserId) {
        if (teamId == null || authorUserId == null) {
            return null;
        }
        TeamEntity team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            log.warn("チームが見つかりません: teamId={}", teamId);
            return null;
        }
        NameDisclosureMode mode = team.getSupporterNameDisclosure() != null
                ? team.getSupporterNameDisclosure()
                : NameDisclosureMode.DISPLAY_NAME;
        if (mode != NameDisclosureMode.REAL_NAME) {
            return null;
        }
        return buildFullName(authorUserId);
    }

    /**
     * 組織投稿作成時の本名スナップショットを取得する。
     *
     * <p>組織の {@code supporter_name_disclosure} が {@link NameDisclosureMode#REAL_NAME} の場合のみ
     * 投稿者のフルネームを返す。</p>
     *
     * @param organizationId 投稿先組織 ID
     * @param authorUserId   投稿者ユーザー ID
     * @return REAL_NAME モード時の本名スナップショット文字列、または {@code null}
     */
    @Nullable
    public String resolveForOrganizationPost(Long organizationId, Long authorUserId) {
        if (organizationId == null || authorUserId == null) {
            return null;
        }
        OrganizationEntity org = organizationRepository.findById(organizationId).orElse(null);
        if (org == null) {
            log.warn("組織が見つかりません: organizationId={}", organizationId);
            return null;
        }
        NameDisclosureMode mode = org.getSupporterNameDisclosure() != null
                ? org.getSupporterNameDisclosure()
                : NameDisclosureMode.DISPLAY_NAME;
        if (mode != NameDisclosureMode.REAL_NAME) {
            return null;
        }
        return buildFullName(authorUserId);
    }

    /**
     * タイムライン投稿作成時の本名スナップショットを取得する（Phase 3 以降の実装予定）。
     *
     * <p>現在は未使用。Phase 3 で {@code timeline_posts} が公開 API 対象になった時点で
     * 投稿作成サービスに組み込む（設計書 §4.2 参照）。</p>
     *
     * @param teamId       投稿先チーム ID（または {@code null}）
     * @param orgId        投稿先組織 ID（または {@code null}）
     * @param authorUserId 投稿者ユーザー ID
     * @return REAL_NAME モード時の本名スナップショット文字列、または {@code null}
     */
    @Nullable
    public String resolveForTimelinePost(@Nullable Long teamId, @Nullable Long orgId, Long authorUserId) {
        // Phase 3 実装予定。現時点では blog_posts のみが公開 API 対象（設計書 §4.2）。
        if (teamId != null) {
            return resolveForTeamPost(teamId, authorUserId);
        }
        if (orgId != null) {
            return resolveForOrganizationPost(orgId, authorUserId);
        }
        return null;
    }

    /**
     * イベント投稿作成時の本名スナップショットを取得する（Phase 3 以降の実装予定）。
     *
     * <p>現在は未使用。Phase 3 で {@code events} が公開 API 対象になった時点で
     * 投稿作成サービスに組み込む（設計書 §4.2 参照）。</p>
     *
     * @param teamId       投稿先チーム ID（または {@code null}）
     * @param orgId        投稿先組織 ID（または {@code null}）
     * @param authorUserId 投稿者ユーザー ID
     * @return REAL_NAME モード時の本名スナップショット文字列、または {@code null}
     */
    @Nullable
    public String resolveForEventPost(@Nullable Long teamId, @Nullable Long orgId, Long authorUserId) {
        // Phase 3 実装予定。現時点では blog_posts のみが公開 API 対象（設計書 §4.2）。
        if (teamId != null) {
            return resolveForTeamPost(teamId, authorUserId);
        }
        if (orgId != null) {
            return resolveForOrganizationPost(orgId, authorUserId);
        }
        return null;
    }

    /**
     * ユーザーの本名（last_name + first_name）を組み立てる。
     *
     * <p>どちらか一方が {@code null} の場合は非 null 側のみを返す。
     * 両方 {@code null} の場合は {@code null} を返す。</p>
     *
     * @param authorUserId 投稿者ユーザー ID
     * @return 本名文字列、または {@code null}（ユーザー不在 / 名前未設定）
     */
    @Nullable
    private String buildFullName(Long authorUserId) {
        UserEntity user = userRepository.findById(authorUserId).orElse(null);
        if (user == null) {
            log.warn("スナップショット取得: ユーザーが見つかりません: userId={}", authorUserId);
            return null;
        }
        String last = user.getLastName();
        String first = user.getFirstName();
        if (last == null && first == null) {
            return null;
        }
        if (last == null) {
            return first;
        }
        if (first == null) {
            return last;
        }
        return last + first;
    }
}
