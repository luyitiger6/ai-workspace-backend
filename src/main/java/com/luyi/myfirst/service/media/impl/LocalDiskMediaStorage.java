package com.luyi.myfirst.service.media.impl;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.config.MediaProperties;
import com.luyi.myfirst.service.media.MediaStorage;
import com.luyi.myfirst.service.media.model.LoadedMedia;
import com.luyi.myfirst.service.media.model.StoredMedia;
import com.luyi.myfirst.service.media.support.MediaImageType;
import com.luyi.myfirst.service.media.support.MediaRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;

/**
 * 本地磁盘存储。
 *
 * 三条不变式：
 * 1. 文件名完全由服务端生成（ULID + 由内容决定的扩展名），客户端给的名字一个字符都不参与；
 * 2. 读取先校验形状再拼路径，拼完再校验归一化后仍在根目录内——双保险；
 * 3. 上传只读 {@code MAX_BYTES + 1} 字节：多读的那 1 字节就是「超限」的证据，
 *    不必把整个文件读进内存再判断。
 *
 * 读取与回收都走 {@link #resolveOwned}，因此「形状不对」与「越出根目录」只有一处出口。
 */
@Service
public class LocalDiskMediaStorage implements MediaStorage {

    private static final Logger LOG = LoggerFactory.getLogger(LocalDiskMediaStorage.class);

    private final Path root;
    private final String publicBaseUrl;

    public LocalDiskMediaStorage(MediaProperties properties) {
        this.root = Paths.get(properties.root()).toAbsolutePath().normalize();
        this.publicBaseUrl = stripTrailingSlash(properties.publicBaseUrl());
    }

    @Override
    public StoredMedia store(InputStream content) {
        byte[] bytes;
        try {
            bytes = content.readNBytes(MediaRules.MAX_BYTES + 1);
        } catch (IOException exception) {
            // 读不到请求体属于「传什么了」的问题，归参数；写盘失败才是服务端故障，下面不吞。
            LOG.warn("读取上传内容失败", exception);
            throw new BizException(AuthErrorEnum.VALIDATION_FAILED);
        }
        if (bytes.length == 0 || bytes.length > MediaRules.MAX_BYTES) {
            throw new BizException(AuthErrorEnum.VALIDATION_FAILED);
        }
        MediaImageType type = MediaImageType
                .detect(Arrays.copyOf(bytes, Math.min(bytes.length, MediaRules.MAGIC_BYTES)))
                .orElseThrow(() -> new BizException(AuthErrorEnum.VALIDATION_FAILED));

        String fileName = Ulid.generate() + "." + type.extension();
        try {
            Files.createDirectories(root);
            Files.write(root.resolve(fileName), bytes);
        } catch (IOException exception) {
            // 不伪装成 400：磁盘写不进去是服务端故障，让 500 暴露出来更诚实。
            throw new UncheckedIOException("媒体文件写入失败: " + fileName, exception);
        }
        return new StoredMedia(publicBaseUrl + "/" + fileName, type.contentType(), bytes.length);
    }

    @Override
    public Optional<LoadedMedia> load(String fileName) {
        Optional<Path> owned = resolveOwned(fileName);
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        Path target = owned.get();
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        Optional<MediaImageType> type = MediaImageType
                .fromExtension(fileName.substring(fileName.lastIndexOf('.') + 1));
        if (type.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new LoadedMedia(target, type.get().contentType(), Files.size(target)));
        } catch (IOException exception) {
            LOG.warn("读取媒体文件属性失败: {}", fileName, exception);
            return Optional.empty();
        }
    }

    @Override
    public void deleteIfOwned(String publicUrl) {
        if (publicUrl == null || !publicUrl.startsWith(publicBaseUrl + "/")) {
            return;
        }
        Optional<Path> owned = resolveOwned(publicUrl.substring(publicBaseUrl.length() + 1));
        if (owned.isEmpty()) {
            return;
        }
        try {
            Files.deleteIfExists(owned.get());
        } catch (IOException exception) {
            // 回收是 best-effort：删帖已经提交，这里抛错只会让用户看到「删不掉」，问题反而更大。
            LOG.warn("删除媒体文件失败，留下孤儿文件: {}", publicUrl, exception);
        }
    }

    /** 形状校验 + 归一化后仍在根目录内，两道都过才交出路径。 */
    private Optional<Path> resolveOwned(String fileName) {
        if (fileName == null || !MediaRules.FILE_NAME.matcher(fileName).matches()) {
            return Optional.empty();
        }
        Path target = root.resolve(fileName).normalize();
        if (!target.startsWith(root)) {
            LOG.warn("媒体标识越出存储根目录: {}", fileName);
            return Optional.empty();
        }
        return Optional.of(target);
    }

    private static String stripTrailingSlash(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
