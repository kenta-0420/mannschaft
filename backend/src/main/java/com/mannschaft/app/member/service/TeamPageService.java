package com.mannschaft.app.member.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.member.MemberErrorCode;
import com.mannschaft.app.member.MemberMapper;
import com.mannschaft.app.member.PageStatus;
import com.mannschaft.app.member.PageType;
import com.mannschaft.app.member.PageVisibility;
import com.mannschaft.app.member.dto.CreateTeamPageRequest;
import com.mannschaft.app.member.dto.PreviewTokenResponse;
import com.mannschaft.app.member.dto.PublishRequest;
import com.mannschaft.app.member.dto.SectionResponse;
import com.mannschaft.app.member.dto.MemberProfileResponse;
import com.mannschaft.app.member.dto.TeamPageResponse;
import com.mannschaft.app.member.dto.UpdateTeamPageRequest;
import com.mannschaft.app.member.entity.TeamPageEntity;
import com.mannschaft.app.member.entity.TeamPageSectionEntity;
import com.mannschaft.app.member.entity.MemberProfileEntity;
import com.mannschaft.app.member.repository.TeamPageRepository;
import com.mannschaft.app.member.repository.TeamPageSectionRepository;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * メンバー紹介ページサービス。ページのCRUD・公開管理・プレビュートークンを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamPageService {

    private final TeamPageRepository pageRepository;
    private final TeamPageSectionRepository sectionRepository;
    private final MemberProfileRepository profileRepository;
    private final MemberMapper memberMapper;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * ページ一覧をページング取得する。
     */
    public Page<TeamPageResponse> listPages(Long teamId, Long organizationId, Pageable pageable) {
        Page<TeamPageEntity> page;
        if (teamId != null) {
            page = pageRepository.findByTeamIdOrderBySortOrder(teamId, pageable);
        } else {
            page = pageRepository.findByOrganizationIdOrderBySortOrder(organizationId, pageable);
        }
        return page.map(memberMapper::toTeamPageResponse);
    }

    /**
     * ページ詳細をセクション・メンバー付きで取得する。
     */
    public TeamPageResponse getPage(Long pageId) {
        TeamPageEntity entity = findPageOrThrow(pageId);
        List<TeamPageSectionEntity> sections = sectionRepository.findByTeamPageIdOrderBySortOrder(pageId);
        List<MemberProfileEntity> members = profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(pageId);

        List<SectionResponse> sectionResponses = memberMapper.toSectionResponseList(sections);
        List<MemberProfileResponse> memberResponses = memberMapper.toMemberProfileResponseList(members);

        return memberMapper.toTeamPageDetailResponse(entity, sectionResponses, memberResponses);
    }

    /**
     * ページを作成する。
     */
    @Transactional
    public TeamPageResponse createPage(Long userId, CreateTeamPageRequest request) {
        PageType pageType = PageType.valueOf(request.getPageType());
        Long teamId = request.getTeamId();
        Long organizationId = request.getOrganizationId();

        // メインページの重複チェック
        if (pageType == PageType.MAIN) {
            if (teamId != null && pageRepository.findByTeamIdAndPageType(teamId, PageType.MAIN).isPresent()) {
                throw new BusinessException(MemberErrorCode.DUPLICATE_MAIN_PAGE);
            }
            if (organizationId != null && pageRepository.findByOrganizationIdAndPageType(organizationId, PageType.MAIN).isPresent()) {
                throw new BusinessException(MemberErrorCode.DUPLICATE_MAIN_PAGE);
            }
        }

        // 年度重複チェック
        if (pageType == PageType.YEARLY && request.getYear() != null) {
            if (teamId != null && pageRepository.existsByTeamIdAndYear(teamId, request.getYear())) {
                throw new BusinessException(MemberErrorCode.DUPLICATE_YEAR);
            }
            if (organizationId != null && pageRepository.existsByOrganizationIdAndYear(organizationId, request.getYear())) {
                throw new BusinessException(MemberErrorCode.DUPLICATE_YEAR);
            }
        }

        // スラッグ重複チェック
        if (teamId != null && pageRepository.existsByTeamIdAndSlug(teamId, request.getSlug())) {
            throw new BusinessException(MemberErrorCode.DUPLICATE_SLUG);
        }
        if (organizationId != null && pageRepository.existsByOrganizationIdAndSlug(organizationId, request.getSlug())) {
            throw new BusinessException(MemberErrorCode.DUPLICATE_SLUG);
        }

        PageVisibility visibility = request.getVisibility() != null
                ? PageVisibility.valueOf(request.getVisibility()) : PageVisibility.MEMBERS_ONLY;

        TeamPageEntity entity = TeamPageEntity.builder()
                .teamId(teamId)
                .organizationId(organizationId)
                .title(request.getTitle())
                .slug(request.getSlug())
                .pageType(pageType)
                .year(request.getYear())
                .description(request.getDescription())
                .coverImageS3Key(request.getCoverImageS3Key())
                .visibility(visibility)
                .createdBy(userId)
                .build();

        TeamPageEntity saved = pageRepository.save(entity);
        log.info("ページ作成: id={}, pageType={}", saved.getId(), pageType);
        return memberMapper.toTeamPageResponse(saved);
    }

    /**
     * ページを更新する。
     */
    @Transactional
    public TeamPageResponse updatePage(Long pageId, UpdateTeamPageRequest request) {
        TeamPageEntity entity = findPageOrThrow(pageId);

        PageVisibility visibility = request.getVisibility() != null
                ? PageVisibility.valueOf(request.getVisibility()) : entity.getVisibility();
        Boolean allowSelfEdit = request.getAllowSelfEdit() != null
                ? request.getAllowSelfEdit() : entity.getAllowSelfEdit();
        Integer sortOrder = request.getSortOrder() != null
                ? request.getSortOrder() : entity.getSortOrder();

        entity.update(request.getTitle(), request.getSlug(), request.getDescription(),
                request.getCoverImageS3Key(), visibility, allowSelfEdit, sortOrder);

        TeamPageEntity saved = pageRepository.save(entity);
        log.info("ページ更新: id={}", pageId);
        return memberMapper.toTeamPageResponse(saved);
    }

    /**
     * ページを論理削除する。
     */
    @Transactional
    public void deletePage(Long pageId) {
        TeamPageEntity entity = findPageOrThrow(pageId);
        entity.softDelete();
        pageRepository.save(entity);
        log.info("ページ削除: id={}", pageId);
    }

    /**
     * 公開ステータスを変更する。
     */
    @Transactional
    public TeamPageResponse changeStatus(Long pageId, PublishRequest request) {
        TeamPageEntity entity = findPageOrThrow(pageId);
        PageStatus status = PageStatus.valueOf(request.getStatus());
        entity.changeStatus(status);
        TeamPageEntity saved = pageRepository.save(entity);
        log.info("ページステータス変更: id={}, status={}", pageId, status);
        return memberMapper.toTeamPageResponse(saved);
    }

    /**
     * プレビュートークンを発行する。
     */
    @Transactional
    public PreviewTokenResponse issuePreviewToken(Long pageId) {
        TeamPageEntity entity = findPageOrThrow(pageId);

        byte[] tokenBytes = new byte[48];
        SECURE_RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(24);

        entity.setPreviewToken(token, expiresAt);
        pageRepository.save(entity);

        String previewUrl = String.format("/team/pages/%s?preview_token=%s", entity.getSlug(), token);
        log.info("プレビュートークン発行: pageId={}", pageId);

        return new PreviewTokenResponse(entity.getId(), token, previewUrl, expiresAt);
    }

    /**
     * プレビュートークンを無効化する。
     */
    @Transactional
    public void revokePreviewToken(Long pageId) {
        TeamPageEntity entity = findPageOrThrow(pageId);
        entity.clearPreviewToken();
        pageRepository.save(entity);
        log.info("プレビュートークン無効化: pageId={}", pageId);
    }

    /**
     * ページエンティティを取得する。存在しない場合は例外をスローする。
     */
    TeamPageEntity findPageOrThrow(Long pageId) {
        return pageRepository.findById(pageId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.PAGE_NOT_FOUND));
    }
}
