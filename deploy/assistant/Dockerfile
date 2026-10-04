FROM python:3.12-slim

WORKDIR /app
COPY pyproject.toml /app/
COPY python /app/python
COPY scripts/assistant_gateway_container_entrypoint.py /app/serve.py
COPY deploy/assistant/verify.py /app/verify.py
RUN pip install --no-cache-dir '.[assistant-vision-gateway]'

ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
# Supply a read-only, owner-readable JSON configuration at runtime. Provider
# credentials are never build arguments, image layers, or Docker environment.
ENTRYPOINT ["python", "/app/serve.py"]
