# Build context is lab3/ (see docker-compose.yml), so the Maven wrapper is shared with the API.

# ---- build stage ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw ./
COPY gateway/pom.xml gateway/pom.xml
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -f gateway/pom.xml dependency:go-offline
COPY gateway/src gateway/src
RUN ./mvnw -q -f gateway/pom.xml package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/gateway/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
