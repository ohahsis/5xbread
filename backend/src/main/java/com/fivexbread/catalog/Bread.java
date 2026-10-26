package com.fivexbread.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * V1 bread 테이블 (A-01). 삭제하지 않고 판매 중지(active=false)만 한다. 과거 예약 항목이 FK 로 가리키기 때문 (13-12).
 * active_name 은 DB 생성 컬럼(판매 중일 때만 공백 제거·소문자 이름)이고 UNIQUE 라, 판매 중인 빵끼리만 이름이 겹치지 않는다.
 */
@Entity
@Table(name = "bread")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bread {

    /** 동시에 판매 중일 수 있는 빵 종류 수 (부록 B: 판매 빵 20~30종, 고정) */
    public static final int MAX_ACTIVE_BREADS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private BreadCategory category;

    @Column(nullable = false)
    private int price;

    @Column(length = 200)
    private String description;

    @Convert(converter = AllergenSetConverter.class)    // JPA 는 Set<Allergen>을 컬럼 하나에 어떻게 넣을지 모른다. 그래서 "이 변환기로 바꿔서 저장하고, 읽을 때도 이걸로 되돌려라"라고 알려준다.
    @Column(length = 100)
    private Set<Allergen> allergens = EnumSet.noneOf(Allergen.class);   // 처음 값을 빈 집합으로 둬서 필드가 null 이 되지 않게 한다. 그래서 bread.getAllergens().contains(MILK)를 null 확인 없이 쓸 수 있다.

    private Long imageId;                                  // 사진 업로드(bread-images)는 이번 범위 밖

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active;

    @Column(insertable = false, updatable = false, length = 30) // 이 필드는 읽기만 하고, INSERT·UPDATE 문에는 넣지 말라고 JPA 에 명령
    private String activeName;                             // DB 가 계산. 중복 사전 확인에만 읽는다

    private Bread(String name, BreadCategory category, int price, String description, Set<Allergen> allergens,
                  int displayOrder) {
        this.name = name;
        this.category = category;
        this.price = price;
        this.description = description;
        this.allergens = copy(allergens);
        this.displayOrder = displayOrder;
        this.active = true;
    }

    public static Bread register(String name, BreadCategory category, int price, String description,
                                 Set<Allergen> allergens, int displayOrder) {
        return new Bread(name, category, price, description, allergens, displayOrder);
    }

    /** 판매 중인 빵이 activeCount 종일 때 한 종을 더 판매할 수 있는지 (등록·판매 재개) */
    public static boolean canActivateMore(long activeCount) {
        return activeCount < MAX_ACTIVE_BREADS;
    }

    /** DB 생성 컬럼과 같은 규칙: 공백 제거 + 소문자. 비교는 컬럼 콜레이션(대소문자 무시)이 한 번 더 한다 */
    public static String activeNameOf(String name) {
        return name.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changeCategory(BreadCategory category) {
        this.category = category;
    }

    /** 기존 예약은 reservation_item.unit_price(예약 당시 가격)를 쓰므로 영향 없음 (13-12) */
    public void changePrice(int price) {
        this.price = price;
    }

    public void describe(String description) {
        this.description = description;
    }

    public void changeAllergens(Set<Allergen> allergens) {
        this.allergens = copy(allergens);
    }

    public void changeDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    /** 판매 중지해도 기존 예약은 그대로 둔다. 새 판매일 수량 설정에서만 빠진다 (9장 필드 규격 "판매 여부") */
    public void changeActive(boolean active) {
        this.active = active;
    }

    private static Set<Allergen> copy(Set<Allergen> allergens) {
        return allergens == null || allergens.isEmpty() ? EnumSet.noneOf(Allergen.class) : EnumSet.copyOf(allergens);
    }
}
