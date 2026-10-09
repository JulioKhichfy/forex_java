# Instala os programas MQL5 do Jev Forex num terminal MT5 e compila.
#
#   powershell -ExecutionPolicy Bypass -File mql5\install.ps1
#   powershell -ExecutionPolicy Bypass -File mql5\install.ps1 -Terminal "C:\Program Files\MetaTrader 5 EXNESS"
#
# Copia para <pasta de dados>\MQL5\{Include,Services,Experts}\Jev e compila cada programa.
# Rode de novo sempre que mudar algum .mq5/.mqh (o terminal pode ficar aberto).
param(
    [string]$Terminal = "C:\Program Files\MetaTrader 5 EXNESS"
)
$ErrorActionPreference = "Stop"

$metaEditor = Join-Path $Terminal "MetaEditor64.exe"
if (-not (Test-Path $metaEditor)) { throw "MetaEditor não encontrado em $metaEditor" }

# a pasta de dados do terminal tem um origin.txt apontando para a pasta de instalação
$dataRoot = Join-Path $env:APPDATA "MetaQuotes\Terminal"
$data = Get-ChildItem $dataRoot -Directory | Where-Object {
    $origin = Join-Path $_.FullName "origin.txt"
    (Test-Path $origin) -and ((Get-Content $origin -Encoding Unicode -Raw).Trim() -eq $Terminal)
} | Select-Object -First 1
if (-not $data) { throw "Pasta de dados de $Terminal não encontrada em $dataRoot (abra o terminal uma vez)" }
$mql5 = Join-Path $data.FullName "MQL5"
Write-Output "Terminal: $Terminal"
Write-Output "MQL5:     $mql5"

$targets = @(
    @{ From = "Include\Jev";  To = "Include\Jev";  Filter = "*.mqh" },
    @{ From = "Services";     To = "Services\Jev"; Filter = "*.mq5" },
    @{ From = "Experts";      To = "Experts\Jev";  Filter = "*.mq5" }
)
foreach ($t in $targets) {
    $dest = Join-Path $mql5 $t.To
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item (Join-Path $PSScriptRoot "$($t.From)\$($t.Filter)") $dest -Force
}

$failed = 0
foreach ($p in @("Services\Jev\JevCalendarExporter.mq5", "Services\Jev\JevCandleExporter.mq5", "Experts\Jev\JevExecutor.mq5")) {
    $file = Join-Path $mql5 $p
    $log = [IO.Path]::ChangeExtension($file, ".log")
    Start-Process -FilePath $metaEditor -ArgumentList "/compile:`"$file`"", "/log:`"$log`"" -Wait -NoNewWindow
    $result = Get-Content $log -Encoding Unicode | Where-Object { $_ -match "^Result:" }
    Write-Output ("{0,-45} {1}" -f $p, $result)
    if ($result -notmatch "^Result: 0 errors") {
        $failed++
        Get-Content $log -Encoding Unicode | Where-Object { $_ -match "error" } | Write-Output
    }
}
if ($failed -gt 0) { throw "$failed programa(s) com erro de compilação" }
Write-Output "Pronto. No MT5: Navegador → atualize (botão direito → Atualizar) para ver Serviços\Jev e Experts\Jev."
