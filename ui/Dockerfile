# syntax=docker/dockerfile:1
FROM node:24-alpine3.23 AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npm run build

# Unprivileged nginx: uid 101, listens on 8080, writable dirs under /tmp
FROM nginxinc/nginx-unprivileged:1.31.7-alpine
LABEL org.opencontainers.image.title="order-ui" \
      org.opencontainers.image.description="Angular UI for the order service, served by nginx with an /api proxy" \
      org.opencontainers.image.source="https://github.com/c0de-ch/pf-eventordersrv"
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /workspace/dist/ui/browser/ /usr/share/nginx/html/
ENV API_UPSTREAM=order-service:8080
EXPOSE 8080
