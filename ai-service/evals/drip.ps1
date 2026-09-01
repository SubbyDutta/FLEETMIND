# P12 eval quota-drip: run once daily AFTER ~12:35 PM IST (free-tier quota
# resets at midnight US-Pacific). Tries every API key in ai-service/.env.keys
# (one per line, # comments ok) until each hits its daily wall; --resume means
# only unanswered scenarios run, so progress accumulates across days.
$svc = Split-Path $PSScriptRoot -Parent
Set-Location $svc

$keysFile = Join-Path $svc ".env.keys"
if (-not (Test-Path $keysFile)) { Write-Host "no .env.keys file — see comment above"; exit 1 }
$keys = Get-Content $keysFile | Where-Object { $_ -match '\S' -and $_ -notmatch '^\s*#' }

foreach ($k in $keys) {
    $tail = $k.Substring([Math]::Max(0, $k.Length - 6))
    Write-Host "=== drip pass with key ...$tail ==="
    (Get-Content "$svc\.env") | ForEach-Object {
        if ($_ -match '^GEMINI_API_KEY=') { "GEMINI_API_KEY=$k" } else { $_ }
    } | Set-Content -Encoding ascii "$svc\.env"

    .venv\Scripts\python.exe -m evals.runner --resume --pace=30
}

.venv\Scripts\python.exe -c "import json,glob; recs=[json.load(open(f,encoding='utf-8')) for f in glob.glob('evals/out/*.json')]; done=sum(1 for r in recs if r.get('answer')); print(f'=== progress: {done}/30 scenarios answered ===')"
