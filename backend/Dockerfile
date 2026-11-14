FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY backend/ backend/
COPY database/ database/
WORKDIR /src/backend
RUN chmod +x gradlew && ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/backend/build/libs/*.jar app.jar
COPY --from=build /src/database/migrations /app/migrations
ENV MIGRATIONS_LOCATION=filesystem:/app/migrations
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
