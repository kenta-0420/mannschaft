package com.mannschaft.app.corkboard;

import com.mannschaft.app.corkboard.dto.CorkboardCardResponse;
import com.mannschaft.app.corkboard.dto.CorkboardDetailResponse;
import com.mannschaft.app.corkboard.dto.CorkboardGroupResponse;
import com.mannschaft.app.corkboard.dto.CorkboardResponse;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardGroupEntity;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * コルクボード機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface CorkboardMapper {

    default CorkboardResponse toBoardResponse(CorkboardEntity entity) {
        if (entity == null) {
            return null;
        }
        return CorkboardResponse.builder()
                .id(entity.getId())
                .scope(new CorkboardResponse.BoardScopeDto(entity.getScopeType(), entity.getScopeId()))
                .ownerId(entity.getOwnerId())
                .name(entity.getName())
                .settings(new CorkboardResponse.BoardSettingsDto(
                        entity.getBackgroundStyle(),
                        entity.getEditPolicy(),
                        entity.getIsDefault()))
                .version(entity.getVersion())
                .audit(new CorkboardResponse.BoardAuditDto(entity.getCreatedAt(), entity.getUpdatedAt()))
                .build();
    }

    default List<CorkboardResponse> toBoardResponseList(List<CorkboardEntity> entities) {
        if (entities == null) {
            return null;
        }
        return entities.stream().map(this::toBoardResponse).toList();
    }

    default CorkboardCardResponse toCardResponse(CorkboardCardEntity entity) {
        if (entity == null) {
            return null;
        }
        return CorkboardCardResponse.builder()
                .id(entity.getId())
                .corkboardId(entity.getCorkboardId())
                .reference(new CorkboardCardResponse.CardReferenceDto(
                        entity.getSectionId(),
                        entity.getCardType(),
                        entity.getReferenceType(),
                        entity.getReferenceId(),
                        entity.getContentSnapshot()))
                .content(new CorkboardCardResponse.CardContentDto(
                        entity.getTitle(),
                        entity.getBody(),
                        entity.getUrl(),
                        entity.getOgTitle(),
                        entity.getOgImageUrl(),
                        entity.getOgDescription()))
                .layout(new CorkboardCardResponse.CardLayoutDto(
                        entity.getPositionX(),
                        entity.getPositionY(),
                        entity.getZIndex(),
                        entity.getCardSize()))
                .style(new CorkboardCardResponse.CardStyleDto(
                        entity.getColorLabel(),
                        entity.getNoteColor()))
                .state(new CorkboardCardResponse.CardStateDto(
                        entity.getIsArchived(),
                        entity.getIsPinned(),
                        entity.getPinnedAt(),
                        entity.getAutoArchiveAt(),
                        entity.getIsRefDeleted()))
                .audit(new CorkboardCardResponse.CardAuditDto(
                        entity.getUserNote(),
                        entity.getCreatedBy(),
                        entity.getCreatedAt(),
                        entity.getUpdatedAt()))
                .build();
    }

    default List<CorkboardCardResponse> toCardResponseList(List<CorkboardCardEntity> entities) {
        if (entities == null) {
            return null;
        }
        return entities.stream().map(this::toCardResponse).toList();
    }

    default CorkboardGroupResponse toGroupResponse(CorkboardGroupEntity entity) {
        if (entity == null) {
            return null;
        }
        return CorkboardGroupResponse.builder()
                .id(entity.getId())
                .corkboardId(entity.getCorkboardId())
                .name(entity.getName())
                .isCollapsed(entity.getIsCollapsed())
                .layout(new CorkboardGroupResponse.GroupLayoutDto(
                        entity.getPositionX(),
                        entity.getPositionY(),
                        entity.getWidth(),
                        entity.getHeight()))
                .displayOrder(entity.getDisplayOrder())
                .audit(new CorkboardGroupResponse.GroupAuditDto(
                        entity.getCreatedAt(),
                        entity.getUpdatedAt()))
                .build();
    }

    default List<CorkboardGroupResponse> toGroupResponseList(List<CorkboardGroupEntity> entities) {
        if (entities == null) {
            return null;
        }
        return entities.stream().map(this::toGroupResponse).toList();
    }

    /**
     * ボード詳細レスポンスを組み立てる。
     *
     * @param entity        ボードエンティティ
     * @param cards         アクティブカード一覧
     * @param groups        セクション一覧
     * @param viewerCanEdit 閲覧ユーザーがこのボードを編集可能か
     *                      （F09.8 件A: フロントの編集ボタン disabled 制御用）
     */
    default CorkboardDetailResponse toDetailResponse(CorkboardEntity entity,
                                                      List<CorkboardCardEntity> cards,
                                                      List<CorkboardGroupEntity> groups,
                                                      boolean viewerCanEdit) {
        return CorkboardDetailResponse.builder()
                .id(entity.getId())
                .scope(new CorkboardDetailResponse.BoardScopeDto(
                        entity.getScopeType(),
                        entity.getScopeId()))
                .ownerId(entity.getOwnerId())
                .name(entity.getName())
                .settings(new CorkboardDetailResponse.BoardSettingsDto(
                        entity.getBackgroundStyle(),
                        entity.getEditPolicy(),
                        entity.getIsDefault()))
                .version(entity.getVersion())
                .boardContent(new CorkboardDetailResponse.BoardContentDto(
                        toCardResponseList(cards),
                        toGroupResponseList(groups),
                        entity.getCreatedAt(),
                        entity.getUpdatedAt()))
                .viewerCanEdit(viewerCanEdit)
                .build();
    }
}
