package com.mannschaft.app.member.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EnumInputParser;
import com.mannschaft.app.member.MemberErrorCode;
import com.mannschaft.app.member.MemberMapper;
import com.mannschaft.app.member.SectionType;
import com.mannschaft.app.member.dto.CreateSectionRequest;
import com.mannschaft.app.member.dto.SectionResponse;
import com.mannschaft.app.member.dto.UpdateSectionRequest;
import com.mannschaft.app.member.entity.TeamPageEntity;
import com.mannschaft.app.member.entity.TeamPageSectionEntity;
import com.mannschaft.app.member.repository.TeamPageSectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ページセクションサービス。セクションのCRUDを担当する。
 *
 * <p><b>TX 境界（PR #3387 D-3T 根治）</b>: クラス単位の {@code @Transactional} を付けない。閲覧系メソッドは
 * 他ドメインの権限確認（{@code AccessControlService}）を読むので、member の TX の外で走らせる
 * （付けると権限確認が member の TX に入り、D-3T が赤になる）。書き込みメソッドは必ずメソッド単位の
 * {@code @Transactional} を持つこと。閲覧で生じる短い隙の扱いは
 * docs/features/F06.6_member_subtab_visibility.md「TX 境界とレース」節を参照。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamPageSectionService {

    private final TeamPageSectionRepository sectionRepository;
    private final TeamPageService pageService;
    private final MemberMapper memberMapper;

    /**
     * セクション一覧を取得する。
     */
    public List<SectionResponse> listSections(Long actorUserId, Long pageId) {
        // ページ存在確認 + entity 由来スコープでメンバー検証（Wave3-B2 member 認可根治）
        TeamPageEntity page = pageService.findPageOrThrow(pageId);
        pageService.checkPageViewableOrNotFound(actorUserId, page);
        List<TeamPageSectionEntity> entities = sectionRepository.findByTeamPageIdOrderBySortOrder(pageId);
        return memberMapper.toSectionResponseList(entities);
    }

    /**
     * セクションを追加する。
     */
    @Transactional
    public SectionResponse createSection(Long actorUserId, Long pageId, CreateSectionRequest request) {
        // ページ存在確認 + entity 由来スコープで ADMIN 以上検証（Wave3-B2 member 認可根治）
        TeamPageEntity page = pageService.findPageOrThrow(pageId);
        pageService.checkPageAdminOrNotFound(actorUserId, page);

        SectionType sectionType = EnumInputParser.parse(SectionType.class, request.getSectionType(), "sectionType");
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
    public SectionResponse updateSection(Long actorUserId, Long sectionId, UpdateSectionRequest request) {
        TeamPageSectionEntity entity = findSectionOrThrow(sectionId);
        // URL に pageId/teamId を含まない bare id エンドポイントのため、entity 由来（teamPageId経由の
        // ページ）スコープで ADMIN 以上を検証する（Wave3-B2 member BOLA対策）。
        TeamPageEntity page = pageService.findPageOrThrow(entity.getTeamPageId());
        pageService.checkPageAdminOrNotFound(actorUserId, page);

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
    public void deleteSection(Long actorUserId, Long sectionId) {
        TeamPageSectionEntity entity = findSectionOrThrow(sectionId);
        // BOLA対策: entity 由来（teamPageId経由のページ）スコープで ADMIN 以上を検証する（Wave3-B2 member）。
        TeamPageEntity page = pageService.findPageOrThrow(entity.getTeamPageId());
        pageService.checkPageAdminOrNotFound(actorUserId, page);
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
