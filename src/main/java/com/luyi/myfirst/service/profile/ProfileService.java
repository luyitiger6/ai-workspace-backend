package com.luyi.myfirst.service.profile;

import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.profile.model.ProfileCard;
import com.luyi.myfirst.service.profile.model.ProfileUpdate;
import com.luyi.myfirst.service.profile.model.ProfileView;

import java.util.List;

/**
 * 资料服务：本人读取、部分更新、按对外标识批量取名片、兴趣标签清单。
 *
 * 本人读写都以令牌为准，不存在按对外标识修改他人资料的入口（spec「更新仅限本人且为部分更新」）。
 * 批量名片是公开能力，只返回昵称与头像；不存在或已注销的标识被省略，不使整批失败。
 */
public interface ProfileService {

    ProfileView read(VerifiedToken verified);

    ProfileView update(VerifiedToken verified, ProfileUpdate command);

    /**
     * 按对外标识批量取名片。1 至 50 个，超量或空集合抛 400；
     * 不存在、形状不合或已注销的标识自然查不到，从结果里省略。
     */
    List<ProfileCard> cards(List<String> publicIds);

    List<String> interests();
}
