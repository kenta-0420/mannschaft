package com.mannschaft.app.notification.confirmable.mapper;

import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDetailResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationRecipientResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationSettingsResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationTemplateResponse;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationSettingsEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationTemplateEntity;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableRecipientGroupRepository;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

/**
 * F04.9 確認通知システムの Entity → DTO 変換マッパー（MapStruct）。
 *
 * <p><b>confirmedCount について</b>:
 * {@link ConfirmableNotificationResponse} および {@link ConfirmableNotificationDetailResponse} の
 * {@code confirmedCount} は Repository のカウントメソッドを使って Controller 側でセットすること。
 * MapStruct はそのフィールドを無視する（{@code ignore = true}）。</p>
 *
 * <p><b>CMP-260920-1040 AC-32</b>: テンプレートの {@code defaultRecipientGroupId} は、参照先グループが
 * 論理削除済みなら応答で NULL に落とす（「既定＝配下すべて」に戻す）。この判定は MapStruct の
 * 既定マッピングでは表現できない（DB 参照が要る）ため、interface ではなく abstract class とし、
 * {@link ConfirmableRecipientGroupRepository} を注入して {@link #toTemplateResponse} を手動実装する。</p>
 */
@Mapper(componentModel = "spring")
public abstract class ConfirmableNotificationMapper {

    @Autowired(required = false)
    protected ConfirmableRecipientGroupRepository recipientGroupRepository;

    /**
     * 確認通知 Entity → 一覧用レスポンスDTO に変換する。
     *
     * <p>confirmedCount は Controller 側で別途セットすること。</p>
     */
    @Mapping(target = "confirmedCount", ignore = true)
    public abstract ConfirmableNotificationResponse toResponse(ConfirmableNotificationEntity entity);

    /**
     * 確認通知エンティティリスト → 一覧用レスポンスDTOリストに変換する。
     *
     * <p>confirmedCount は Controller 側で個別にセットすること。</p>
     */
    @Mapping(target = "confirmedCount", ignore = true)
    public abstract List<ConfirmableNotificationResponse> toResponseList(List<ConfirmableNotificationEntity> entities);

    /**
     * 確認通知 Entity → 詳細レスポンスDTO に変換する。
     *
     * <p>createdBy は UserEntity の id にマッピングする。
     * confirmedCount は Controller 側で別途セットすること。</p>
     */
    @Mapping(target = "createdBy", source = "createdBy.id")
    @Mapping(target = "confirmedCount", ignore = true)
    public abstract ConfirmableNotificationDetailResponse toDetailResponse(ConfirmableNotificationEntity entity);

    /**
     * 確認通知受信者 Entity → レスポンスDTO に変換する（ADMIN+ 視点・全フィールド）。
     */
    @Named("toRecipientResponseFull")
    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "displayName", source = "user.displayName")
    @Mapping(target = "avatarUrl", source = "user.avatarUrl")
    public abstract ConfirmableNotificationRecipientResponse toRecipientResponse(
            ConfirmableNotificationRecipientEntity entity);

    /**
     * 確認通知受信者エンティティリスト → レスポンスDTOリストに変換する（ADMIN+ 視点）。
     */
    @IterableMapping(qualifiedByName = "toRecipientResponseFull")
    public abstract List<ConfirmableNotificationRecipientResponse> toRecipientResponseList(
            List<ConfirmableNotificationRecipientEntity> entities);

    /*
     * CMP-260920-1040是正: 公開（MEMBER 視点）変換の toRecipientPublicResponse /
     * toRecipientPublicResponseList はここにあったが、LAZY な recipient.getUser() を関連経由で
     * 読むため退会者を含むと EntityNotFoundException になり 500 化していた。呼び出し元
     * （ConfirmableNotificationQueryService#getRecipientsForMember）をネイティブ投影から直接 DTO を
     * 組み立てる方式に是正し、本メソッドは呼び出し元が無くなったため削除した。
     */

    /**
     * 確認通知テンプレート Entity → レスポンスDTO に変換する（AC-32: 削除済み既定グループは NULL）。
     */
    public ConfirmableNotificationTemplateResponse toTemplateResponse(ConfirmableNotificationTemplateEntity entity) {
        if (entity == null) {
            return null;
        }
        ConfirmableNotificationTemplateResponse base = mapTemplateBase(entity);
        UUID groupId = entity.getDefaultRecipientGroupId();
        UUID resolvedGroupId = isDefaultGroupValid(groupId) ? groupId : null;
        return ConfirmableNotificationTemplateResponse.builder()
                .id(base.getId())
                .scopeType(base.getScopeType())
                .scopeId(base.getScopeId())
                .name(base.getName())
                .title(base.getTitle())
                .body(base.getBody())
                .defaultPriority(base.getDefaultPriority())
                .defaultRecipientGroupId(resolvedGroupId)
                .createdAt(base.getCreatedAt())
                .build();
    }

    /**
     * 確認通知テンプレートエンティティリスト → レスポンスDTOリストに変換する。
     */
    public List<ConfirmableNotificationTemplateResponse> toTemplateResponseList(
            List<ConfirmableNotificationTemplateEntity> entities) {
        return entities.stream().map(this::toTemplateResponse).toList();
    }

    /** MapStruct の既定マッピング（defaultRecipientGroupId を除く全フィールド）。 */
    @Mapping(target = "defaultRecipientGroupId", ignore = true)
    protected abstract ConfirmableNotificationTemplateResponse mapTemplateBase(
            ConfirmableNotificationTemplateEntity entity);

    private boolean isDefaultGroupValid(UUID groupId) {
        if (groupId == null || recipientGroupRepository == null) {
            return false;
        }
        return recipientGroupRepository.findByIdAndDeletedAtIsNull(groupId).isPresent();
    }

    /**
     * 確認通知設定 Entity → レスポンスDTO に変換する。
     */
    public abstract ConfirmableNotificationSettingsResponse toSettingsResponse(
            ConfirmableNotificationSettingsEntity entity);
}
