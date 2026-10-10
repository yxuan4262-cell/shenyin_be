# syntax=docker/dockerfile:1
# ============================================================
#  知光后端 (zhiguang_be) 容器化 Dockerfile
#  第十阶段·第一课 练习A：多阶段构建 + Spring Boot 分层 jar (layertools)
#
#  设计对照本地启动方式（run-app.ps1）：
#    本地 = mvn spring-boot:run，应用监听 8080
#    这里 = 用 JRE 直接跑 fat jar 的 JarLauncher，同样监听 8080
#
#  产物 jar：target/zhiguang-1.0-SNAPSHOT.jar（spring-boot:3.2.4 / Java 21）
# ============================================================

# ---------- 阶段①：build（把脏活集中在这，用完即弃，不进最终镜像）----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只喂 pom.xml 下载依赖：pom 不变 → 这一层命中缓存（对应 Q2「稳定在前、易变在后」）
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

# 依赖下好后才放源码：改一行业务代码不会连累全量依赖重新下载
COPY src ./src
RUN mvn -B -ntp -DskipTests clean package

# ---------- 阶段②：extract（用 layertools 把 fat jar 拆成四层）----------
FROM eclipse-temurin:21-jre AS extract
WORKDIR /extract
COPY --from=build /build/target/*.jar app.jar
# 拆出的目录：dependencies/  spring-boot-loader/  snapshot-dependencies/  application/
RUN java -Djarmode=layertools -jar app.jar extract

# ---------- 阶段③：runtime（最小化：只带 JRE + 分层后的 jar 内容）----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# 安全：非 root 运行（不带 maven/JDK 构建工具，攻击面最小 —— 对应 Q1 的安全收益）
RUN groupadd --system spring && useradd --system --gid spring spring
USER spring:spring

# 按「变化频率 低→高」依次 COPY 四层（对应 Q3）：
#   只改业务代码时，前三层命中缓存直接复用，仅最后 application 薄层重建。
COPY --from=extract --chown=spring:spring /extract/dependencies/            ./
COPY --from=extract --chown=spring:spring /extract/spring-boot-loader/      ./
COPY --from=extract --chown=spring:spring /extract/snapshot-dependencies/   ./
COPY --from=extract --chown=spring:spring /extract/application/             ./

EXPOSE 8080

# 容器感知 JVM：按容器内存上限动态设定堆大小，避免固定 -Xmx 在大/小容器里被 OOMKilled
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

# ⚠️ 关键坑：Spring Boot 3.2.4 的启动器类名带 .launch
#   正确：org.springframework.boot.loader.launch.JarLauncher
#   老版：org.springframework.boot.loader.JarLauncher（照抄会 ClassNotFoundException）
#
# 生产配置应由环境变量注入（12-Factor），如：
#   -e SPRING_DATA_REDIS_HOST=redis  -e SPRING_ELASTICSEARCH_URIS=http://elasticsearch:9200
#   完整 prod 编排（application-prod.yml + compose 补 MySQL/app）见第十阶段·第二课
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
