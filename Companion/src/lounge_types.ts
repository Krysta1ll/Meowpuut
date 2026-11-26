// Shared TypeScript types for the pure-game cat lounge, writing pad, sync inspector, and 82-achievement wall.

export type PetForm = "3d" | "pixel";
export type CatMood = "dilated" | "sparkle" | "happy" | "purr" | "peek";
export type AchievementTier = "normal" | "hard" | "very_hard" | "legend" | "local";
export type ExpeditionTier = "onboarding" | "stroll" | "adventure";

export interface PlaceDef {
  id: string;
  bit: number;
  name: [string, string];
  vibe: [string, string];
  alwaysOpen: boolean;
}

export interface CurioDef {
  id: string;
  name: [string, string];
  flavor: [string, string];
  rarity: "common" | "uncommon" | "rare" | "legend";
  icon: string;
  placeBit: number | null;
}

export interface FurnitureDef {
  id: string;
  bit: number;
  slot: "window" | "left" | "floor" | "right" | "wall";
  price: number;
  p0Shop: boolean;
  unlockCurios: number;
  unlockPostcards: number;
  name: [string, string];
  desc: [string, string];
  icon: string;
}

export interface TitleDef {
  id: string;
  name: [string, string];
  hint: [string, string];
  badge: string;
}

export interface ToyDef {
  id: string;
  name: [string, string];
  desc: [string, string];
  icon: string;
}

export interface StoryView {
  id: string;
  cat: "mimimo" | "momo";
  level: number;
  title: [string, string];
  unlocked: boolean;
  body: [string, string] | null;
}

export interface StarStoryView {
  id: string;
  star: number;
  title: [string, string];
  unlocked: boolean;
  body: [string, string] | null;
}

export interface ParagonInfo {
  bondMimimo: number;
  bondMomo: number;
  maxBondReached: boolean;
  paragonLevel: number;
  stardustPips: number;
  nextStarPct: number;
  nextStarNeed: number;
}

export interface ExpeditionSlot {
  placeId: string;
  placeBit: number;
  tier: ExpeditionTier;
  walkedChars: number;
  needChars: number;
}

export interface LoungeState {
  walletEpoch: number;
  seenMeowChars: number;
  kibbleRemainder: number;
  kibbleBalance: number;
  ownedFurniture: number;
  starterGiftClaimed: boolean;
  petForm: PetForm;
  curios: string[];
  curioDupes: number;
  roomSlots: Record<string, string>;
  equippedTitle: string;
  expeditionCount: number;
  expeditionPity: number;
  onboardingExpeditionDone: boolean;
  restedBonusChars: number;
  expeditionSlots: (ExpeditionSlot | null)[];
  focusCompleted: number;
  snackVouchers: number;
  toyPlays: Record<string, number>;
  pattedMimimoLocal: number;
  pattedMomoLocal: number;
  petBucket: number;
  petBucketMs: number;
  seed: number;
}

export interface LoungeView {
  state: LoungeState;
  availableKibble: number;
  totalChars: number;
  placesMask: number;
  unlockedSlots: number;
  paragon: ParagonInfo;
  places: PlaceDef[];
  curios: CurioDef[];
  furniture: FurnitureDef[];
  titles: TitleDef[];
  toys: ToyDef[];
  unlockedTitles: string[];
  stories: StoryView[];
  starStories: StarStoryView[];
}

export interface ToyReaction {
  toyId: string;
  cat: "mimimo" | "momo" | "both";
  mood: CatMood;
  drama: string;
  dialogue: [string, string];
  throttled: boolean;
  kibbleGained: number;
}

export interface ExpeditionOutcome {
  slot: number;
  placeId: string;
  placeName: [string, string];
  tier: ExpeditionTier;
  curios: CurioDef[];
  newCount: number;
  pityTriggers: number;
  pityNow: number;
  allCollected: boolean;
  rewardKibble: number;
  kibbleBalance: number;
  dialogue: [string, string];
}

export interface InteractResponse {
  reaction: ToyReaction;
  completed: ExpeditionOutcome[];
  view: LoungeView;
}

export interface SettleLoungeResponse {
  completed: ExpeditionOutcome[];
  view: LoungeView;
}

export interface FocusOutcome {
  minutes: number;
  naturalMeowChars: number;
  snackVouchers: number;
  focusCompleted: number;
  dialogue: [string, string];
}

export interface FocusResponse {
  outcome: FocusOutcome;
  view: LoungeView;
}

export interface PadCommitOutcome {
  accepted: number;
  dailyTotal: number;
  totalChars: number;
}

export interface SnapshotField {
  key: string;
  label: [string, string];
  value: string;
  bytes: number;
}

export interface SyncInspection {
  formatVersion: number;
  formatBytes: number;
  epoch: number;
  totalGraphemes: number;
  replicaCount: number;
  headerHex: string;
  privacyNote: [string, string];
  fields: SnapshotField[];
}

export interface AchievementCatalogItem {
  api: string;
  name: [string, string];
  desc: [string, string];
  hidden: boolean;
  steam: boolean;
  enabled: boolean;
  unlocked: boolean;
  tier: AchievementTier;
  index: number;
}
