package com.luyi.myfirst.web.profile;

import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.profile.ProfileService;
import com.luyi.myfirst.service.profile.model.ProfileUpdate;
import com.luyi.myfirst.service.profile.model.ProfileView;
import com.luyi.myfirst.web.auth.interceptor.AuthInterceptor;
import com.luyi.myfirst.web.profile.dto.ProfileResponse;
import com.luyi.myfirst.web.profile.dto.UpdateProfileRequest;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本人资料部分更新端点（PUT /api/profile，需登录）。
 *
 * 只把请求体转成 service 入参并回传映射后的响应；校验在 ProfileRules，这里不重复判定。
 * 更新只作用于令牌账户，没有按对外标识改他人资料的入口（spec「更新仅限本人」）。
 */
@RestController
@RequestMapping("/api/profile")
public class UpdateProfileController {

    private final ProfileService profileService;

    public UpdateProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @PutMapping
    ProfileResponse update(
            @RequestAttribute(AuthInterceptor.VERIFIED_ATTRIBUTE) VerifiedToken verified,
            @RequestBody UpdateProfileRequest request) {
        ProfileUpdate command = new ProfileUpdate(
                request.displayName(),
                request.avatarUrl(),
                request.bio(),
                request.interests());
        return toResponse(profileService.update(verified, command));
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
