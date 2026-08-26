package com.mannschaft.app.contact.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.contact.ContactErrorCode;
import com.mannschaft.app.contact.dto.ContactableMemberResponse;
import com.mannschaft.app.contact.repository.ContactRequestRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.user.repository.UserBlockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * チーム/組織メンバーからの連絡先申請可能メンバー一覧サービス。
 *
 * <p><b>保証する可視性（設計書 {@code docs/features/F04.8_contact.md §4.7}）</b>:
 * メンバー一覧の参照可否は<b>entity（チーム・組織）から取得した {@code visibility}</b> で判定し、
 * リクエスト値は信頼しない。{@code PUBLIC} のスコープは認証ユーザーに開示し、
 * それ以外のスコープは<b>当該スコープのメンバーに限定</b>する（非メンバーは
 * {@link ContactErrorCode#CONTACT_007} = 403、不存在スコープは
 * {@link ContactErrorCode#CONTACT_015} = 404）。</p>
 *
 * <p>開示するのは氏名・ハンドル・アバターと申請状態のみに絞り、ブロック関係にある相手は
 * 一覧から除外する（{@code buildContactableResponses}）。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ContactableMemberService {

    private static final int MAX_PAGE_SIZE = 50;

    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;
    private final ContactRequestRepository contactRequestRepository;
    private final MediaUrlResolver mediaUrlResolver;

    /**
     * チームの連絡先申請可能メンバー一覧を取得する。
     *
     * @param currentUserId 現在のユーザーID
     * @param teamId        チームID
     * @param q             キーワード検索
     * @param page          ページ番号（0始まり）
     * @param size          ページサイズ
     */
    public List<ContactableMemberResponse> getTeamContactableMembers(
            Long currentUserId, Long teamId, String q, int page, int size) {

        TeamEntity team = teamRepository.findById(teamId)
                .orElseThrow(() -> new BusinessException(ContactErrorCode.CONTACT_015));

        // アクセス制限チェック
        if (team.getVisibility() != TeamEntity.Visibility.PUBLIC) {
            if (!userRoleRepository.existsByUserIdAndTeamId(currentUserId, teamId)) {
                throw new BusinessException(ContactErrorCode.CONTACT_007);
            }
        }

        // チームメンバーを取得（ページング）
        int safeSize = Math.min(size, MAX_PAGE_SIZE);
        List<UserRoleEntity> roles = userRoleRepository
                .findByTeamId(teamId, PageRequest.of(page, safeSize)).getContent();

        return buildContactableResponses(currentUserId, roles.stream()
                .map(UserRoleEntity::getUserId).toList(), q);
    }

    /**
     * 組織の連絡先申請可能メンバー一覧を取得する。
     */
    public List<ContactableMemberResponse> getOrgContactableMembers(
            Long currentUserId, Long orgId, String q, int page, int size) {

        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new BusinessException(ContactErrorCode.CONTACT_015));

        // アクセス制限チェック（PUBLIC 以外の組織は自分がメンバーの場合のみアクセス可）
        if (org.getVisibility() != OrganizationEntity.Visibility.PUBLIC) {
            if (!userRoleRepository.existsByUserIdAndOrganizationId(currentUserId, orgId)) {
                throw new BusinessException(ContactErrorCode.CONTACT_007);
            }
        }

        int safeSize = Math.min(size, MAX_PAGE_SIZE);
        List<UserRoleEntity> roles = userRoleRepository
                .findByOrganizationId(orgId, PageRequest.of(page, safeSize)).getContent();

        return buildContactableResponses(currentUserId, roles.stream()
                .map(UserRoleEntity::getUserId).toList(), q);
    }

    private List<ContactableMemberResponse> buildContactableResponses(
            Long currentUserId, List<Long> memberUserIds, String q) {

        // 自分自身を除外
        List<Long> filteredIds = memberUserIds.stream()
                .filter(id -> !id.equals(currentUserId))
                .toList();

        if (filteredIds.isEmpty()) return List.of();

        Map<Long, UserEntity> userMap = userRepository.findAllById(filteredIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u));

        return filteredIds.stream()
                .map(userMap::get)
                .filter(u -> u != null)
                // ブロック関係はリストから除外
                .filter(u -> !userBlockRepository.existsByBlockerIdAndBlockedId(currentUserId, u.getId())
                        && !userBlockRepository.existsByBlockedIdAndBlockerId(u.getId(), currentUserId))
                // キーワードフィルタ
                .filter(u -> {
                    if (q == null || q.isBlank()) return true;
                    String keyword = q.toLowerCase();
                    return (u.getLastName() + " " + u.getFirstName()).toLowerCase().contains(keyword)
                            || (u.getContactHandle() != null && u.getContactHandle().contains(keyword));
                })
                .map(u -> {
                    boolean isContact = contactRequestRepository.isContact(currentUserId, u.getId());
                    boolean hasPending = contactRequestRepository
                            .findByRequesterIdAndTargetIdAndStatus(currentUserId, u.getId(), "PENDING")
                            .isPresent();
                    return ContactableMemberResponse.builder()
                            .userId(u.getId())
                            .fullName(u.getLastName() + " " + u.getFirstName())
                            .contactHandle(u.getContactHandle())
                            .avatarUrl(mediaUrlResolver.resolve(u.getAvatarUrl()))
                            .isContact(isContact)
                            .hasPendingRequest(hasPending)
                            .build();
                })
                .toList();
    }
}
