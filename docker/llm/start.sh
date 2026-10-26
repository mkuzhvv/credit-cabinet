set -euo pipefail

prepare_model() {
    local config=/opt/llm/config.json
    local api=http://127.0.0.1:11434
    local models=

    for ((attempt = 0; attempt < 60; attempt++)); do
        if models=$(curl --fail --silent --max-time 2 "$api/api/tags"); then
            break
        fi
        if ! kill -0 "$server_pid" 2>/dev/null; then
            printf 'Ollama stopped during startup\n' >&2
            return 1
        fi
        sleep 1
    done

    if [ -z "$models" ]; then
        printf 'Ollama did not become available\n' >&2
        return 1
    fi

    local model expected_digest actual_digest response
    model=$(jq -er '.model' "$config")
    expected_digest=$(jq -er '.model_digest' "$config")

    if ! jq -e --arg name "$model" '.models[] | select(.name == $name)' <<< "$models" >/dev/null; then
        printf 'Downloading %s\n' "$model"
        OLLAMA_HOST=http://127.0.0.1:11434 ollama pull "$model"
        models=$(curl --fail --silent --show-error --max-time 10 "$api/api/tags")
    fi

    actual_digest=$(jq -er --arg name "$model" '.models[] | select(.name == $name) | .digest' <<< "$models")
    if [ "$actual_digest" != "$expected_digest" ]; then
        printf 'Model digest does not match assistant/config.json: %s\n' "$model" >&2
        return 1
    fi
    if jq -e --arg name "$model" '.models[] | select(.name == $name) | ((.remote_host // "") != "")' <<< "$models" >/dev/null; then
        printf 'A local model is required\n' >&2
        return 1
    fi

    printf 'Warming up %s\n' "$model"
    response=$(jq '{model: .model, messages: [], stream: false, think: .think,
        keep_alive: .keep_alive, options: {num_ctx: .num_ctx}}' "$config" |
        curl --fail --silent --show-error --max-time 300 \
            --header 'Content-Type: application/json' --data-binary @- "$api/api/chat")
    if ! jq -e '.done == true and .error == null' <<< "$response" >/dev/null; then
        printf 'Model warm-up did not complete: %s\n' "$response" >&2
        return 1
    fi

    touch /tmp/ollama-ready
    printf 'Ready: %s, digest=%s\n' "$model" "$actual_digest"
}

shutdown() {
    if [ -n "$prepare_pid" ]; then
        kill -TERM "$prepare_pid" 2>/dev/null || true
        wait "$prepare_pid" 2>/dev/null || true
    fi
    kill -TERM "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
}

rm -f /tmp/ollama-ready
ollama serve &
server_pid=$!
prepare_model &
prepare_pid=$!

trap shutdown EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

wait "$prepare_pid"
prepare_pid=
wait "$server_pid"
