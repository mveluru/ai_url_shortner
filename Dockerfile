# Build: docker build -t url-shortener .   Run with the prod profile and the environment described in application-prod.yml.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./mvnw -q -B -pl url-shortener-service -am -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
USER app
COPY --from=build /src/url-shortener-service/target/url-shortener-service-*.jar /app/app.jar
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
