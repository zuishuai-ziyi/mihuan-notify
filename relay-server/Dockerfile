# mihuan-notify relay-server — Python 3.12-slim + uvicorn
# 构建：docker build -t mihuan-relay .
# 运行：docker run -d --name mihuan-relay -p 8080:8080 -v mihuan-relay-data:/app/data mihuan-relay

FROM python:3.12-slim

WORKDIR /app

# 先拷 requirements 利用层缓存
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py admin.py store.py ./
COPY webui/dist ./webui/dist

# 数据目录（volume 挂载点）；Docker 标记供 IN_DOCKER 检测（Docker 实际的 .dockerenv 在 / 根，不在 /app）
RUN mkdir -p /app/data && touch /app/.dockerenv
VOLUME /app/data

ENV RELAY_HOST=0.0.0.0 \
    RELAY_PORT=8080 \
    PYTHONUNBUFFERED=1

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=3).status == 200 else 1)"

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080"]
