param(
    [ValidateSet('auto', 'cpu', 'nvidia', 'vulkan')]
    [string]$Device = 'auto',
    [switch]$Cpu
)

$ErrorActionPreference = 'Stop'
$projectDirectory = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$composeArguments = @('compose', '--project-directory', $projectDirectory, '-f', (Join-Path $projectDirectory 'compose.yaml'))

& docker @composeArguments build
if ($LASTEXITCODE -ne 0) { throw 'Image build failed' }

if ($Cpu) { $Device = 'cpu' }
$selectedDevice = 'cpu'
if ($Device -in @('auto', 'nvidia')) {
    $runtime = & docker info --format '{{if index .Runtimes "nvidia"}}nvidia{{end}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker is unavailable' }
    if ($runtime -eq 'nvidia') {
        try {
            & docker run --rm --gpus all --entrypoint nvidia-smi credit-cabinet-llm:local --query-gpu=name --format=csv,noheader 2>$null
            if ($LASTEXITCODE -eq 0) { $selectedDevice = 'nvidia' }
        } catch {
            Write-Host 'NVIDIA is unavailable to Docker'
        }
    }
}

if ($Device -eq 'vulkan' -or ($Device -eq 'auto' -and $selectedDevice -eq 'cpu' -and $env:OS -ne 'Windows_NT')) {
    try {
        $vulkanDevices = & docker run --rm --device /dev/dri --entrypoint /bin/bash credit-cabinet-llm:local /opt/llm/check-vulkan.sh 2>$null
        if ($LASTEXITCODE -eq 0 -and $vulkanDevices -match '^\d+(,\d+)*$') { $selectedDevice = 'vulkan' }
    } catch {
        Write-Host 'Vulkan is unavailable to Docker'
    }
}

if ($Device -in @('nvidia', 'vulkan') -and $selectedDevice -ne $Device) {
    throw "Requested device '$Device' is unavailable to Docker; check the driver and runtime"
}

$overlay = '{"services":{"ollama":{"environment":{"CUDA_VISIBLE_DEVICES":"-1","OLLAMA_VULKAN":"0"}}}}'
if ($selectedDevice -eq 'nvidia') {
    $overlay = '{"services":{"ollama":{"gpus":"all","environment":{"OLLAMA_VULKAN":"0"}}}}'
} elseif ($selectedDevice -eq 'vulkan') {
    $overlay = '{"services":{"ollama":{"devices":["/dev/dri:/dev/dri"],"environment":{"OLLAMA_VULKAN":"1","CUDA_VISIBLE_DEVICES":"-1","GGML_VK_VISIBLE_DEVICES":"' + $vulkanDevices + '"}}}}'
}

Write-Host "Selected Ollama backend: $selectedDevice"
$overlay | & docker @composeArguments -f - up -d --no-build --wait --wait-timeout 2400
if ($LASTEXITCODE -ne 0) { throw 'Application startup failed; inspect docker compose logs' }

& docker @composeArguments exec -T ollama ollama ps
if ($LASTEXITCODE -ne 0) { throw 'Cannot read model placement' }
Write-Host 'Application is ready. Open http://localhost:8080 (or the port set in APP_PORT).'
