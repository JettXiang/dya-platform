FROM nginx:1.27-alpine

COPY web/dist /usr/share/nginx/html
COPY deploy/nginx/nginx.conf /etc/nginx/conf.d/default.conf
COPY deploy/nginx/update.xml /usr/share/nginx/html/update.xml

# 宿主复制进来的静态文件权限不可控，统一放开读权限，避免 nginx worker 403
RUN chmod -R a+rX /usr/share/nginx/html

EXPOSE 80
