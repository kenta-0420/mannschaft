# ============================================================
# Stage 1: Install dependencies
# ============================================================
FROM node:22-alpine AS deps

WORKDIR /app

# Install only production + build-time deps via ci for reproducibility
COPY package.json package-lock.json ./
RUN npm ci

# ============================================================
# Stage 2: Build
# ============================================================
FROM node:22-alpine AS builder

WORKDIR /app

# Copy installed node_modules from deps stage
COPY --from=deps /app/node_modules ./node_modules

# Copy source
COPY . .

# Build Nuxt 3 (outputs to .output/)
# NUXT_PUBLIC_API_BASE can be overridden at runtime via env var;
# set a placeholder here so the build succeeds in CI without a live backend.
ARG NUXT_PUBLIC_API_BASE=http://localhost:8080
ENV NUXT_PUBLIC_API_BASE=${NUXT_PUBLIC_API_BASE}

RUN npm run build

# ============================================================
# Stage 3: Runtime
# ============================================================
FROM node:22-alpine AS runtime

# Security: add non-root user
RUN addgroup --system --gid 1001 nuxtgroup \
 && adduser  --system --uid 1001 --ingroup nuxtgroup nuxtuser

WORKDIR /app

# Nuxt 3 standalone output is self-contained — no node_modules needed
COPY --from=builder --chown=nuxtuser:nuxtgroup /app/.output ./

USER nuxtuser

# Nuxt 3 default port
ENV PORT=3000
ENV HOST=0.0.0.0
ENV NODE_ENV=production

EXPOSE 3000

# Health check — hits Nuxt's built-in /_nuxt/... or the root page
HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
  CMD wget -qO- http://localhost:3000/ || exit 1

# Nuxt 3 standalone entry point
CMD ["node", "server/index.mjs"]
