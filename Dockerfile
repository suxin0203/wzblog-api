# 多阶段构建：构建层用 Maven+JDK21，运行层只留 JRE，产物更小且不含构建工具链。
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 健康检查依赖 curl；以非 root 运行
RUN apk add --no-cache curl && addgroup -S wzblog && adduser -S wzblog -G wzblog
USER wzblog

COPY --from=build /build/target/wzblog-server-*.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
EXPOSE 8021
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
