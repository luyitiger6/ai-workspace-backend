package com.luyi.myfirst.web.profile;

import com.luyi.myfirst.service.profile.ProfileService;
import com.luyi.myfirst.service.profile.model.ProfileView;
import com.luyi.myfirst.web.profile.dto.PublicProfileResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开用户资料端点（GET /api/profiles/{publicId}，无需登录）。
 *
 * 与名片批量端点共用 {@code /api/profiles} 前缀但不共用语义：批量只回三字段，这里回四项。
 * 不存在、已注销与形状不合都由服务层统一抛 {@code PROFILE_NOT_FOUND}，
 * 控制器不加分支——一旦在这里区分原因，三种响应的字节就会不一致。
 */
@RestController
@RequestMapping("/api/profiles")
public class PublicProfileController {

    private final ProfileService profileService;

    public PublicProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/{publicId}")
    PublicProfileResponse read(@PathVariable String publicId) {
        return toResponse(profileService.readPublic(publicId));
    }

    private static PublicProfileResponse toResponse(ProfileView view) {
        return new PublicProfileResponse(
                view.publicId(),
                view.displayName(),
                view.avatarUrl(),
                view.bio(),
                view.interests());
    }
}
