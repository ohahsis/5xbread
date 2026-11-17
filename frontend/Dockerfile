# 손님 화면 빌드 → Nginx 이미지 (compose.full.yaml 의 nginx 서비스)
# 호스트에 Node 가 없어도 docker compose -f compose.full.yaml up -d --build 한 번으로 화면까지 뜬다.
# nginx.conf 는 이미지에 넣지 않고 compose 가 infra/nginx/nginx.conf 를 마운트한다 (로드밸런서 설정과 한곳에서 관리)
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27
COPY --from=build /app/dist /usr/share/nginx/html
