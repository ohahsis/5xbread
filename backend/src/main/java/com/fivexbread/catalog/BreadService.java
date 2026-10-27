package com.fivexbread.catalog;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 빵 등록·수정·판매 중지 (A-01, 13-12).
 * 이름 중복은 먼저 조회로 알려 주고, 동시에 같은 이름이 들어온 경우는 uk_bread_active_name 이 막는다.
 */
@Service
@RequiredArgsConstructor
public class BreadService {

    private final BreadRepository breadRepository;

    @Transactional(readOnly = true)
    public List<Bread> list(Boolean active) {
        return active == null
                ? breadRepository.findAllByOrderByDisplayOrderAscNameAsc()
                : breadRepository.findByActiveOrderByDisplayOrderAscNameAsc(active);
    }

    @Transactional
    public Bread register(BreadCreateRequest request) {
        checkActiveLimit();
        if (breadRepository.existsByActiveName(Bread.activeNameOf(request.name()))) {
            throw new BusinessException(ErrorCode.DUPLICATE_BREAD_NAME);
        }
        Bread bread = Bread.register(request.name(), request.category(), request.price(), request.description(),
                request.allergens(), request.displayOrder());
        return saveAndFlush(bread);
    }

    @Transactional
    public Bread update(long breadId, BreadUpdateRequest request) {
        Bread bread = breadRepository.findById(breadId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        boolean reactivating = Boolean.TRUE.equals(request.active()) && !bread.isActive();
        if (reactivating) {
            checkActiveLimit();
        }
        // 엔티티를 바꾸기 전에 확인한다. 바꾼 뒤 JPQL 을 실행하면 자동 flush 가 먼저 일어나 UNIQUE 위반이 조회에서 터진다
        boolean activeAfter = request.active() != null ? request.active() : bread.isActive();
        String nameAfter = request.name() != null ? request.name() : bread.getName();
        if (activeAfter && (request.name() != null || reactivating)
                && breadRepository.existsByActiveNameAndIdNot(Bread.activeNameOf(nameAfter), breadId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_BREAD_NAME);
        }

        if (request.name() != null) {
            bread.rename(request.name());
        }
        if (request.category() != null) {
            bread.changeCategory(request.category());
        }
        if (request.price() != null) {
            bread.changePrice(request.price());
        }
        if (request.description() != null) {
            bread.describe(request.description().isEmpty() ? null : request.description());
        }
        if (request.allergens() != null) {
            bread.changeAllergens(request.allergens());
        }
        if (request.displayOrder() != null) {
            bread.changeDisplayOrder(request.displayOrder());
        }
        if (request.active() != null) {
            bread.changeActive(request.active());
        }
        return saveAndFlush(bread);
    }

    // 판매 중 개수는 동시에 두 건이 29종에서 등록되면 31종이 될 수 있다. 사장님 화면 하나에서만 쓰여 잠금은 두지 않는다(알려진 한계)
    private void checkActiveLimit() {
        if (!Bread.canActivateMore(breadRepository.countByActiveTrue())) {
            throw new BusinessException(ErrorCode.ACTIVE_BREAD_LIMIT_EXCEEDED);
        }
    }

    // 조회와 저장 사이에 같은 이름이 들어온 경우. 예외를 삼키지 않고 업무 예외로 바꿔 던져 트랜잭션은 롤백된다
    private Bread saveAndFlush(Bread bread) {
        try {
            return breadRepository.saveAndFlush(bread);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_BREAD_NAME);
        }
    }
}
