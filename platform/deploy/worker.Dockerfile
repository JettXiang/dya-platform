FROM eclipse-temurin:17-jre

WORKDIR /app
COPY worker/target/worker.jar /app/worker.jar

ENV TZ=Asia/Shanghai
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/worker.jar"]
