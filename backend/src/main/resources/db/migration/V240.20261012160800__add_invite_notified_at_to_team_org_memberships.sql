-- F01.2.1 2-D: 招待の受信通知（TEAM_ORG_INVITE_RECEIVED）を outbox に予約した瞬間を加盟行に残す。
-- 招待は INSERT のコミット後に組織の状態を読み直してから受信通知を予約する（2-C の C 案）ので、その間は
-- 招待されたチームがまだ招待を知らない。組織のアーカイブ・削除の片付けがその間に招待を消したとき、
-- 知らない招待の「取消」をチームへ送らないよう、この列が NULL の招待には取消の通知を出さない（§4.5）。
-- 時刻は UTC の壁時計（§4.6）。申請（TEAM_APPLY）は INSERT と同じ tx で受信通知を予約するので使わない（NULL のまま）。
ALTER TABLE team_org_memberships
    ADD COLUMN invite_notified_at DATETIME NULL
        COMMENT '招待の受信通知を outbox に予約した瞬間（UTC 壁時計）。NULL の招待は相手のチームがまだ知らない';

-- 既存の招待は、2-C の流れで受信通知の予約まで済んでいるものとして招待日時で埋める
UPDATE team_org_memberships
SET invite_notified_at = invited_at
WHERE direction = 'ORG_INVITE';
