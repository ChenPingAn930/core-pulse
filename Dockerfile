# ===== 构建阶段：打包后端 jar =====
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
# 先复制 pom 文件，利用 Docker 层缓存加速依赖下载
COPY pom.xml .
COPY corepulse-common/pom.xml corepulse-common/
COPY corepulse-domain/pom.xml corepulse-domain/
COPY corepulse-chat/pom.xml corepulse-chat/
COPY corepulse-task/pom.xml corepulse-task/
COPY corepulse-system/pom.xml corepulse-system/
COPY corepulse-user/pom.xml corepulse-user/
COPY corepulse-web/pom.xml corepulse-web/
# 下载依赖（利用缓存）
RUN mvn dependency:go-offline -B || true
# 复制全部源码并打包
COPY . .
RUN mvn clean package -DskipTests -B

# ===== 运行阶段 =====
FROM eclipse-temurin:17-jre
WORKDIR /app
# 从构建阶段复制后端 jar
COPY --from=builder /build/corepulse-web/target/*.jar app.jar
# 复制前端静态资源到 /app/static（由启动脚本从前端 dist 同步而来）
COPY frontend-dist/ /app/static/
EXPOSE 927
ENTRYPOINT ["java", "-jar", "app.jar"]