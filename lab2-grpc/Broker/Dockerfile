# Build context is lab2-grpc/ (see docker-compose.yml), so the shared proto/ folder is available.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build/Broker
COPY proto/ /build/proto/
COPY Broker/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY Broker/src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/Broker/target/Broker-2.0-SNAPSHOT.jar ./broker.jar
EXPOSE 5000
ENTRYPOINT ["java", "-jar", "broker.jar"]
