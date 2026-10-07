# 多阶段构建：CI 里用 Maven 构建，运行时只带 JRE，镜像小、内存省
FROM maven:3.8.6-openjdk-8 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

FROM openjdk:8-jre-slim
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# 内存限制给 JVM 留余量（Claw 免费额度下用 256MB 规格即可）
ENTRYPOINT ["java", "-Xms64m", "-Xmx180m", "-jar", "app.jar"]
