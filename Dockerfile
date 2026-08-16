# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build
COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

# Create a non-root user for security
RUN addgroup -S gateway && adduser -S gateway -G gateway

WORKDIR /app

# Copy the compiled JAR from the builder stage
COPY --from=builder /build/target/apigateway-1.0.0-SNAPSHOT.jar app.jar

# Change ownership to the non-root user
RUN chown -R gateway:gateway /app

USER gateway

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
