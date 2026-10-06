// 契約追補準拠の暫定手動型。OpenAPI統合時に生成型へ移行する。
import type { components } from '~/types/generated'
export type AssignmentResult = components['schemas']['AssignmentResult']
export type Decimal = string
export type RanchStage = 'EGG' | 'BABY' | 'JUVENILE' | 'ADULT'
export type RenderStyle = 'PIXEL' | 'PAINT_2D'
export type MotionMode = 'NORMAL' | 'REDUCED' | 'STOPPED'
export type Habitat = 'LAND' | 'SEA' | 'AIR'
export interface DinosaurSummary {
 id: string; speciesKey: string | null; variantKey: string | null; habitat: Habitat | null;
 stage: RanchStage; name: string | null; xp: Decimal; nextStageXp: Decimal | null;
 affinityBand?: 'NEUTRAL' | 'WARM' | 'CLOSE';
 version: Decimal; namedAt: string | null; speciesCatalogVersion: string | null; egg: { startedAt: string; readyAt: string; crackStage: 'INTACT' | 'SMALL_CRACK' | 'WIDE_CRACK' | 'READY'; hatchReady: boolean; hatchedAt: string | null } | null
}
export interface RanchSettings {
 isVisible: boolean; viewMode: 'ROOM'; renderStyle: RenderStyle; motionMode: MotionMode;
 isSoundEnabled: boolean; soundVolume: number; version: Decimal
}
export interface RanchSlot { slotKey: 'SHELF_1' | 'SHELF_2' | 'SHELF_3'; inventoryId: string | null; version: Decimal; decoration?: { collectibleKey: string; labelKey: string; assetKey: string } | null }
export interface OwnerSummary { id: string; status: 'ACTIVE' | 'PAUSED'; balance: Decimal; version: Decimal }
export interface RanchState {
 featureStatus: 'AVAILABLE' | 'UNAVAILABLE'; deliveryPaused: boolean;
 rewardsStatus: 'ENABLED' | 'DISABLED' | 'PAUSED'; shopAvailable: boolean;
 owner: OwnerSummary | null;
 dinosaur: DinosaurSummary | null; settings: RanchSettings | null;
 roomSlots: RanchSlot[]; serverTime: string; policyVersion: Decimal | null;
 careBudget: { weekStartsOn: string; remainingXp: Decimal; weeklyCapXp: Decimal; awardedXp: Decimal; amountXp: Decimal; weekEndsAt: string; ruleVersion: string } | null;
 weekBudget: { remaining: Decimal; personalRequiredCount: Decimal; personalCompletedCount: number } | null;
 assignment: { availableMethods: ('HABITAT_RANDOM' | 'DIAGNOSIS' | 'BIRTH_STYLE')[]; selectionConfirmed: boolean; confirmedMethod: string | null } | null
}
export interface FeedingResult { commandId: string; dinosaurId: string; gainedXp: Decimal; isGrowthCapped: boolean; stageAfter: RanchStage; costPoints: Decimal; completedAt: string }
export interface InteractionResult { commandId: string; dinosaurId: string; reactionKey: string; affinityBand: string; affinityChanged: boolean; completedAt: string }
export interface HatchResult { commandId: string; dinosaurId: string; name: string; stage: RanchStage; namedAt: string; hatchedAt: string; version: Decimal }
export type HatchResponse = { kind: 'HATCH_RESULT'; result: HatchResult; state: null } | { kind: 'CURRENT_STATE'; result: null; state: RanchState }
export type AssignmentRequest = { method: 'HABITAT_RANDOM'; habitat: Habitat; version: Decimal } | { method: 'DIAGNOSIS'; resultId: string; version: Decimal } | { method: 'BIRTH_STYLE'; resultId: string; confirmationRef: string; version: Decimal }
export interface RanchInventory { id: string; collectibleKey: string; labelKey: string; assetKey: string; isRevoked: boolean; placedSlotKey: string | null }
export interface RanchLegacySyncResult { commandId: string; nextAfterAwardId: Decimal; processedCount: number; importedCount: number; hasNext: boolean; completedAt: string }
export interface ShopItem { skuKey: string; collectibleKey: string; labelKey: string; pricePoints: Decimal; priceVersion: Decimal; isOwned: boolean }
export interface RanchRecord { id: string; kind: string; sourceType: string | null; deltaPoints: Decimal; deltaXp: Decimal; occurredAt: string; sourceLink: { kind: string; id: string; url: string } | null }
export interface CursorPage<T> { data: T[]; meta: { nextCursor: string | null; hasNext: boolean; limit: number } }
export interface DiagnosisQuestion { id: string; axis: string; polarity: number; text: Record<string,string> }
export interface TieQuestion { axisId: string; zero: Record<string,string>; one: Record<string,string> }
export interface DiagnosisSession {
 id: string; status: 'STARTED' | 'TIE_BREAK_REQUIRED' | 'COMPLETED' | 'CANCELLED'; version: Decimal; answerRevision: Decimal;
 questionnaireVersion: string; scoringVersion: string; questions: DiagnosisQuestion[];
 answers: { questionId: string; value: number }[]; tieQuestions: TieQuestion[]; resultId: string | null
}
export interface DiagnosisResult {
 id: string
 method: 'DIAGNOSIS' | 'BIRTH_STYLE'
 completedAt: string
 resultSchemaVersion: string
 questionnaireVersion?: string | null
 scoringVersion?: string | null
 normalizationVersion?: string | null
 ruleVersion?: string | null
 mappingVersion?: string | null
 typeCode?: string | null
 axes?: Record<string, number> | null
 numberSummary?: { lifePathNumber: number; nameNumber: number; dateSum: number; nameSum: number } | null
 descriptionSnapshot: Record<string, string>
 axisDescriptions?: Record<string, Record<string, string>> | null
}
export interface BirthProfile { lastName: string | null; firstName: string | null; lastNameKana: string | null; firstNameKana: string | null; birthDate: string | null; revision: Decimal }
export interface BirthConfirmation { confirmationRef: string; expiresAt: string; profileRevision: Decimal }
