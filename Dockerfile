# 1. Build the React frontend.
FROM node:24-slim AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# 2. Build the Spring Boot jar, with the frontend packaged in as static resources.
FROM eclipse-temurin:25-jdk AS backend
WORKDIR /build/backend
COPY backend/ ./
COPY --from=frontend /build/frontend/dist /build/frontend/dist
RUN sh ./gradlew bootJar -PprebuiltFrontend --no-daemon

# 3. Runtime image. No fonts ship inside the app: edited text is redrawn with fonts installed in
# the image, so install a Korean font (and fontconfig, which Java 2D needs to render pages).
FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-nanum fontconfig \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=backend /build/backend/build/libs/pdfedit-v2-backend-*.jar app.jar

ENV SERVER_PORT=5905
EXPOSE 5905

ENTRYPOINT ["java", "-jar", "app.jar"]
