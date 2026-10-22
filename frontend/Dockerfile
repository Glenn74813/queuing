# Build: docker build -t philhealth/qms-frontend:<version> frontend/
FROM node:20.18-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_AGENCY_NAME="PhilHealth"
ENV VITE_AGENCY_NAME=$VITE_AGENCY_NAME
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /src/dist /usr/share/nginx/html
COPY nginx-spa.conf /etc/nginx/conf.d/default.conf
EXPOSE 8081
