FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG APP_BASE_PATH=/
RUN npm run build -- --base "${APP_BASE_PATH}"

FROM nginx:1.27-alpine AS web
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80

FROM web AS instance
COPY nginx.instance.conf.template /etc/nginx/templates/default.conf.template

FROM web AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
