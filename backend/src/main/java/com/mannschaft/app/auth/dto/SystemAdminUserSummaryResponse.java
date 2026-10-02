package com.mannschaft.app.auth.dto;

import com.mannschaft.app.auth.entity.UserEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * システム管理ダッシュボード「全ユーザー一覧」レスポンス DTO（auth ドメイン所有）。
 *
 * <p>{@link UserEntity} 直返しを廃し、システム管理画面で必要な項目のみを<b>許可リスト方式</b>で
 * 明示的に返す。PII（氏名・カナ・電話番号・郵便番号・生年月日・性別・地域コード）、各種ブラインド
 * インデックスハッシュ、{@code passwordHash}、内部運用フラグ（暗号鍵バージョン・通報制限・物理削除日時）は
 * 一切含めない。フィールド名は Entity のシリアライズ名と一致させ、フロントエンドを無風化する。</p>
 *
 * <p><b>配置理由（ドメイン境界の原則）</b>: 本 DTO と対応する {@code SystemAdminUserSummaryMapper} は
 * {@link UserEntity}（auth ドメイン）を参照するため、auth ドメイン内に配置する。admin ドメインへ
 * 置くと D-1 クロスドメイン Entity 参照違反となるため、所有権を auth ドメインに置く設計とした。</p>
 */
@Getter
@Builder
public class SystemAdminUserSummaryResponse {

    private final Long id;
    private final String email;
    private final String displayName;
    private final String contactHandle;
    private final UserEntity.UserStatus status;
    private final String locale;
    private final String timezone;
    private final LocalDateTime lastLoginAt;
    private final LocalDateTime archivedAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
