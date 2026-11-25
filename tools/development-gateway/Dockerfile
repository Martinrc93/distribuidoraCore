FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build -- --mode instance-launcher

FROM nginxinc/nginx-unprivileged:1.30-alpine
COPY frontend/nginx-security.conf /etc/nginx/includes/security.conf
COPY tools/development-gateway/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/ /usr/share/nginx/html/
USER root
RUN mv /usr/share/nginx/html/instance-launcher.html /usr/share/nginx/html/index.html
USER 101
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s CMD wget -q -O /dev/null http://127.0.0.1:8080/index.html || exit 1
