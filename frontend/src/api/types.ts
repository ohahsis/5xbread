// 화면에서 쓰는 API 타입. schema.d.ts(생성물)를 그대로 다시 내보낸다.
//
// gen:api 는 --properties-required-by-default 로 생성한다. springdoc 은 응답 record 의 필드를 required 로 표시하지 않아,
// 그대로 생성하면 모든 필드가 optional(?)이 되어 화면 코드마다 undefined 처리가 붙는다.
// null 이 올 수 있는 필드는 백엔드 명세가 nullable 로 표시하므로 생성 타입에 `| null` 로 들어온다.
import type { components } from './schema'

type Schemas = components['schemas']

export type SaleDateStatus = Schemas['SaleDateStatusResponse']
export type PickupSlot = Schemas['PickupSlotResponse']
export type AccessTokenResponse = Schemas['AccessTokenResponse']
export type LoginRequest = Schemas['LoginRequest']
export type SignupRequest = Schemas['SignupRequest']
export type ReservationCreateRequest = Schemas['ReservationCreateRequest']
export type ReservationItem = Schemas['ItemResponse']
export type PickupCode = Schemas['PickupCodeResponse']

/** perUserLimit: null 이면 한도 없음 / myReservedQty: 로그인하지 않았으면 null */
export type SaleBread = Schemas['SaleBreadResponse']
export type SaleBreads = Schemas['SaleBreadsResponse']

/** canceledAt: 취소하지 않았으면 null */
export type ReservationDetail = Schemas['ReservationDetailResponse']
export type ReservationStatus = ReservationDetail['status']

/** nextCursor: 마지막 페이지면 null */
export type ReservationList = Schemas['ReservationListResponse']

/** POST /reservations 의 201 본문 (같은 Idempotency-Key 재요청도 같은 본문) */
export type ReservationCreated = Schemas['ReservationResponse']
