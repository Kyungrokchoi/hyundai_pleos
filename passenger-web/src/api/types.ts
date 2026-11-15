

import type { components } from "./schema";

type S = components["schemas"];

export type TripSummary = S["TripSummary"];
export type SettlementOut = S["SettlementOut"];
export type ShareOut = S["ShareOut"];
export type SegmentOut = S["SegmentOut"];
export type ParticipantOut = S["ParticipantOut"];
export type IntervalOut = S["IntervalOut"];
export type CostRuleOut = S["CostRuleOut"];
export type CostItemOut = S["CostItemOut"];
export type LatestOdometerOut = S["LatestOdometerOut"];
export type Issue = S["Issue"];

export type AuthOut = S["AuthOut"];
export type GroupOut = S["GroupOut"];
export type MembershipOut = S["MembershipOut"];
export type MemberOut = S["MemberOut"];
export type InvitePreviewOut = S["InvitePreviewOut"];
export type InviteAcceptIn = S["InviteAcceptIn"];
export type InviteAcceptOut = S["InviteAcceptOut"];
export type BoardingIn = S["BoardingIn"];
export type ApprovalIn = S["ApprovalIn"];

export type CharacterOut = S["CharacterOut"];
export type BadgeOut = S["BadgeOut"];
export type HistoryItem = S["HistoryItem"];
export type CouponOut = S["CouponOut"];

export type TripState = TripSummary["state"];
export type DataSource = TripSummary["data_mode"];
export type MemberStatus = MemberOut["status"];
export type Quality = IntervalOut["boundary_quality"];
export type ItemKind = CostItemOut["kind"];
export type Allocation = CostItemOut["allocation"];
export type EnergyPolicy = SettlementOut["energy_policy"];
export type SettlementState = SettlementOut["state"];
export type ApprovalState = ShareOut["approval"];
export type Decision = ApprovalIn["decision"];
