set -euo pipefail

device=auto
if [ "$#" -eq 1 ] && [ "$1" = '--cpu' ]; then
    device=cpu
elif [ "$#" -eq 2 ] && [ "$1" = '--device' ]; then
    device=$2
elif [ "$#" -ne 0 ]; then
    printf 'Usage: bash docker/up.sh [--cpu | --device auto|cpu|nvidia|vulkan]\n' >&2
    exit 2
fi
case "$device" in
    auto|cpu|nvidia|vulkan) ;;
    *) printf 'Unknown device: %s\n' "$device" >&2; exit 2 ;;
esac

project_directory=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compose=(docker compose --project-directory "$project_directory" -f "$project_directory/compose.yaml")

"${compose[@]}" build

selected_device=cpu
if [ "$device" = auto ] || [ "$device" = nvidia ]; then
    runtime=$(docker info --format '{{if index .Runtimes "nvidia"}}nvidia{{end}}')
    if [ "$runtime" = 'nvidia' ]; then
        if docker run --rm --gpus all --entrypoint nvidia-smi credit-cabinet-llm:local --query-gpu=name --format=csv,noheader 2>/dev/null; then
            selected_device=nvidia
        fi
    fi
fi

if [ "$device" = vulkan ] || { [ "$device" = auto ] && [ "$selected_device" = cpu ] && [ "$(uname -s)" = Linux ]; }; then
    if vulkan_devices=$(docker run --rm --device /dev/dri --entrypoint /bin/bash credit-cabinet-llm:local /opt/llm/check-vulkan.sh 2>/dev/null); then
        if [[ "$vulkan_devices" =~ ^[0-9]+(,[0-9]+)*$ ]]; then
            selected_device=vulkan
        fi
    fi
fi

if { [ "$device" = nvidia ] || [ "$device" = vulkan ]; } && [ "$selected_device" != "$device" ]; then
    printf 'Requested device %s is unavailable to Docker; check the driver and runtime\n' "$device" >&2
    exit 1
fi

overlay='{"services":{"ollama":{"environment":{"CUDA_VISIBLE_DEVICES":"-1","OLLAMA_VULKAN":"0"}}}}'
if [ "$selected_device" = nvidia ]; then
    overlay='{"services":{"ollama":{"gpus":"all","environment":{"OLLAMA_VULKAN":"0"}}}}'
elif [ "$selected_device" = vulkan ]; then
    printf -v overlay '{"services":{"ollama":{"devices":["/dev/dri:/dev/dri"],"environment":{"OLLAMA_VULKAN":"1","CUDA_VISIBLE_DEVICES":"-1","GGML_VK_VISIBLE_DEVICES":"%s"}}}}' "$vulkan_devices"
fi

printf 'Selected Ollama backend: %s\n' "$selected_device"
printf '%s\n' "$overlay" | "${compose[@]}" -f - up -d --no-build --wait --wait-timeout 2400

"${compose[@]}" exec -T ollama ollama ps
printf 'Application is ready. Open http://localhost:8080 (or the port set in APP_PORT).\n'
