param(
    [ValidateSet("iniciar", "testar", "menu", "auditar", "parar")]
    [string]$Acao = "iniciar",
    [string]$UF = "SE"
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
function Invoke-Docker {
    param([string[]]$Argumentos)
    & docker @Argumentos
    if ($LASTEXITCODE -ne 0) { throw "Docker falhou (codigo $LASTEXITCODE)." }
}
switch ($Acao) {
    "iniciar" {
        Invoke-Docker -Argumentos @("compose", "config", "--quiet")
        Invoke-Docker -Argumentos @("compose", "build", "cadastro")
        Invoke-Docker -Argumentos @("compose", "up", "-d")
        Invoke-Docker -Argumentos @("compose", "ps")
    }
    "testar" { Invoke-Docker -Argumentos @("compose", "run", "--rm", "verificar") }
    "menu" { Invoke-Docker -Argumentos @("compose", "run", "--rm", "cliente", "menu", $UF) }
    "auditar" { Invoke-Docker -Argumentos @("compose", "run", "--rm", "cliente", "auditar") }
    "parar" { Invoke-Docker -Argumentos @("compose", "down") }
}
