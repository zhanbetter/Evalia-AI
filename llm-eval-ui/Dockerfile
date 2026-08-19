# ============ 构建阶段：打包前端静态资源 ============
FROM node:20-alpine AS build
WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ============ 运行阶段：Caddy（静态文件 + API 反代 + 自动 HTTPS）============
FROM caddy:2-alpine
COPY --from=build /build/dist /srv
COPY Caddyfile /etc/caddy/Caddyfile
EXPOSE 80 443