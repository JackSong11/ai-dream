FROM node:22-alpine AS builder
WORKDIR /app
COPY dream-frontend/dream-app-ui/package*.json ./
RUN npm ci
COPY dream-frontend/dream-app-ui/ ./
ARG VITE_API_BASE_URL=
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

FROM nginx:1.28-alpine
COPY dream-frontend/dream-app-ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
