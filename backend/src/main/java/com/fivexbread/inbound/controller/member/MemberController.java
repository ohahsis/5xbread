package com.fivexbread.inbound.controller.member;

import com.fivexbread.domain.member.MemberFacade;
import com.fivexbread.domain.member.MemberService;
import com.fivexbread.inbound.controller.member.model.request.MyInfoPatch;
import com.fivexbread.inbound.controller.member.model.request.MyInfoPatchBody;
import com.fivexbread.inbound.controller.member.model.response.MyInfoResponse;
import com.fivexbread.inbound.controller.member.model.request.SignupRequest;
import com.fivexbread.inbound.controller.member.model.response.SignupResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Content;
import com.fivexbread.domain.auth.model.vo.AuthMember;
import com.fivexbread.inbound.controller.reservation.ReservationController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가입 (C-01)과 내 정보 (C-10). /members/me 는 로그인한 회원의 별칭이라 다른 회원 id 를 넣어 볼 수 없다 (11장 IDOR).
 * 비밀번호 변경은 토큰을 다시 내려 주므로 auth.PasswordController 에 있다.
 */
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberFacade memberFacade;
    private final MemberService memberService;
    private final Validator validator;

    /**
     * 가입 (C-01). 클라이언트 IP는 server.forward-headers-strategy=native로
     * Nginx가 붙인 X-Forwarded-For를 Tomcat이 반영한 값 (13-5).
     */
    @PostMapping
    @ApiResponse(responseCode = "201", description = "가입함. Location: /api/v1/members/{id}")
    public ResponseEntity<SignupResponse> signUp(@Valid @RequestBody SignupRequest request,
                                                 HttpServletRequest httpRequest) {
        SignupResponse response = memberFacade.signUp(request, httpRequest.getRemoteAddr());
        return ResponseEntity.created(URI.create("/api/v1/members/" + response.memberId())).body(response);
    }

    /** 내 정보 (U-10). 탈퇴한 회원의 남은 Access Token 이면 401 */
    @GetMapping("/me")
    public MyInfoResponse me(@AuthenticationPrincipal AuthMember member) {
        return memberService.myInfo(member.memberId());
    }

    /** 이름 수정 (C-10). {"name": "..."} 만 받는다. 규칙은 가입과 같다(앞뒤 공백 제거 후 한글·영문 2~20자) */
    @PatchMapping(value = "/me", consumes = ReservationController.MERGE_PATCH_JSON)
    public MyInfoResponse patch(@AuthenticationPrincipal AuthMember member,
                                @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = ReservationController.MERGE_PATCH_JSON, schema = @Schema(implementation = MyInfoPatchBody.class)))
                                @RequestBody Map<String, Object> body) {
        return memberService.rename(member.memberId(), MyInfoPatch.name(body, validator));
    }
}
