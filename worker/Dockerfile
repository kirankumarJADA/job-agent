FROM mcr.microsoft.com/playwright:v1.63.0-noble

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY src ./src
COPY test ./test

RUN mkdir -p /data && chown -R pwuser:pwuser /app /data
USER pwuser
ENV NODE_ENV=production
CMD ["node", "src/worker.js"]
