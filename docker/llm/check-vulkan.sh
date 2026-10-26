set -euo pipefail

summary=$(vulkaninfo --summary 2>/dev/null)
devices=()
index=

while IFS= read -r line; do
    if [[ "$line" =~ ^[[:space:]]*GPU([0-9]+): ]]; then
        index=${BASH_REMATCH[1]}
    fi
    if [[ "$line" =~ deviceType.*(INTEGRATED|DISCRETE)_GPU ]] && [ -n "$index" ]; then
        devices+=("$index")
    fi
done <<< "$summary"

if [ "${#devices[@]}" -eq 0 ]; then
    printf 'No physical Vulkan GPU is available\n' >&2
    exit 1
fi

IFS=,
printf '%s\n' "${devices[*]}"
