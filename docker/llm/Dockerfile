FROM ollama/ollama:0.40.11

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl jq vulkan-tools mesa-vulkan-drivers \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/llm

COPY backend/src/main/resources/assistant/config.json ./config.json
COPY docker/llm/start.sh ./start.sh
COPY docker/llm/check-vulkan.sh ./check-vulkan.sh

EXPOSE 11434

ENTRYPOINT ["/bin/bash", "/opt/llm/start.sh"]
CMD []
