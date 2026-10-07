# 多阶段构建：CI 里用 Maven 构建，运行时只带 JRE，镜像小、内存省
# 注意：openjdk 官方镜像已从 Docker Hub 下架，用 eclipse-temurin 系列替代
FROM maven:3.8.6-eclipse-temurin-8 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# 内存限制给 JVM 留余量（Claw 免费额度下用 256MB 规格即可）
ENTRYPOINT ["java", "-Xms64m", "-Xmx180m", "-jar", "app.jar"]
