package com.luyi.myfirst.service.media;

import com.luyi.myfirst.service.media.model.LoadedMedia;
import com.luyi.myfirst.service.media.model.StoredMedia;

import java.io.InputStream;
import java.util.Optional;

/**
 * 图片的存取能力。
 *
 * 三个方法都只用「内容」或「存储标识」表达，不出现路径、不出现 {@code MultipartFile}：
 * 上层因此拿不到拼接路径的权力，落盘位置与命名规则只存在于实现里，
 * 换对象存储时替换实现即可，控制器与帖子服务都不用改。
 *
 * 上传准入（大小、类型）也归实现判断——它们是「能不能存进去」的一部分，
 * 放到调用方就等于多出一处可能被绕过的入口。
 */
public interface MediaStorage {

    /** 存入一张图片，返回可对外发布的绝对地址。不合准入时抛 {@code VALIDATION_FAILED}。 */
    StoredMedia store(InputStream content);

    /** 按存储标识取回；标识形状不符与文件不存在一律返回空，不区分二者。 */
    Optional<LoadedMedia> load(String fileName);

    /** 只有地址属于本服务托管前缀时才删文件；其它情况静默忽略。 */
    void deleteIfOwned(String publicUrl);
}
