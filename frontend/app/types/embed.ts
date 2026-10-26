export interface EmbedBracketData {
  rounds: Array<{
    name: string
    matches: Array<{
      id: number
      homeTeam: string | null
      awayTeam: string | null
      homeScore: number | null
      awayScore: number | null
      status: string
    }>
  }>
}

export interface EmbedRankingData {
  statKey: string
  rankings: Array<{
    rank: number
    playerName: string
    teamName: string | null
    value: number
  }>
}

export interface EmbedStandingData {
  divisionName: string
  standings: Array<{
    rank: number
    teamName: string
    wins: number
    losses: number
    draws: number
    points: number
    goalDifference: number
  }>
}
