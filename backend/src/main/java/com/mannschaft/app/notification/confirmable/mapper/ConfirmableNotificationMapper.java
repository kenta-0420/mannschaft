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
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

/**
 * F04.9 確認通知システムの Entity → DTO 変換マッパー（MapStruct）。
 *
 * <p><b>confirmedCount について</b>:
 * {@link ConfirmableNotificationResponse} および {@link ConfirmableNotificationDetailResponse} の
 * {@code confirmedCount} は Repository のカウントメソッドを使って Controller 側でセットすること。
 * MapStruct はそのフィールドを無視する（{@code ignore = true}）。</p>
 */
@Mapper(componentModel = "spring")
public interface ConfirmableNotificationMapper {

    /**
     * 確認通知 Entity → 一覧用レスポンスDTO に変換する。
     *
     * <p>confirmedCount は Controller 側で別途セットすること。</p>
     */
    @Mapping(target = "confirmedCount", ignore = true)
    ConfirmableNotificationResponse toResponse(ConfirmableNotificationEntity entity);

    /**
     * 確認通知エンティティリスト → 一覧用レスポンスDTOリストに変換する。
     *
     * <p>confirmedCount は Controller 側で個別にセットすること。</p>
     */
    @Mapping(target = "confirmedCount", ignore = true)
    List<ConfirmableNotificationResponse> toResponseList(List<ConfirmableNotificationEntity> entities);

    /**
     * 確認通知 Entity → 詳細レスポンスDTO に変換する。
     *
     * <p>createdBy は UserEntity の id にマッピングする。
     * confirmedCount は Controller 側で別途セットすること。</p>
     */
    @Mapping(target = "createdBy", source = "createdBy.id")
    @Mapping(target = "confirmedCount", ignore = true)
    ConfirmableNotificationDetailResponse toDetailResponse(ConfirmableNotificationEntity entity);

    /**
     * 確認通知受信者 Entity → レスポンスDTO に変換する（ADMIN+ 視点・全フィールド）。
     */
    @Named("toRecipientResponseFull")
    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "displayName", source = "user.displayName")
    @Mapping(target = "avatarUrl", source = "user.avatarUrl")
    ConfirmableNotificationRecipientResponse toRecipientResponse(
            ConfirmableNotificationRecipientEntity entity);

    /**
     * 確認通知受信者エンティティリスト → レスポンスDTOリストに変換する（ADMIN+ 視点）。
     */
    @IterableMapping(qualifiedByName = "toRecipientResponseFull")
    List<ConfirmableNotificationRecipientResponse> toRecipientResponseList(
            List<ConfirmableNotificationRecipientEntity> entities);

    /**
     * 確認通知受信者 Entity → 公開（MEMBER 視点）DTO に変換する。
     *
     * <p>F04.9 Phase D（{@code unconfirmed_visibility = ALL_MEMBERS}）でメンバーがアクセスした場合に使用する。
     * 未確認者の存在を可視化するが、確認状態の詳細（confirmedAt/confirmedVia/excludedAt）はマスクして返す。</p>
     *
     * <p>本メソッドが返すのは未確認者のみという前提で呼び出すこと（フィルタは Service 層で実施）。</p>
     */
    @Named("toRecipientResponsePublic")
    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "displayName", source = "user.displayName")
    @Mapping(target = "avatarUrl", source = "user.avatarUrl")
    @Mapping(target = "confirmedAt", ignore = true)
    @Mapping(target = "confirmedVia", ignore = true)
    @Mapping(target = "excludedAt", ignore = true)
    ConfirmableNotificationRecipientResponse toRecipientPublicResponse(
            ConfirmableNotificationRecipientEntity entity);

    /**
     * 確認通知受信者エンティティリスト → 公開（MEMBER 視点）DTO リストに変換する。
     *
     * <p>F04.9 Phase D の MEMBER 視点用。confirmedAt / confirmedVia / excludedAt はマスク。</p>
     */
    @IterableMapping(qualifiedByName = "toRecipientResponsePublic")
    List<ConfirmableNotificationRecipientResponse> toRecipientPublicResponseList(
            List<ConfirmableNotificationRecipientEntity> entities);

    /**
     * 確認通知テンプレート Entity → レスポンスDTO に変換する。
     */
    ConfirmableNotificationTemplateResponse toTemplateResponse(
            ConfirmableNotificationTemplateEntity entity);

    /**
     * 確認通知テンプレートエンティティリスト → レスポンスDTOリストに変換する。
     */
    List<ConfirmableNotificationTemplateResponse> toTemplateResponseList(
            List<ConfirmableNotificationTemplateEntity> entities);

    /**
     * 確認通知設定 Entity → レスポンスDTO に変換する。
     */
    ConfirmableNotificationSettingsResponse toSettingsResponse(
            ConfirmableNotificationSettingsEntity entity);
}
