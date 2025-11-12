# Build stage
FROM node:22 AS build
WORKDIR /app
# Aligner la version de npm dans l'image sur celle qui génère le lock
RUN corepack enable && corepack prepare npm@11.6.1 --activate
# Copier *uniquement* les manifests avant l’install
COPY package*.json ./
# Regénère au besoin package-lock.json
RUN if [ -f package-lock.json ]; then \
      npm ci --no-audit --no-fund ; \
    else \
      npm install --no-audit --no-fund ; \
    fi
COPY . ./
RUN npm run build -- --configuration production

# Runtime stage
FROM nginx:1.27-alpine
# Angular CLI 17+ emits files in dist/<project>/browser
COPY --from=build /app/dist/polyfit-frontend/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
