# ---- build stage:在容器內 build,不依賴本機工具鏈(這台機器沒有 mvnw/mvn) ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 先只 copy pom 抓依賴,善用 layer cache(src 改動不會重抓依賴)
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# 再 copy 原始碼 build(跳過測試,測試在 CI/本地另跑)
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- run stage:精簡 JRE 映像 ----
FROM eclipse-temurin:17-jre AS run
WORKDIR /app

# artifactId=ticket-system, version=0.3.0 → target/ticket-system-0.3.0.jar
COPY --from=build /app/target/ticket-system-*.jar app.jar

EXPOSE 8099

# graceful shutdown:縮容砍 pod 時給時間排空進行中請求與 @Async 佇列(見 architecture.md 安全表)
# 用 exec 讓 JVM 成為 PID 1,才收得到 k8s 送的 SIGTERM 觸發 graceful shutdown
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
