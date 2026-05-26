package com.mannschaft.app.bulletin.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * 保管庫フォルダ更新・移動リクエストDTO（設計書 F05.1 §4 PUT .../archive/folders/{folderId}）。
 *
 * <p>すべてのフィールドが任意。指定されたフィールドのみ更新する。
 * {@code parentFolderId} を指定するとフォルダ移動（サブツリーごと移動）になる。</p>
 *
 * <p>{@code parentFolderId} を「保管庫直下（ルート）へ移動」する意図で {@code null} を送りたい場合と、
 * 「親を変更しない」意図とを区別するため、{@link #isParentFolderIdPresent()} フラグで明示指定を判定する
 * （JSON に {@code parentFolderId} キーが存在したかどうかを Setter で記録する）。</p>
 */
@Getter
@NoArgsConstructor
public class UpdateArchiveFolderRequest {

    @Size(max = 100)
    @Setter
    private String name;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "color は #RRGGBB 形式で指定してください")
    @Setter
    private String color;

    @Size(max = 40)
    @Setter
    private String icon;

    @Setter
    private Integer displayOrder;

    /** 移動先の親フォルダ ID。NULL = ルートへ移動（{@code parentFolderIdPresent=true} の場合のみ移動扱い）。 */
    private UUID parentFolderId;

    /** JSON に {@code parentFolderId} キーが含まれていたか（移動操作かどうかの判定）。 */
    private boolean parentFolderIdPresent;

    public void setParentFolderId(UUID parentFolderId) {
        this.parentFolderId = parentFolderId;
        this.parentFolderIdPresent = true;
    }
}
