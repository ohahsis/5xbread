package com.fivexbread.saledate;

import static com.fivexbread.saledate.StockRowError.Reason.*;

import com.fivexbread.catalog.Bread;
import com.fivexbread.catalog.BreadRepository;
import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매일별 수량 조회·일괄 저장 (A-02, R-01·R-07, 13-8 2·3단계). DB 만 다룬다. Redis 반영은 StockFacade 가 트랜잭션 밖에서 한다.
 *
 * 저장 순서 (한 트랜잭션):
 * 1. If-Match 버전이 현재와 다르면 412 (검사 전에 빨리 끝냄)
 * 2. 모든 행 검사 → 틀린 행이 하나라도 있으면 전부 모아 422, 아무것도 바꾸지 않음
 * 3. sale_date 버전 조건부 +1 → 0행이면 다른 저장이 먼저 커밋한 것 → 412. 이 UPDATE 가 행을 잠가 동시 저장이 한 줄로 선다
 * 4. 행별 UPDATE(총량은 예약 수량 이상일 때만) → 0행이 하나라도 있으면 전체 롤백 후 422 STOCK_BELOW_RESERVED
 * 요청에 없는 빵의 행은 그대로 둔다.
 */
@Service
@RequiredArgsConstructor
public class StockService {

    static final int MAX_PRODUCED = 500;
    static final int MIN_PER_USER_LIMIT = 1;
    static final int MAX_PER_USER_LIMIT = 20;

    private final SaleDateRepository saleDateRepository;
    private final DailyStockRepository dailyStockRepository;
    private final BreadRepository breadRepository;

    public record Result(StocksResponse stocks, String eTag) {
    }

    @Transactional(readOnly = true)
    public Result get(LocalDate date) {
        SaleDate saleDate = findSaleDate(date);
        return new Result(view(date), saleDate.eTag());
    }

    /**
     * 기존 행의 예약 가능 총량 변화. 저장 전에 트랜잭션 밖(StockFacade)에서 Redis 를 먼저 줄이려고 같은 검사를 미리 한다.
     * 버전이 같으면 그사이 다른 수량 저장이 없었으므로, 저장 트랜잭션에서도 같은 변화가 된다.
     */
    @Transactional(readOnly = true)
    public List<ReservableChange> reservableChanges(LocalDate date, int expectedVersion, List<StockRowRequest> rows) {
        Map<Long, DailyStock> stocks = checkRows(date, expectedVersion, rows);
        List<ReservableChange> changes = new ArrayList<>();
        for (StockRowRequest row : rows) {
            DailyStock stock = stocks.get(row.breadId());
            if (stock != null && stock.getReservableQty() != row.reservableQty()) {
                changes.add(new ReservableChange(row.breadId(), stock.getReservableQty(), row.reservableQty()));
            }
        }
        return changes;
    }

    /** @param before 저장 전 예약 가능 총량, after 저장할 총량 */
    public record ReservableChange(long breadId, int before, int after) {

        public int delta() {
            return after - before;
        }
    }

    @Transactional
    public Result put(LocalDate date, int expectedVersion, List<StockRowRequest> rows) {
        Map<Long, DailyStock> stocks = checkRows(date, expectedVersion, rows);

        if (saleDateRepository.increaseVersion(date, expectedVersion) == 0) {
            throw new BusinessException(ErrorCode.PRECONDITION_FAILED);
        }
        List<Long> belowReserved = new ArrayList<>();
        for (StockRowRequest row : rows) {
            DailyStock stock = stocks.get(row.breadId());
            if (stock == null) {
                dailyStockRepository.save(DailyStock.create(date, row.breadId(), row.producedQty(),
                        row.reservableQty(), row.perUserLimit()));
            } else if (dailyStockRepository.updateIfNotBelowReserved(stock.getId(), row.producedQty(),
                    row.reservableQty(), row.perUserLimit()) == 0) {
                belowReserved.add(stock.getId());
            }
        }
        if (!belowReserved.isEmpty()) {
            // 2단계 검사 뒤 그 사이 예약이 들어와 예약 수량이 새 총량을 넘은 경우. 예외로 버전 증가까지 모두 롤백된다
            throw invalidRows(dailyStockRepository.findCurrentReserved(belowReserved).stream()
                    .map(r -> new StockRowError(((Number) r[0]).longValue(), "reservableQty", STOCK_BELOW_RESERVED,
                            ((Number) r[1]).intValue()))
                    .toList());
        }
        return new Result(view(date), SaleDate.eTagOf(expectedVersion + 1));
    }

