FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build -- --mode instance-launcher

FROM nginx:1.27-alpine
COPY tools/development-gateway/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/ /usr/share/nginx/html/
RUN mv /usr/share/nginx/html/instance-launcher.html /usr/share/nginx/html/index.html
EXPOSE 80
