# ============ 构建阶段 ============
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先复制全部 pom.xml（利用 Docker 层缓存，业务代码不变时依赖层不重建）
COPY pom.xml .
COPY llm-eval-common/pom.xml llm-eval-common/pom.xml
COPY llm-eval-model/pom.xml llm-eval-model/pom.xml
COPY llm-eval-dao/pom.xml llm-eval-dao/pom.xml
COPY llm-eval-service/pom.xml llm-eval-service/pom.xml
COPY llm-eval-worker/pom.xml llm-eval-worker/pom.xml
COPY llm-eval-web/pom.xml llm-eval-web/pom.xml
RUN mvn -B -q dependency:go-offline -DskipTests

# 复制源码并打包（跳过测试以加速部署）
COPY . .
RUN mvn -B clean package -DskipTests

# ============ 运行阶段 ============
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/llm-eval-web/target/llm-eval-web-*.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS="-Xms256m -Xmx768m"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]