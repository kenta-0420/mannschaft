-- F06.2 photo_albums.cover_photo_id FK 追加（循環参照回避のため photos テーブル作成後に追加）
ALTER TABLE photo_albums
    ADD CONSTRAINT fk_pa_cover_photo FOREIGN KEY (cover_photo_id) REFERENCES photos (id) ON DELETE SET NULL;
