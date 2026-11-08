package com.luyi.myfirst.web.profile;

import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.profile.ProfileService;
import com.luyi.myfirst.service.profile.model.ProfileView;
import com.luyi.myfirst.web.auth.interceptor.AuthInterceptor;
import com.luyi.myfirst.web.profile.dto.ProfileResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本人资料读取端点（GET /api/profile，需登录）。
 *
 * 身份由拦截器写入 request attribute；这里只做白名单映射，不把实体直接序列化出去。
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    ProfileResponse read(@RequestAttribute(AuthInterceptor.VERIFIED_ATTRIBUTE) VerifiedToken verified) {
        return toResponse(profileService.read(verified));
    }

    private static ProfileResponse toResponse(ProfileView view) {
        return new ProfileResponse(
                view.publicId(),
                view.displayName(),
                view.avatarUrl(),
                view.bio(),
                view.interests());
    }
}
