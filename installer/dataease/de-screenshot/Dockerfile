# DataEase 截图服务：基于微软官方 Playwright 镜像（已包含 Chromium 与系统依赖）
FROM mcr.microsoft.com/playwright:v1.56.1-noble

ENV NODE_ENV=production \
    PORT=3000 \
    CONCURRENCY=2 \
    TZ=Asia/Shanghai

WORKDIR /opt/app
COPY package.json server.js ./
RUN npm install --omit=dev --no-audit --no-fund \
    && apt-get update \
    && apt-get install -y --no-install-recommends fonts-noto-cjk curl \
    && rm -rf /var/lib/apt/lists/*

EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 CMD curl -f http://localhost:3000/health || exit 1
USER pwuser
CMD ["node", "server.js"]
