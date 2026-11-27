# syntax=docker/dockerfile:1.7
FROM python:3.11-slim

WORKDIR /app

ENV PYTHONUNBUFFERED=1
ENV HOST=0.0.0.0
ENV PORT=8000
ENV PYTHON_BIN=python3

COPY requirements.txt .

RUN pip install --no-cache-dir -r requirements.txt

ARG PHONE_WATCH_INSTALL_BROWSER=1
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    if [ "$PHONE_WATCH_INSTALL_BROWSER" = "1" ]; then \
        echo 'Acquire::Retries "5";' > /etc/apt/apt.conf.d/80-retries \
        && \
        apt-get update \
        && apt-get install -y --no-install-recommends libatomic1 \
        && \
        python -m playwright install-deps chromium; \
    else \
        echo "Skipping Playwright browser system dependency install"; \
    fi

RUN if [ "$PHONE_WATCH_INSTALL_BROWSER" = "1" ]; then \
        python -m playwright install chromium; \
    else \
        echo "Skipping Playwright browser download"; \
    fi

COPY . .

EXPOSE 8000

CMD ["python", "-m", "phone_watch", "serve", "--host", "0.0.0.0", "--port", "8000"]
