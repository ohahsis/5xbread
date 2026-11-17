// 화면에서 쓰는 API 타입. schema.d.ts(생성물)를 그대로 다시 내보내고, 명세가 정확히 표현하지 못하는 곳만 고친다.
//
// gen:api 는 --properties-required-by-default 로 생성한다. springdoc 은 응답 record 의 필드를 required 로 표시하지 않아,
// 그대로 생성하면 모든 필드가 optional(?)이 되어 화면 코드마다 undefined 처리가 붙는다.
// 대신 실제로 null 이 올 수 있는 필드는 아래에서 직접 `| null` 로 고친다 (백엔드 record 의 주석 기준).
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
export type SaleBread = Omit<Schemas['SaleBreadResponse'], 'perUserLimit' | 'myReservedQty'> & {
  perUserLimit: number | null
  myReservedQty: number | null
}

export type SaleBreads = Omit<Schemas['SaleBreadsResponse'], 'breads'> & { breads: SaleBread[] }

/** canceledAt: 취소하지 않았으면 null */
export type ReservationDetail = Omit<Schemas['ReservationDetailResponse'], 'canceledAt'> & {
  canceledAt: string | null
}

export type ReservationStatus = ReservationDetail['status']

/** nextCursor: 마지막 페이지면 null */
export type ReservationList = Omit<Schemas['ReservationListResponse'], 'items' | 'nextCursor'> & {
  items: ReservationDetail[]
  nextCursor: string | null
}

/**
 * POST /reservations 의 201 본문. 컨트롤러가 ResponseEntity<Object>(같은 키 재요청이면 저장해 둔 본문)라
 * 명세에는 object 로만 나온다. 실제 내용은 ReservationResponse 다.
 */
export type ReservationCreated = Schemas['ReservationResponse']
