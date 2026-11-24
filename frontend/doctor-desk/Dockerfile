# syntax=docker/dockerfile:1.7
# -----------------------------------------------------------------------------
# Syncra HIMS — Doctor Desk (React SPA served by unprivileged nginx)
# -----------------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json .npmrc ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.30-alpine AS runtime
LABEL org.opencontainers.image.title="syncra-doctor-desk" \
      org.opencontainers.image.vendor="Syncra Technologies"
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
