$dir = 'worldwindjni\src\main\cpp\include\stb'
$bad = Join-Path $dir ' stb_truetype.h'
$good = Join-Path $dir 'stb_truetype.h'
if (Test-Path -LiteralPath $bad) {
  Move-Item -LiteralPath $bad -Destination $good -Force
  Write-Output 'RENAMED leading-space file -> stb_truetype.h'
} else {
  Write-Output 'leading-space file not found (already fixed?)'
}
Write-Output '--- dir listing after rename ---'
Get-ChildItem $dir -Force | ForEach-Object { Write-Output ('[' + $_.Name + '] ' + $_.Length) }
Write-Output '--- head of stb_truetype.h ---'
Get-Content -LiteralPath $good -TotalCount 40 | Select-Object -Skip 0
