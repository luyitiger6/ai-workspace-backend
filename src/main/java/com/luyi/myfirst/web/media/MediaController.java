package com.luyi.myfirst.web.media;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.service.media.MediaStorage;
import com.luyi.myfirst.service.media.model.LoadedMedia;
import com.luyi.myfirst.service.media.model.StoredMedia;
import com.luyi.myfirst.web.media.dto.MediaUploadResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Optional;

/**
 * 媒体 REST 端点。
 *
 * 只做两件事：把请求体交给存储服务、把结果装成响应体。准入判定（大小、类型、
 * 存储标识）全部在 {@link MediaStorage} 的实现里，因为那里才是「能不能存进去」的地方。
 *
 * 身份不由这里取：上传路由没有登记进 {@code AuthInterceptor.publicRoutes()}，
 * 拦截器已经保证能走到这里的请求都带着有效令牌，控制器再取一次身份也只是重复解析。
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaStorage storage;

    public MediaController(MediaStorage storage) {
        this.storage = storage;
    }

    @PostMapping
    ResponseEntity<MediaUploadResponse> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new BizException(AuthErrorEnum.VALIDATION_FAILED);
        }
        try (InputStream content = file.getInputStream()) {
            StoredMedia stored = storage.store(content);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new MediaUploadResponse(stored.url(), stored.contentType(), stored.size()));
        } catch (IOException exception) {
            throw new UncheckedIOException("读取上传内容失败", exception);
        }
    }

    /**
     * 读取已托管资源：公开路由，来访者不需要登录。
     *
     * 标识不合形状、文件不存在都返回 404 且不拼路径——两者在调用方看来必须是同一件事，
     * 否则「这个文件存在与不存在」会变成一个可枚举的信道。
     *
     * 缓存一年加 immutable：文件标识由服务端生成且从不覆写，同一地址的内容永远不会变，
     * 浏览器不需要再次确认。
     */
    @GetMapping("/{fileName}")
    ResponseEntity<Resource> read(@PathVariable String fileName) {
        Optional<LoadedMedia> loaded = storage.load(fileName);
        if (loaded.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        LoadedMedia media = loaded.get();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(media.contentType()))
                .contentLength(media.size())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .eTag("\"" + fileName + "\"")
                .body(new FileSystemResource(media.path()));
    }
}
