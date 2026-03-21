package com.mannschaft.app.member.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.member.MemberErrorCode;
import com.mannschaft.app.member.MemberMapper;
import com.mannschaft.app.member.SectionType;
import com.mannschaft.app.member.dto.CreateSectionRequest;
import com.mannschaft.app.member.dto.SectionResponse;
import com.mannschaft.app.member.dto.UpdateSectionRequest;
import com.mannschaft.app.member.entity.TeamPageSectionEntity;
import com.mannschaft.app.member.repository.TeamPageSectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ページセクションサービス。セクションのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamPageSectionService {

    private final TeamPageSectionRepository sectionRepository;
    private final TeamPageService pageService;
    private final MemberMapper memberMapper;

    /**
     * セクション一覧を取得する。
     */
    public List<SectionResponse> listSections(Long pageId) {
        // ページ存在確認
        pageService.findPageOrThrow(pageId);
        List<TeamPageSectionEntity> entities = sectionRepository.findByTeamPageIdOrderBySortOrder(pageId);
        return memberMapper.toSectionResponseList(entities);
    }

    /**
     * セクションを追加する。
     */
    @Transactional
    public SectionResponse createSection(Long pageId, CreateSectionRequest request) {
        // ページ存在確認
        pageService.findPageOrThrow(pageId);

        SectionType sectionType = SectionType.valueOf(request.getSectionType());
        Integer sortOrder = request.getSortOrder() != null ? request.getSortOrder() : 0;

        TeamPageSectionEntity entity = TeamPageSectionEntity.builder()
                .teamPageId(pageId)
                .sectionType(sectionType)
                .title(request.getTitle())
                .content(request.getContent())
                .imageS3Key(request.getImageS3Key())
                .imageCaption(request.getImageCaption())
                .sortOrder(sortOrder)
                .build();

        TeamPageSectionEntity saved = sectionRepository.save(entity);
        log.info("セクション作成: pageId={}, sectionId={}", pageId, saved.getId());
        return memberMapper.toSectionResponse(saved);
    }

    /**
     * セクションを更新する。
     */
    @Transactional
    public SectionResponse updateSection(Long sectionId, UpdateSectionRequest request) {
        TeamPageSectionEntity entity = findSectionOrThrow(sectionId);

        Integer sortOrder = request.getSortOrder() != null ? request.getSortOrder() : entity.getSortOrder();

        entity.update(request.getTitle(), request.getContent(),
                request.getImageS3Key(), request.getImageCaption(), sortOrder);

        TeamPageSectionEntity saved = sectionRepository.save(entity);
        log.info("セクション更新: sectionId={}", sectionId);
        return memberMapper.toSectionResponse(saved);
    }

    /**
     * セクションを削除する。
     */
    @Transactional
    public void deleteSection(Long sectionId) {
        TeamPageSectionEntity entity = findSectionOrThrow(sectionId);
        sectionRepository.delete(entity);
        log.info("セクション削除: sectionId={}", sectionId);
    }

    /**
     * セクションエンティティを取得する。存在しない場合は例外をスローする。
     */
    private TeamPageSectionEntity findSectionOrThrow(Long sectionId) {
        return sectionRepository.findById(sectionId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.SECTION_NOT_FOUND));
    }
}
