# syntax=docker/dockerfile:1.7
# =============================================================================
# React (CRA) frontend: build static assets, serve with nginx, proxy /api -> backend.
# =============================================================================

# ---- 1) build ---------------------------------------------------------------
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json* ./
# NOTE: `npm install`, not `npm ci`: @stripe/stripe-js and @stripe/react-stripe-js were added to
# package.json and the lock file could not be regenerated offline. Run `npm install` once in ./react,
# commit the refreshed package-lock.json, then switch this line to `npm ci` for reproducible builds.
RUN --mount=type=cache,target=/root/.npm npm install --no-audit --no-fund

COPY . .

# "/api" is proxied by nginx to the backend, so the browser stays same-origin (no CORS).
ARG REACT_APP_API_BASE_URL=/api
ENV REACT_APP_API_BASE_URL=$REACT_APP_API_BASE_URL \
    CI=false \
    GENERATE_SOURCEMAP=false
RUN npm run build

# ---- 2) serve -----------------------------------------------------------------
FROM nginx:1.27-alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/build /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1/nginx-health || exit 1
