# Build context is lab2-grpc/ (see docker-compose.yml), so the shared proto/ folder is available.
FROM node:22-alpine AS build
WORKDIR /build/Publisher
COPY Publisher/package.json Publisher/package-lock.json ./
RUN npm ci
COPY Publisher/tsconfig.json ./
COPY Publisher/src ./src
RUN npm run build && npm prune --omit=dev

FROM node:22-alpine
# Same layout as the repository, so the code finds ../../proto/pubsub.proto from dist/.
WORKDIR /app/Publisher
COPY proto/ /app/proto/
COPY --from=build /build/Publisher/node_modules ./node_modules
COPY --from=build /build/Publisher/dist ./dist
COPY Publisher/public ./public
# Terminal mode: <publisherId> <topic> [host] [port]   (inside compose the broker host is "broker")
# Web UI mode:   --ui [brokerHost] [brokerPort] [uiPort]
ENV UI_HOST=0.0.0.0
EXPOSE 3000
ENTRYPOINT ["node", "dist/index.js"]
