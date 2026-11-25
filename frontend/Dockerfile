FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG APP_BASE_PATH=/
RUN npm run build -- --base "${APP_BASE_PATH}"

FROM nginxinc/nginx-unprivileged:1.30-alpine AS web
COPY nginx-security.conf /etc/nginx/includes/security.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s CMD wget -q -O /dev/null http://127.0.0.1:8080/index.html || exit 1

FROM web AS instance
COPY nginx.instance.conf.template /etc/nginx/templates/default.conf.template

FROM web AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
