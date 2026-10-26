# 組織・人数ヘッダー画像 manifest

統括が実見した選択画像の所在・size・SHAを記録する。原本は実在するlocal archiveへ保全済み。元worktreeのパスはprovenanceとして保持し、PNG/PIIはGitへ複製しない。

```json
{
  "schemaVersion": 1,
  "purpose": "ローカル原本の所在とSHAだけを保存する。画像本文・PII・raw system-outをGitへcopyしない。",
  "productionSourceHead": "1064ff0e7dca4513082ca83ddb7e877c5d2ad967",
  "records": [
    {
      "cmpId": "CMP-261010-1129",
      "mapLocalOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-org-photo-image-map.json",
      "mapSha256": "f0b51a304635ae5e46386e2595d18431657b24691b872ddf0a3d071c4467237c",
      "images": [
        {
          "id": "CMP-261010-1129-image-01",
          "attachmentName": "org-create-viewport-768",
          "localOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-org-photo-20261015T111337871133-html/data/1d77eb1bda6211ce0106f5c6aaf80de5cff7cab2.png",
          "sha256": "056c66595d102c6ea4664aa899d887eb7bef82241de95f6dad87027e70062589",
          "bytes": 101905,
          "viewport": 768,
          "captureKind": "viewport screenshot",
          "locale": "ja",
          "imageWidth": 768,
          "imageHeight": 1024,
          "gitImageCopied": false,
          "rootVisuallyReviewed": true,
          "rootReviewIdentitySource": "統括が引継記録の既視認IDを指定。原本map番号とcopy filenameを機械照合。",
          "originalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-org-photo-20261015T111337871133-html/data/1d77eb1bda6211ce0106f5c6aaf80de5cff7cab2.png"
        },
        {
          "id": "CMP-261010-1129-image-03",
          "attachmentName": "org-private-info-768",
          "localOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-org-photo-20261015T111337871133-html/data/21ae952dea8fe34b4141c454072e60f769f2b419.png",
          "sha256": "98189a358eaf65571229a0851fd0da43ab4a9a0b2b3412a123821a20d2c09c78",
          "bytes": 142903,
          "viewport": 768,
          "captureKind": "viewport screenshot",
          "locale": "ja",
          "imageWidth": 768,
          "imageHeight": 1024,
          "gitImageCopied": false,
          "rootVisuallyReviewed": true,
          "rootReviewIdentitySource": "統括が引継記録の既視認IDを指定。原本map番号とcopy filenameを機械照合。",
          "originalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-org-photo-20261015T111337871133-html/data/21ae952dea8fe34b4141c454072e60f769f2b419.png"
        }
      ],
      "recordedImageCount": 2,
      "captureKinds": {
        "viewport screenshot": 2
      },
      "rootVisualReview": {
        "reportedImageCount": 2,
        "selectedOriginalMapEntryNumbers": [
          1,
          3
        ],
        "allSelectedImagesReviewed": true,
        "allOriginalMapImagesClaimedReviewed": false,
        "selection": "root-specified stable photos only; transient spinner not adopted"
      },
      "originalMapImageCount": 3,
      "mapOriginalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-org-photo-image-map.json"
    },
    {
      "cmpId": "CMP-261010-1131",
      "mapLocalOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-header-image-map.json",
      "mapSha256": "05742426705fb9fcd832a2666425df71f41cad8f4ce180fa9faa07fc796b32e7",
      "images": [
        {
          "id": "CMP-261010-1131-image-04",
          "attachmentName": "organizations-768.png",
          "localOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-49883-68px幅の管理者ヘッダーで名前・ロール・人数が読める-chromium-real/organizations-768.png",
          "sha256": "58659df777ab20dbebce198ec5e358ea666dabceac26881cb7c545fcd60365bb",
          "bytes": 147496,
          "viewport": 768,
          "captureKind": "fullPage screenshot",
          "locale": "ja",
          "imageWidth": 768,
          "imageHeight": 1024,
          "gitImageCopied": false,
          "rootVisuallyReviewed": true,
          "rootReviewIdentitySource": "統括が引継記録の既視認IDを指定。原本map番号とcopy filenameを機械照合。",
          "originalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-49883-68px幅の管理者ヘッダーで名前・ロール・人数が読める-chromium-real/organizations-768.png"
        },
        {
          "id": "CMP-261010-1131-image-05",
          "attachmentName": "teams-768.png",
          "localOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-49883-68px幅の管理者ヘッダーで名前・ロール・人数が読める-chromium-real/teams-768.png",
          "sha256": "b918b8f5d6af4be80d60c1857079003d4f174656537a2df4bad9ee53bc2dc667",
          "bytes": 152697,
          "viewport": 768,
          "captureKind": "fullPage screenshot",
          "locale": "ja",
          "imageWidth": 768,
          "imageHeight": 1037,
          "gitImageCopied": false,
          "rootVisuallyReviewed": true,
          "rootReviewIdentitySource": "統括が引継記録の既視認IDを指定。原本map番号とcopy filenameを機械照合。",
          "originalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-49883-68px幅の管理者ヘッダーで名前・ロール・人数が読める-chromium-real/teams-768.png"
        },
        {
          "id": "CMP-261010-1131-image-07",
          "attachmentName": "Deutsch-organizations-360.png",
          "localOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/Deutsch-organizations-360.png",
          "sha256": "e09853c126e4b0af7f42fee34a4c154bcd5c31cb89d6a2408555b6b7a2048329",
          "bytes": 80120,
          "viewport": 360,
          "captureKind": "fullPage screenshot",
          "locale": "Deutsch",
          "imageWidth": 360,
          "imageHeight": 1153,
          "gitImageCopied": false,
          "rootVisuallyReviewed": true,
          "rootReviewIdentitySource": "統括が引継記録の既視認IDを指定。原本map番号とcopy filenameを機械照合。",
          "originalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/Deutsch-organizations-360.png"
        },
        {
          "id": "CMP-261010-1131-image-09",
          "attachmentName": "Deutsch-organizations-390.png",
          "localOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/Deutsch-organizations-390.png",
          "sha256": "5ed2420be768e7ff983efc7b943a1b1af53a27f4aa1bb6e89ae9aa10b0b62380",
          "bytes": 83176,
          "viewport": 390,
          "captureKind": "fullPage screenshot",
          "locale": "Deutsch",
          "imageWidth": 390,
          "imageHeight": 1121,
          "gitImageCopied": false,
          "rootVisuallyReviewed": true,
          "rootReviewIdentitySource": "統括が引継記録の既視認IDを指定。原本map番号とcopy filenameを機械照合。",
          "originalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/Deutsch-organizations-390.png"
        },
        {
          "id": "CMP-261010-1131-image-11",
          "attachmentName": "Deutsch-teams-360.png",
          "localOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/Deutsch-teams-360.png",
          "sha256": "f0be6bef6d0f7d4ecde2ea2433bc192ececa37ade4a8211a798a2493d4f7cb4b",
          "bytes": 79891,
          "viewport": 360,
          "captureKind": "fullPage screenshot",
          "locale": "Deutsch",
          "imageWidth": 360,
          "imageHeight": 1169,
          "gitImageCopied": false,
          "rootVisuallyReviewed": true,
          "rootReviewIdentitySource": "統括が引継記録の既視認IDを指定。原本map番号とcopy filenameを機械照合。",
          "originalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/Deutsch-teams-360.png"
        },
        {
          "id": "CMP-261010-1131-image-13",
          "attachmentName": "Deutsch-teams-390.png",
          "localOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/Deutsch-teams-390.png",
          "sha256": "ce2a7cee93181f692cb7fba91917eece8ad86f3d70ace005462aae97549ba24f",
          "bytes": 82701,
          "viewport": 390,
          "captureKind": "fullPage screenshot",
          "locale": "Deutsch",
          "imageWidth": 390,
          "imageHeight": 1137,
          "gitImageCopied": false,
          "rootVisuallyReviewed": true,
          "rootReviewIdentitySource": "統括が引継記録の既視認IDを指定。原本map番号とcopy filenameを機械照合。",
          "originalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/Deutsch-teams-390.png"
        },
        {
          "id": "CMP-261010-1131-image-15",
          "attachmentName": "English-organizations-360.png",
          "localOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/English-organizations-360.png",
          "sha256": "33010479a83c8194841a1f26ce2e97e9dc5fc6237ffddc57fa9a1688fd709fb7",
          "bytes": 75004,
          "viewport": 360,
          "captureKind": "fullPage screenshot",
          "locale": "English",
          "imageWidth": 360,
          "imageHeight": 1105,
          "gitImageCopied": false,
          "rootVisuallyReviewed": true,
          "rootReviewIdentitySource": "統括が引継記録の既視認IDを指定。原本map番号とcopy filenameを機械照合。",
          "originalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/English-organizations-360.png"
        },
        {
          "id": "CMP-261010-1131-image-17",
          "attachmentName": "English-organizations-390.png",
          "localOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/English-organizations-390.png",
          "sha256": "f98cd2e92481007c31eae08379e8b34841c65c24d2802c0001d47a59bd103ca5",
          "bytes": 78011,
          "viewport": 390,
          "captureKind": "fullPage screenshot",
          "locale": "English",
          "imageWidth": 390,
          "imageHeight": 1049,
          "gitImageCopied": false,
          "rootVisuallyReviewed": true,
          "rootReviewIdentitySource": "統括が引継記録の既視認IDを指定。原本map番号とcopy filenameを機械照合。",
          "originalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/English-organizations-390.png"
        },
        {
          "id": "CMP-261010-1131-image-19",
          "attachmentName": "English-teams-360.png",
          "localOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/English-teams-360.png",
          "sha256": "d272c516a0df9350eb2d48cf9f8b18ea584e016bd45da4dc54fadabb470aa1f8",
          "bytes": 74324,
          "viewport": 360,
          "captureKind": "fullPage screenshot",
          "locale": "English",
          "imageWidth": 360,
          "imageHeight": 1121,
          "gitImageCopied": false,
          "rootVisuallyReviewed": true,
          "rootReviewIdentitySource": "統括が引継記録の既視認IDを指定。原本map番号とcopy filenameを機械照合。",
          "originalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/English-teams-360.png"
        },
        {
          "id": "CMP-261010-1131-image-21",
          "attachmentName": "English-teams-390.png",
          "localOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/English-teams-390.png",
          "sha256": "96e7e3ebd61d96795387fd7c81af718a40db68984a667daa9243062512c1965e",
          "bytes": 77308,
          "viewport": 390,
          "captureKind": "fullPage screenshot",
          "locale": "English",
          "imageWidth": 390,
          "imageHeight": 1065,
          "gitImageCopied": false,
          "rootVisuallyReviewed": true,
          "rootReviewIdentitySource": "統括が引継記録の既視認IDを指定。原本map番号とcopy filenameを機械照合。",
          "originalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/English-teams-390.png"
        },
        {
          "id": "CMP-261010-1131-image-23",
          "attachmentName": "Español-organizations-360.png",
          "localOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/Español-organizations-360.png",
          "sha256": "ef1bbfa5375503eb821b5080956d56574ed16c6fb8f727054bca3158762102e9",
          "bytes": 80632,
          "viewport": 360,
          "captureKind": "fullPage screenshot",
          "locale": "Español",
          "imageWidth": 360,
          "imageHeight": 1153,
          "gitImageCopied": false,
          "rootVisuallyReviewed": true,
          "rootReviewIdentitySource": "統括が引継記録の既視認IDを指定。原本map番号とcopy filenameを機械照合。",
          "originalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/Español-organizations-360.png"
        },
        {
          "id": "CMP-261010-1131-image-25",
          "attachmentName": "Español-organizations-390.png",
          "localOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/Español-organizations-390.png",
          "sha256": "9223a9e0f5a61ab87694ee81b61ed411731fa779fd9617a049e5fdd6779a19a3",
          "bytes": 83922,
          "viewport": 390,
          "captureKind": "fullPage screenshot",
          "locale": "Español",
          "imageWidth": 390,
          "imageHeight": 1049,
          "gitImageCopied": false,
          "rootVisuallyReviewed": true,
          "rootReviewIdentitySource": "統括が引継記録の既視認IDを指定。原本map番号とcopy filenameを機械照合。",
          "originalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/Español-organizations-390.png"
        },
        {
          "id": "CMP-261010-1131-image-27",
          "attachmentName": "Español-teams-360.png",
          "localOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/Español-teams-360.png",
          "sha256": "5262dc5f31ec358e05572e34935aefd4fa10a50019070b4e43b4ea7680bd74b1",
          "bytes": 80412,
          "viewport": 360,
          "captureKind": "fullPage screenshot",
          "locale": "Español",
          "imageWidth": 360,
          "imageHeight": 1169,
          "gitImageCopied": false,
          "rootVisuallyReviewed": true,
          "rootReviewIdentitySource": "統括が引継記録の既視認IDを指定。原本map番号とcopy filenameを機械照合。",
          "originalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/Español-teams-360.png"
        },
        {
          "id": "CMP-261010-1131-image-29",
          "attachmentName": "Español-teams-390.png",
          "localOriginal": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/Español-teams-390.png",
          "sha256": "76cdaf87f9bd0951b5cf8a4d074c8d53a9df12aac7803fdb643ce7080b4e0b34",
          "bytes": 84031,
          "viewport": 390,
          "captureKind": "fullPage screenshot",
          "locale": "Español",
          "imageWidth": 390,
          "imageHeight": 1065,
          "gitImageCopied": false,
          "rootVisuallyReviewed": true,
          "rootReviewIdentitySource": "統括が引継記録の既視認IDを指定。原本map番号とcopy filenameを機械照合。",
          "originalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-header-20261015T110946313342-artifacts/real-fourth-header-canonic-71678-eのモバイルヘッダーで単複形と44pxの操作領域を保つ-chromium-real/Español-teams-390.png"
        }
      ],
      "recordedImageCount": 14,
      "captureKinds": {
        "fullPage screenshot": 14
      },
      "rootVisualReview": {
        "reportedImageCount": 14,
        "selectedOriginalMapEntryNumbers": [
          4,
          5,
          15,
          17,
          19,
          21,
          23,
          25,
          27,
          29,
          7,
          9,
          11,
          13
        ],
        "allSelectedImagesReviewed": true,
        "allOriginalMapImagesClaimedReviewed": false,
        "selection": "root-specified stable photos only; transient spinner not adopted"
      },
      "originalMapImageCount": 32,
      "mapOriginalProvenance": ".claude/worktrees/agent-orphans-entry-20261015/entry-fourth-header-image-map.json"
    }
  ],
  "archivePolicy": {
    "current": "原本を実在するlocal archiveへcopy-onlyで保全済み。全671件size/SHAと選択画像を再照合した。",
    "future": "外部アップロードや恒久公開は行っていない。アクセス制限付きlocal保全を維持し、作業木撤去は別手順で判断する。",
    "excluded": [
      "PNGのGit登録",
      "PII",
      "raw system-out",
      "API key/env",
      "tool transcript",
      "case3 Linux raw log/XML（JWTを含む可能性）"
    ],
    "archiveStatus": "created"
  },
  "archiveStatus": "created",
  "archive": {
    "localPath": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6",
    "manifestPath": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/archive-manifest.json",
    "manifestSha256": "818b5d0e650652fa38e694c1c9f170f2be6458b9014e816648fdd1ccec108078",
    "verifiedFileCount": 671,
    "verifiedBytes": 54023088,
    "candidateFingerprint": "d61dec106bac4d8f213b6f4ea187e56e6fa9a365b06bb621b7499e3663b40c02",
    "rootDirectories": {
      "entry": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry",
      "organization": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/organization",
      "plural": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/plural",
      "proof": ".claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/proof"
    },
    "operation": "exclusive verified copy only",
    "sourceCleanupPerformed": false
  }
}
```
