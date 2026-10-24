FROM python:3.12-slim
WORKDIR /app
COPY requirements.lock.txt .
RUN pip install --no-cache-dir -r requirements.lock.txt
COPY engine.py validation.py server.py score.py policy.json catalog.json ./
COPY contracts/ ./contracts/
RUN useradd --system --uid 10001 scoring
USER scoring
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=3s --start-period=3s --retries=3 CMD python -c "from urllib.request import urlopen; urlopen('http://127.0.0.1:8000/health/ready',timeout=2).close()"
CMD ["python", "server.py", "--host", "0.0.0.0", "--port", "8000"]
