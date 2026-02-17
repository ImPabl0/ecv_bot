FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/target/discord_bot-1.0-SNAPSHOT.jar app.jar

CMD ["java", "-jar", "app.jar"]
