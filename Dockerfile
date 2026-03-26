# Stage 1: build the fat JAR
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: minimal runtime image
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/WebGrep-1.0.0.jar webgrep.jar
ENTRYPOINT ["java", "-jar", "webgrep.jar"]
