$ErrorActionPreference = 'Stop'
$projectDirectory = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$composeArguments = @('compose', '--project-directory', $projectDirectory, '-f', (Join-Path $projectDirectory 'compose.yaml'))
$ollamaUrl = if ($env:ASSISTANT_OLLAMA_BASE_URL) { $env:ASSISTANT_OLLAMA_BASE_URL.TrimEnd('/') } else { 'http://host.docker.internal:11434' }
$appPort = if ($env:APP_PORT) { $env:APP_PORT } else { '8080' }

& docker @composeArguments up -d --build --wait --wait-timeout 180 postgres scoring backend frontend
if ($LASTEXITCODE -ne 0) { throw 'Application startup failed; inspect docker compose logs' }

& docker @composeArguments exec -T backend curl --fail --silent --output /dev/null --connect-timeout 2 --max-time 3 "$ollamaUrl/api/tags"
if ($LASTEXITCODE -ne 0) {
    Write-Warning 'Ollama is not reachable from the backend. Start native Ollama and load qwen3.5:9b-q4_K_M; see docker/README.md. The application remains available.'
} else {
    Write-Host 'Ollama API is reachable. The backend will verify the model and digest when a message is sent.'
}
Write-Host "Application is ready: http://localhost:$appPort"
