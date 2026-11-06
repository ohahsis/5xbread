package com.fivexbread.admin;

import com.fivexbread.auth.AuthMember;
import com.fivexbread.member.Member;
import com.fivexbread.member.MemberService;
import com.fivexbread.member.Phone;
import com.fivexbread.member.PhoneValidator;
import com.fivexbread.reservation.ReservationController;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 사장님 회원 관리 (A-09, A-10 제한 해제). ADMIN 만(SecurityConfig). 필요한 최소 범위만 둔다 (A-10 검색 고도화는 2차) */
@RestController
@RequestMapping("/api/v1/admin/members")
@RequiredArgsConstructor
public class AdminMemberController {

    private final MemberService memberService;
    private final TemporaryPasswordFacade temporaryPasswordFacade;
    private final ReservationBlockService reservationBlockService;

    /** 번호 전체가 일치하는 손님만 찾는다. 없으면 200 + 빈 목록 */
    @GetMapping
    public List<AdminMemberResponse> search(@RequestParam @Phone String phone) {
        return memberService.findByPhone(PhoneValidator.normalize(phone)).stream()
                .filter(Member::canReceiveTemporaryPassword)
                .map(AdminMemberResponse::from)
                .toList();
    }

    @PostMapping("/{memberId}/temporary-passwords")
    @ResponseStatus(HttpStatus.CREATED)
    public TemporaryPasswordResponse issueTemporaryPassword(@AuthenticationPrincipal AuthMember admin,
                                                            @PathVariable long memberId) {
        return temporaryPasswordFacade.issue(admin.memberId(), memberId);
    }

    /** 노쇼 제한 해제 (A-10, R-17). 제한이 없어도 결과가 같으므로 200 */
    @PatchMapping(value = "/{memberId}", consumes = ReservationController.MERGE_PATCH_JSON)
    public AdminMemberResponse patch(@AuthenticationPrincipal AuthMember admin, @PathVariable long memberId,
                                     @RequestBody Map<String, Object> body) {
        MemberPatch.requireBlockClear(body);
        return reservationBlockService.clear(admin.memberId(), memberId);
    }
}
