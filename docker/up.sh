set -euo pipefail

if [ "$#" -ne 0 ]; then
    printf 'Usage: bash docker/up.sh\nOllama runs natively and selects its available hardware.\n' >&2
    exit 2
fi

project_directory=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compose=(docker compose --project-directory "$project_directory" -f "$project_directory/compose.yaml")
ollama_url=${ASSISTANT_OLLAMA_BASE_URL:-http://host.docker.internal:11434}
ollama_url=${ollama_url%/}

"${compose[@]}" up -d --build --wait --wait-timeout 180 postgres scoring backend frontend

if "${compose[@]}" exec -T backend curl --fail --silent --output /dev/null --connect-timeout 2 --max-time 3 "$ollama_url/api/tags"; then
    printf 'Ollama API is reachable. The backend will verify the model and digest when a message is sent.\n'
else
    printf 'Ollama is not reachable from the backend. Start native Ollama and load qwen3.5:9b-q4_K_M; see docker/README.md. The application remains available.\n' >&2
fi
printf 'Application is ready: http://localhost:%s\n' "${APP_PORT:-8080}"
