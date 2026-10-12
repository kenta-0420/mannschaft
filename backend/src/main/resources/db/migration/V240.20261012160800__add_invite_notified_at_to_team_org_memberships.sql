-- F01.2.1 2-D: 招待の受信通知（TEAM_ORG_INVITE_RECEIVED）を outbox に予約した瞬間を加盟行に残す。
-- 招待は INSERT のコミット後に組織の状態を読み直してから受信通知を予約する（2-C の C 案）ので、その間は
-- 招待されたチームがまだ招待を知らない。組織のアーカイブ・削除の片付けがその間に招待を消したとき、
-- 知らない招待の「取消」をチームへ送らないよう、この列が NULL の招待には取消の通知を出さない（§4.5）。
-- 時刻は UTC の壁時計（§4.6）。申請（TEAM_APPLY）は INSERT と同じ tx で受信通知を予約するので使わない（NULL のまま）。
ALTER TABLE team_org_memberships
    ADD COLUMN invite_notified_at DATETIME NULL
        COMMENT '招待の受信通知を outbox に予約した瞬間（UTC 壁時計）。NULL の招待は相手のチームがまだ知らない';

-- 既存の招待の補完は、受信通知を予約した根拠がある行に限る（無条件に埋めると、受信通知の予約の前に止まった招待まで
-- 「チームが知っている」扱いになり、片付けで知らない招待の取消を送ってしまう）。
-- 根拠: 受信通知は team_notification_outbox に notification_type = 'TEAM_ORG_INVITE_RECEIVED' の行として予約され、
-- その payload_json の $.fanout.sourceId が加盟行の ID である（OutboxTeamAffiliationNotifier。冪等キーも同じ ID から作る）。
-- その行がある招待だけ、最初の予約の時刻（outbox の created_at。UTC 壁時計）で埋める。
-- 根拠の無い行（outbox の行が無い・既に掃除された・outbox 導入前に予約された招待）は NULL のまま残し、取消を送らない側に倒す
-- （本番に既存データは無い前提。誤って取消を送るより、送らないほうが害が小さい）。
UPDATE team_org_memberships m
SET m.invite_notified_at = (
        SELECT MIN(o.created_at)
        FROM team_notification_outbox o
        WHERE o.notification_type = 'TEAM_ORG_INVITE_RECEIVED'
          AND JSON_EXTRACT(o.payload_json, '$.fanout.sourceId') = m.id)
WHERE m.direction = 'ORG_INVITE'
  AND EXISTS (
        SELECT 1
        FROM team_notification_outbox o
        WHERE o.notification_type = 'TEAM_ORG_INVITE_RECEIVED'
          AND JSON_EXTRACT(o.payload_json, '$.fanout.sourceId') = m.id);