    /** 404 → 휴무일 422 → 버전 412 → 행 검사 422. 통과하면 이 날의 현재 수량 행(breadId → 행) */
    private Map<Long, DailyStock> checkRows(LocalDate date, int expectedVersion, List<StockRowRequest> rows) {
        SaleDate saleDate = findSaleDate(date);
        if (saleDate.isHoliday()) {
            throw new BusinessException(ErrorCode.SALE_DATE_HOLIDAY);
        }
        if (saleDate.getVersion() != expectedVersion) {
            throw new BusinessException(ErrorCode.PRECONDITION_FAILED);
        }

        Map<Long, DailyStock> stocks = dailyStockRepository.findBySaleDate(date).stream()
                .collect(Collectors.toMap(DailyStock::getBreadId, Function.identity()));
        Map<Long, Bread> breads = breadRepository.findAllById(rows.stream().map(StockRowRequest::breadId).toList())
                .stream().collect(Collectors.toMap(Bread::getId, Function.identity()));
        List<StockRowError> errors = validate(rows, stocks, breads);
        if (!errors.isEmpty()) {
            throw invalidRows(errors);
        }
        return stocks;
    }

    // 첫 오류에서 멈추지 않고 모든 행의 모든 위반을 모은다 (13-8 어필 포인트)
    private static List<StockRowError> validate(List<StockRowRequest> rows, Map<Long, DailyStock> stocks,
                                                Map<Long, Bread> breads) {
        List<StockRowError> errors = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (StockRowRequest row : rows) {
            Long breadId = row.breadId();
            if (!seen.add(breadId)) {
                errors.add(StockRowError.of(breadId, "breadId", DUPLICATE_BREAD));
                continue;
            }
            Bread bread = breads.get(breadId);
            DailyStock stock = stocks.get(breadId);
            if (bread == null) {
                errors.add(StockRowError.of(breadId, "breadId", BREAD_NOT_FOUND));
                continue;
            }
            if (!bread.isActive() && stock == null) {
                errors.add(StockRowError.of(breadId, "breadId", BREAD_INACTIVE));
            }
            if (row.producedQty() < 0 || row.producedQty() > MAX_PRODUCED) {
                errors.add(StockRowError.of(breadId, "producedQty", OUT_OF_RANGE));
            }
            if (row.reservableQty() < 0) {
                errors.add(StockRowError.of(breadId, "reservableQty", OUT_OF_RANGE));
            } else if (row.reservableQty() > row.producedQty()) {
                errors.add(StockRowError.of(breadId, "reservableQty", EXCEEDS_PRODUCED));
            }
            if (stock != null && row.reservableQty() < stock.getReservedQty()) {
                errors.add(new StockRowError(breadId, "reservableQty", STOCK_BELOW_RESERVED, stock.getReservedQty()));
            }
            Integer limit = row.perUserLimit();
            if (limit != null && (limit < MIN_PER_USER_LIMIT || limit > MAX_PER_USER_LIMIT)) {
                errors.add(StockRowError.of(breadId, "perUserLimit", OUT_OF_RANGE));
            }
        }
        return errors;
    }

    private StocksResponse view(LocalDate date) {
        Map<Long, DailyStock> stocks = dailyStockRepository.findBySaleDate(date).stream()
                .collect(Collectors.toMap(DailyStock::getBreadId, Function.identity()));
        List<StockRowResponse> rows = breadRepository.findAll().stream()
                .filter(bread -> bread.isActive() || stocks.containsKey(bread.getId()))
                .sorted(Comparator.comparingInt(Bread::getDisplayOrder).thenComparing(Bread::getName))
                .map(bread -> row(bread, stocks.get(bread.getId())))
                .toList();
        return new StocksResponse(date, rows);
    }

    private static StockRowResponse row(Bread bread, DailyStock stock) {
        if (stock == null) {
            return new StockRowResponse(bread.getId(), bread.getName(), bread.isActive(), false, 0, 0, 0, null);
        }
        return new StockRowResponse(bread.getId(), bread.getName(), bread.isActive(), true, stock.getProducedQty(),
                stock.getReservableQty(), stock.getReservedQty(), stock.getPerUserLimit());
    }

    private SaleDate findSaleDate(LocalDate date) {
        return saleDateRepository.findById(date).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    static BusinessException invalidRows(List<StockRowError> errors) {
        return new BusinessException(ErrorCode.INVALID_STOCK_ROWS, null, Map.of("errors", errors));
    }
}
