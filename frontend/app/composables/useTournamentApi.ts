// 後方互換のため、全サブcomposableをマージして返す
// 新規コードは tournament/ サブディレクトリの各composableを直接インポートしてください
import { useTournamentBase } from './tournament/useTournamentBase'
import { useTournamentBracket } from './tournament/useTournamentBracket'
import { useTournamentParticipants } from './tournament/useTournamentParticipants'

export { useTournamentBase }
export { useTournamentBracket }
export { useTournamentParticipants }
export { useTournamentFee } from './tournament/useTournamentFee'
// F08.7 順位UI Wave B-3: スコアキーパー指名管理。composables/tournament は auto-import
// 対象 dir に含まれないため、top-level の本ファイルから re-export して自動インポート可能にする。
export { useTournamentScorekeepers } from './tournament/useTournamentScorekeepers'

export function useTournamentApi() {
  return {
    ...useTournamentBase(),
    ...useTournamentBracket(),
    ...useTournamentParticipants(),
  }
}
