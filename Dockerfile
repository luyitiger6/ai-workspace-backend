# 运行镜像：只带 JRE 与可执行 jar。最终体积由这一层决定，与 Dockerfile 放在哪个目录无关。
#
# 构建在宿主机完成（mvn -f backend/pom.xml package -DskipTests），镜像内不做编译。
# 原因是当前环境拉不到 maven 官方镜像（镜像加速器返回 403，公共代理只有几十 KB/s），
# 而宿主机的 Maven Central 可达。产物效果相同：镜像里同样没有 Maven 与源码。
FROM alpine:3.20

# 官方 CDN 在国内常年几十 KB/s，换阿里云源；只装 headless JRE，不装完整 JDK。
RUN sed -i 's#dl-cdn.alpinelinux.org#mirrors.aliyun.com#g' /etc/apk/repositories \
    && apk add --no-cache openjdk17-jre-headless \
    && rm -rf /var/cache/apk/*

WORKDIR /app

# 非 root 运行，与前端镜像保持同一约定。
RUN addgroup -S app && adduser -S -G app app

COPY target/my-first-project-0.0.1-SNAPSHOT.jar ./app.jar
# 封面图落盘目录；Kubernetes 侧会挂卷覆盖它。
RUN mkdir -p /app/media && chown -R app:app /app

USER app
EXPOSE 8989
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
