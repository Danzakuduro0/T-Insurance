# Образ сервера для показа и для тех, кому не нужен IntelliJ (фронтенд, мобильная разработка).
# Собирается из готового jar, поэтому сначала: ./gradlew :server:buildFatJar
# Запуск вместе с базой: docker compose --profile app up -d --build  (подробнее — README, раздел Docker)
FROM eclipse-temurin:21-jre

# Сервер работает не от root; файлы обращений — в томе /data/files
RUN useradd --system --uid 10001 --no-create-home tstrah \
    && mkdir -p /data/files && chown tstrah /data/files
COPY server/build/libs/server-all.jar /app/server.jar

USER tstrah
ENV STORAGE_DIR=/data/files
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/server.jar"]
