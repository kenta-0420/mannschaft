package com.mannschaft.app.weather.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * ユーザーの郵便番号 / 国コードが更新されたことを通知するイベント（F02.10）。
 *
 * <p>発火元: {@code UserService#updateProfile} および {@code AuthService#register}
 * （実装は第四陣で追加予定）。本イベントを受けて
 * {@link WeatherLocationEventListener} が非同期で座標を再導出する。</p>
 *
 * <p>イベントペイロードはユーザー ID のみ。詳細情報（旧値・新値）は持たない。
 * リスナー側で {@code UserRepository} から最新値を引いて処理する。</p>
 */
@Getter
public class UserPostalCodeUpdatedEvent extends BaseEvent {

    private final Long userId;

    public UserPostalCodeUpdatedEvent(Long userId) {
        super();
        this.userId = userId;
    }
}
