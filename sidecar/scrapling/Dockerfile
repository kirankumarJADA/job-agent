# Robin's local Scrapling sidecar.
#
# Plain HTTP mode needs no browser binaries. Dynamic/stealthy modes need the
# browser toolchain — rebuild with --build-arg INSTALL_BROWSERS=true to add it
# (large download; not required for the default http mode).
FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

ARG INSTALL_BROWSERS=false
RUN if [ "$INSTALL_BROWSERS" = "true" ]; then python -m scrapling install; fi

COPY app.py .

ENV SCRAPLING_SERVICE_HOST=0.0.0.0 \
    SCRAPLING_SERVICE_PORT=8199
EXPOSE 8199

# Never publish this port to anything but loopback/host-internal networks:
# the sidecar fetches arbitrary URLs on request and is a local operator tool.
CMD ["python", "app.py"]
