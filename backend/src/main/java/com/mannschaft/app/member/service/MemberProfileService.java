package com.mannschaft.app.member.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.member.MemberErrorCode;
import com.mannschaft.app.member.MemberMapper;
import com.mannschaft.app.member.dto.BulkCreateMemberRequest;
import com.mannschaft.app.member.dto.BulkCreateMemberResponse;
import com.mannschaft.app.member.dto.CopyMembersRequest;
import com.mannschaft.app.member.dto.CopyMembersResponse;
import com.mannschaft.app.member.dto.CreateMemberProfileRequest;
import com.mannschaft.app.member.dto.MemberLookupResponse;
import com.mannschaft.app.member.dto.MemberProfileResponse;
import com.mannschaft.app.member.dto.ReorderRequest;
import com.mannschaft.app.member.dto.ReorderResponse;
import com.mannschaft.app.member.dto.UpdateMemberProfileRequest;
import com.mannschaft.app.member.entity.MemberProfileEntity;
import com.mannschaft.app.member.entity.TeamPageEntity;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * メンバープロフィールサービス。プロフィールのCRUD・一括登録・コピー・並び替え・検索を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberProfileService {

    private final MemberProfileRepository profileRepository;
    private final TeamPageService pageService;
    private final MemberMapper memberMapper;

    private static final int BULK_LIMIT = 100;
    private static final int REORDER_LIMIT = 100;

    /**
     * メンバープロフィール一覧をページング取得する。
     */
    public Page<MemberProfileResponse> listProfiles(Long teamPageId, Pageable pageable) {
        Page<MemberProfileEntity> page = profileRepository.findByTeamPageIdOrderBySortOrder(teamPageId, pageable);
        return page.map(memberMapper::toMemberProfileResponse);
    }

    /**
     * メンバープロフィール詳細を取得する。
     */
    public MemberProfileResponse getProfile(Long profileId) {
        MemberProfileEntity entity = findProfileOrThrow(profileId);
        return memberMapper.toMemberProfileResponse(entity);
    }

    /**
     * メンバープロフィールを作成する。
     */
    @Transactional
    public MemberProfileResponse createProfile(CreateMemberProfileRequest request) {
        // ページ存在確認
        pageService.findPageOrThrow(request.getTeamPageId());

        // ユーザー重複チェック
        if (request.getUserId() != null &&
                profileRepository.existsByTeamPageIdAndUserId(request.getTeamPageId(), request.getUserId())) {
            throw new BusinessException(MemberErrorCode.DUPLICATE_USER);
        }

        MemberProfileEntity entity = MemberProfileEntity.builder()
                .teamPageId(request.getTeamPageId())
                .userId(request.getUserId())
                .displayName(request.getDisplayName())
                .memberNumber(request.getMemberNumber())
                .photoS3Key(request.getPhotoS3Key())
                .bio(request.getBio())
                .position(request.getPosition())
                .customFieldValues(request.getCustomFieldValues())
                .build();

        MemberProfileEntity saved = profileRepository.save(entity);
        log.info("プロフィール作成: profileId={}, teamPageId={}", saved.getId(), request.getTeamPageId());
        return memberMapper.toMemberProfileResponse(saved);
    }

    /**
     * メンバープロフィールを更新する。
     */
    @Transactional
    public MemberProfileResponse updateProfile(Long profileId, UpdateMemberProfileRequest request) {
        MemberProfileEntity entity = findProfileOrThrow(profileId);

        Integer sortOrder = request.getSortOrder() != null ? request.getSortOrder() : entity.getSortOrder();
        Boolean isVisible = request.getIsVisible() != null ? request.getIsVisible() : entity.getIsVisible();

        entity.update(request.getDisplayName(), request.getMemberNumber(),
                request.getPhotoS3Key(), request.getBio(), request.getPosition(),
                request.getCustomFieldValues(), sortOrder, isVisible);

        MemberProfileEntity saved = profileRepository.save(entity);
        log.info("プロフィール更新: profileId={}", profileId);
        return memberMapper.toMemberProfileResponse(saved);
    }

    /**
     * メンバープロフィールを削除する。
     */
    @Transactional
    public void deleteProfile(Long profileId) {
        MemberProfileEntity entity = findProfileOrThrow(profileId);
        profileRepository.delete(entity);
        log.info("プロフィール削除: profileId={}", profileId);
    }

    /**
     * メンバープロフィールを一括登録する。
     */
    @Transactional
    public BulkCreateMemberResponse bulkCreate(BulkCreateMemberRequest request) {
        if (request.getMembers().size() > BULK_LIMIT) {
            throw new BusinessException(MemberErrorCode.BULK_LIMIT_EXCEEDED);
        }

        // ページ存在確認
        pageService.findPageOrThrow(request.getTeamPageId());

        int createdCount = 0;
        List<Long> skippedUserIds = new ArrayList<>();

        for (BulkCreateMemberRequest.BulkMemberItem item : request.getMembers()) {
            if (item.getUserId() != null &&
                    profileRepository.existsByTeamPageIdAndUserId(request.getTeamPageId(), item.getUserId())) {
                skippedUserIds.add(item.getUserId());
                continue;
            }

            MemberProfileEntity entity = MemberProfileEntity.builder()
                    .teamPageId(request.getTeamPageId())
                    .userId(item.getUserId())
                    .displayName(item.getDisplayName())
                    .memberNumber(item.getMemberNumber())
                    .photoS3Key(item.getPhotoS3Key())
                    .bio(item.getBio())
                    .position(item.getPosition())
                    .customFieldValues(item.getCustomFields())
                    .build();

            profileRepository.save(entity);
            createdCount++;
        }

        log.info("プロフィール一括登録: teamPageId={}, created={}, skipped={}",
                request.getTeamPageId(), createdCount, skippedUserIds.size());

        return new BulkCreateMemberResponse(createdCount, skippedUserIds.size(), skippedUserIds);
    }

    /**
     * 前年度ページからメンバーをコピーする。
     */
    @Transactional
    public CopyMembersResponse copyMembers(Long targetPageId, CopyMembersRequest request) {
        // ターゲットページ存在確認
        pageService.findPageOrThrow(targetPageId);

        // コピー元ページ存在確認
        TeamPageEntity sourcePage = pageService.findPageOrThrow(request.getSourcePageId());

        if (targetPageId.equals(request.getSourcePageId())) {
            throw new BusinessException(MemberErrorCode.INVALID_SOURCE_PAGE);
        }

        List<MemberProfileEntity> sourceMembers =
                profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(request.getSourcePageId());

        int copiedCount = 0;
        List<Long> skippedUserIds = new ArrayList<>();

        for (MemberProfileEntity source : sourceMembers) {
            if (source.getUserId() != null &&
                    profileRepository.existsByTeamPageIdAndUserId(targetPageId, source.getUserId())) {
                skippedUserIds.add(source.getUserId());
                continue;
            }

            MemberProfileEntity copy = MemberProfileEntity.builder()
                    .teamPageId(targetPageId)
                    .userId(source.getUserId())
                    .displayName(source.getDisplayName())
                    .memberNumber(source.getMemberNumber())
                    .photoS3Key(source.getPhotoS3Key())
                    .bio(source.getBio())
                    .position(source.getPosition())
                    .customFieldValues(source.getCustomFieldValues())
                    .sortOrder(source.getSortOrder())
                    .build();

            profileRepository.save(copy);
            copiedCount++;
        }

        log.info("メンバーコピー: sourcePageId={}, targetPageId={}, copied={}, skipped={}",
                request.getSourcePageId(), targetPageId, copiedCount, skippedUserIds.size());

        return new CopyMembersResponse(copiedCount, skippedUserIds.size(), skippedUserIds);
    }

    /**
     * メンバーの表示順を一括更新する。
     */
    @Transactional
    public ReorderResponse reorderMembers(ReorderRequest request) {
        if (request.getOrders().size() > REORDER_LIMIT) {
            throw new BusinessException(MemberErrorCode.REORDER_LIMIT_EXCEEDED);
        }

        int updatedCount = 0;
        for (ReorderRequest.OrderItem item : request.getOrders()) {
            profileRepository.findById(item.getId()).ifPresent(entity -> {
                entity.updateSortOrder(item.getSortOrder());
                profileRepository.save(entity);
            });
            updatedCount++;
        }

        log.info("メンバー並び替え: teamPageId={}, updatedCount={}",
                request.getTeamPageId(), updatedCount);

        return new ReorderResponse(updatedCount);
    }

    /**
     * メンバー番号・表示名でメンバーを検索する（コンボボックス用）。
     */
    public List<MemberLookupResponse> lookupMembers(Long teamPageId, String query, int limit) {
        String numberQuery = query + "%";
        String nameQuery = "%" + query + "%";
        Pageable pageable = PageRequest.of(0, Math.min(limit, 20));

        List<MemberProfileEntity> entities = profileRepository.lookupMembers(
                teamPageId, numberQuery, nameQuery, query, pageable);

        return memberMapper.toMemberLookupResponseList(entities);
    }

    /**
     * プロフィールエンティティを取得する。存在しない場合は例外をスローする。
     */
    MemberProfileEntity findProfileOrThrow(Long profileId) {
        return profileRepository.findById(profileId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.PROFILE_NOT_FOUND));
    }
}
