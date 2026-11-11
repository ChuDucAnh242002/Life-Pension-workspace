FROM node:22-alpine AS frontend
WORKDIR /build
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

FROM maven:3.9.11-eclipse-temurin-21 AS backend
WORKDIR /build
COPY backend/pom.xml .
RUN mvn -B dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /build/dist ./src/main/resources/static
RUN mvn -B verify

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 1000 workspace && adduser -S -u 1000 workspace -G workspace \
    && mkdir /data && chown workspace:workspace /data
WORKDIR /app
COPY --from=backend /build/target/workspace-0.1.0.jar app.jar
USER workspace
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "app.jar"]
