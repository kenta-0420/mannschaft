# =============================================================================
# Stage 1: Build
# =============================================================================
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

# Copy Gradle wrapper and dependency descriptors first for layer caching
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts gradle.properties ./

# Download dependencies (cached as a separate layer if build files don't change)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon --quiet

# Copy source and build the fat JAR
COPY src/ src/
RUN ./gradlew bootJar --no-daemon --quiet -x test

# =============================================================================
# Stage 2: Runtime
# =============================================================================
FROM eclipse-temurin:21-jre-jammy AS runtime

# ---- Security: non-root user ------------------------------------------------
RUN groupadd --system --gid 1001 appgroup \
 && useradd  --system --uid  1001 --gid appgroup --no-create-home appuser

WORKDIR /app

# Copy the executable JAR from the build stage
COPY --from=builder --chown=appuser:appgroup \
     /workspace/build/libs/app-0.0.1-SNAPSHOT.jar app.jar

# ---- Runtime configuration --------------------------------------------------
# Spring profile
ENV SPRING_PROFILES_ACTIVE=prod

# Configurable server port (matches Spring's default; override at runtime)
ENV SERVER_PORT=8080

# JVM flags:
#   -XX:+UseContainerSupport      — honour cgroup CPU/memory limits
#   -XX:MaxRAMPercentage=75.0     — cap heap at 75 % of container RAM
#   -Djava.security.egd=...       — faster SecureRandom on Linux (no /dev/random block)
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -Djava.security.egd=file:/dev/./urandom"

EXPOSE ${SERVER_PORT}

# ---- Health check (Spring Actuator) -----------------------------------------
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider \
      http://localhost:${SERVER_PORT}/actuator/health \
   || exit 1

USER appuser

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
