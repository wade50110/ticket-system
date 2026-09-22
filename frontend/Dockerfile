# ---- build stage:node build 出靜態檔 ----
FROM node:20-alpine AS build
WORKDIR /app

# 先 copy package 檔抓依賴,善用 layer cache
COPY package.json package-lock.json* ./
RUN npm install

# build(產出 dist/,純 CSR 靜態檔)
COPY . .
RUN npm run build

# ---- run stage:nginx serve 靜態檔 + 反代 /api ----
FROM nginx:alpine AS run

# 自訂設定:SPA fallback + 反代 /api → backend service
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
