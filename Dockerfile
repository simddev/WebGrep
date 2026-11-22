# Stage 1: build the fat JAR
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: runtime image
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/WebGrep-*.jar webgrep.jar
# Run as an unprivileged user. Files and folders mounted for searching only need to be readable.
RUN addgroup -S webgrep && adduser -S -G webgrep webgrep
USER webgrep
ENTRYPOINT ["java", "-jar", "webgrep.jar"]
