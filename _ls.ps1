$dir = 'worldwindjni\src\main\cpp\include\stb'
Get-ChildItem $dir -Force | ForEach-Object {
  $name = $_.Name
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($name)
  $hex = ($bytes | ForEach-Object { $_.ToString('x2') }) -join ' '
  Write-Output ('NAME=[' + $name + '] LEN=' + $name.Length + ' BYTES=' + $hex + ' SIZE=' + $_.Length)
}
