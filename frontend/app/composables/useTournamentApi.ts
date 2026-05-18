// 後方互換のため、全サブcomposableをマージして返す
// 新規コードは tournament/ サブディレクトリの各composableを直接インポートしてください
import { useTournamentBase } from './tournament/useTournamentBase'
import { useTournamentBracket } from './tournament/useTournamentBracket'
import { useTournamentParticipants } from './tournament/useTournamentParticipants'

export { useTournamentBase }
export { useTournamentBracket }
export { useTournamentParticipants }

export function useTournamentApi() {
  return {
    ...useTournamentBase(),
    ...useTournamentBracket(),
    ...useTournamentParticipants(),
  }
}
