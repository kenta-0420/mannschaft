package com.mannschaft.app.auth.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 既存プロフィール更新と匿名化も出生確認の世代を無効化する。 */
class BirthProfileRevisionTest {
    private UserEntity profile() {
        return UserEntity.builder().lastName("山田").firstName("健太")
                .lastNameKana("ヤマダ").firstNameKana("ケンタ").birthDate("1990-01-22")
                .displayName("本人").build();
    }
    private void update(UserEntity u, String firstName, String kana, String displayName) {
        u.applyProfileUpdate(u.getLastName(),firstName,u.getLastNameKana(),kana,displayName,
                u.getNickname2(),u.getIsSearchable(),u.getAvatarUrl(),u.getPhoneNumber(),
                u.getPostalCode(),u.getLastNameHash(),u.getFirstNameHash(),u.getPhoneNumberHash(),
                u.getLocale(),u.getCountryCode(),u.getTimezone(),u.getDmReceiveFrom());
    }
    @Test @DisplayName("姓名・カナの実変更だけ出生世代を更新する")
    void 姓名カナの変更で世代更新() {
        UserEntity u=profile();
        update(u,"健太","ケンタ","表示名のみ");
        assertThat(u.getBirthProfileVersion()).isZero();
        update(u,"花子","ハナコ","表示名のみ");
        assertThat(u.getBirthProfileVersion()).isEqualTo(1L);
        update(u,"花子","ハナコ","表示名のみ");
        assertThat(u.getBirthProfileVersion()).isEqualTo(1L);
        update(u,"花子","はなこ","表示名のみ");
        assertThat(u.getBirthProfileVersion()).isEqualTo(2L);
    }
    @Test @DisplayName("匿名化は出生確認を無効化し、原入力を消去する")
    void 匿名化で世代更新() {
        UserEntity u=profile();u.anonymize();
        assertThat(u.getBirthProfileVersion()).isEqualTo(1L);
        assertThat(u.getBirthDate()).isNull();
        assertThat(u.getLastNameKana()).isNull();
        assertThat(u.getFirstNameKana()).isNull();
    }
}
