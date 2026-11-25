FROM eclipse-temurin:17-jre

WORKDIR /app
COPY backend/target/collect-backend-0.1.0.jar /app/app.jar

ENV TZ=Asia/Shanghai
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
